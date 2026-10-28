// Offline checks for plugin/launcher/KotlinLibMcpLauncher.java, run by CI's plugin-launcher job:
//
//   javac --release 21 -d out plugin/launcher/*.java .github/launcher-test/*.java
//   java -cp out KotlinLibMcpLauncherTest
//
// The launcher's other CI steps install a real release, which only ever takes the happy path: a
// correct digest, a well-formed zip, a version directory with nothing beside it. These cover the
// branches that path never reaches — the security checks above all, any of which could be inverted
// with CI staying green. No network: every input is built here.
//
// It lives outside plugin/ because everything there ships with the plugin. It is a plain main
// rather than JUnit for the launcher's own reason: two `javac`/`java` commands on a bare JDK, the
// same way the launcher itself runs, with nothing to resolve first. It reaches the launcher's
// package-private methods by sharing its (unnamed) package.

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class KotlinLibMcpLauncherTest {

    private static final URI API = URI.create("https://api.github.com/repos/aoreshkov/kotlin-lib-mcp/releases/tags/v0.9.0");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("launcher-test");
        try {
            run(tmp);
        } finally {
            deleteTree(tmp);
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
    }

    private static void run(Path tmp) throws Exception {
        // --- unpack: zip-slip -------------------------------------------------------------------
        List<String> slips = List.of("server-0.9.0/../escape.txt", "server-0.9.0/lib/../../escape.txt");
        for (int i = 0; i < slips.size(); i++) {
            String evil = slips.get(i);
            Path target = Files.createDirectories(tmp.resolve("slip-" + i).resolve("target"));
            byte[] zip = zip("server-0.9.0/lib/a.jar", evil);
            check("unpack rejects " + evil, () -> {
                IOException e = expectThrows(() -> KotlinLibMcpLauncher.unpack(zip, target));
                return e.getMessage().startsWith("Unsafe zip entry")
                        && !Files.exists(target.getParent().resolve("escape.txt"));
            });
        }
        check("unpack installs a well-formed archive, without its top-level directory", () -> {
            Path target = Files.createDirectories(tmp.resolve("good"));
            KotlinLibMcpLauncher.unpack(zip("server-0.9.0/", "server-0.9.0/lib/a.jar", "server-0.9.0/bin/server"), target);
            return Files.isRegularFile(target.resolve("lib/a.jar")) && Files.isRegularFile(target.resolve("bin/server"));
        });
        check("unpack refuses an archive with no lib/", () -> {
            Path target = Files.createDirectories(tmp.resolve("nolib"));
            return expectThrows(() -> KotlinLibMcpLauncher.unpack(zip("server-0.9.0/bin/server"), target))
                    .getMessage().contains("no lib/");
        });

        // --- digestOf: the digest belongs to the asset asked for ----------------------------------
        String zipAsset = "kotlin-lib-mcp-server-0.9.0.zip";
        String tarAsset = "kotlin-lib-mcp-server-0.9.0.tar";
        check("digestOf takes the asset's own digest when another asset comes first", () ->
                KotlinLibMcpLauncher.digestOf(release(asset(tarAsset, B), asset(zipAsset, A)), "0.9.0", zipAsset).equals(A));
        check("digestOf takes the asset's own digest when another asset follows", () ->
                KotlinLibMcpLauncher.digestOf(release(asset(zipAsset, A), asset(tarAsset, B)), "0.9.0", zipAsset).equals(A));
        check("digestOf does not borrow the next asset's digest when its own is missing", () ->
                expectThrows(() -> KotlinLibMcpLauncher.digestOf(
                        release(asset(zipAsset, null), asset(tarAsset, B)), "0.9.0", zipAsset))
                        .getMessage().contains("no " + zipAsset));
        check("digestOf fails on a release without the asset", () ->
                expectThrows(() -> KotlinLibMcpLauncher.digestOf(release(asset(tarAsset, B)), "0.9.0", zipAsset)) != null);

        // --- verifyChecksum ---------------------------------------------------------------------
        byte[] payload = "the release zip".getBytes(StandardCharsets.UTF_8);
        String real = sha256(payload);
        check("verifyChecksum accepts the matching digest, in either case", () -> {
            KotlinLibMcpLauncher.verifyChecksum(payload, real, "x.zip");
            KotlinLibMcpLauncher.verifyChecksum(payload, real.toUpperCase(), "x.zip");
            return true;
        });
        check("verifyChecksum rejects a mismatch", () ->
                expectThrows(() -> KotlinLibMcpLauncher.verifyChecksum(payload, A, "x.zip"))
                        .getMessage().startsWith("Checksum mismatch"));

        // --- versions -----------------------------------------------------------------------------
        check("versionKey ranks 0.10.0 above 0.9.0", () ->
                KotlinLibMcpLauncher.versionKey("0.10.0").compareTo(KotlinLibMcpLauncher.versionKey("0.9.0")) > 0);
        check("versionKey ranks 1.0.0 above 0.10.0", () ->
                KotlinLibMcpLauncher.versionKey("1.0.0").compareTo(KotlinLibMcpLauncher.versionKey("0.10.0")) > 0);
        check("newestInstalled picks 0.10.0, skipping unfinished and non-version directories", () -> {
            Path dist = tmp.resolve("newest");
            installed(dist, "0.9.0", "0.10.0", "0.9.1", "notes");
            Files.createDirectories(dist.resolve("1.0.0")); // no lib/: not installed
            return "0.10.0".equals(KotlinLibMcpLauncher.newestInstalled(dist));
        });

        // --- pruneSuperseded ----------------------------------------------------------------------
        check("pruneSuperseded keeps the current install and the newest other, nothing else named as a version", () -> {
            Path dist = tmp.resolve("prune/dist");
            Path staging = tmp.resolve("prune/staging");
            installed(dist, "0.8.0", "0.9.0", "0.9.1", "0.10.0", "notes");
            KotlinLibMcpLauncher.pruneSuperseded(dist, "0.10.0", staging);
            return names(dist).equals(Set.of("0.9.1", "0.10.0", "notes")) && names(staging).isEmpty();
        });
        check("pruneSuperseded with one other install keeps it", () -> {
            Path dist = tmp.resolve("prune-one/dist");
            installed(dist, "0.9.0", "0.10.0");
            KotlinLibMcpLauncher.pruneSuperseded(dist, "0.10.0", tmp.resolve("prune-one/staging"));
            return names(dist).equals(Set.of("0.9.0", "0.10.0"));
        });

        // --- GitHub failures: a 404 is the only "No release" ------------------------------------
        check("404 is reported as a release that does not exist", () ->
                KotlinLibMcpLauncher.releaseLookupFailure("0.9.0", status(404, Map.of(), ""))
                        .getMessage().startsWith("No release v0.9.0"));
        check("a primary rate limit is reported as one, with its reset time", () -> {
            KotlinLibMcpLauncher.HttpStatusException e = status(403,
                    Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1798761600"), "");
            String message = KotlinLibMcpLauncher.releaseLookupFailure("0.9.0", e).getMessage();
            return e.rateLimited && Instant.ofEpochSecond(1798761600).equals(e.retryAt)
                    && message.startsWith("GitHub rate-limited") && message.contains("until 00:00 UTC")
                    && message.contains("KOTLIN_LIB_MCP_SERVER_HOME");
        });
        check("a 403 or 429 with retry-after is a rate limit", () ->
                status(403, Map.of("retry-after", "60"), "").rateLimited
                        && status(429, Map.of("retry-after", "60"), "").retryAt != null);
        check("a 429 with neither header is a rate limit", () -> status(429, Map.of(), "").rateLimited);
        check("a secondary rate limit named only in the body is one", () ->
                status(403, Map.of(), "{\"message\":\"You have exceeded a secondary rate limit.\"}").rateLimited);
        check("any other 403 is not a rate limit, and not a missing release", () -> {
            KotlinLibMcpLauncher.HttpStatusException e = status(403, Map.of("x-ratelimit-remaining", "59"), "{\"message\":\"Forbidden\"}");
            String message = KotlinLibMcpLauncher.releaseLookupFailure("0.9.0", e).getMessage();
            return !e.rateLimited && message.startsWith("GitHub answered HTTP 403");
        });
        check("a refused connection is reported as no route to GitHub", () -> {
            String message = KotlinLibMcpLauncher.releaseLookupFailure("0.9.0", new ConnectException("refused")).getMessage();
            return message.startsWith("Could not reach GitHub") && message.contains("KOTLIN_LIB_MCP_SERVER_HOME");
        });

        // --- main: a wrong KOTLIN_LIB_MCP_SERVER_HOME is a one-line reason and exit 2 -------------
        Path noLib = Files.createDirectories(tmp.resolve("home-no-lib"));
        Path noJars = Files.createDirectories(tmp.resolve("home-no-jars/lib"));
        for (Object[] c : new Object[][] {{noLib, "Not a server distribution"}, {noJars.getParent(), "No jars in"}}) {
            check("main reports a server home with " + c[1] + " and exits 2", () -> {
                Launch run = launch((Path) c[0]);
                return run.exit == 2 && run.stderr.contains((String) c[1]) && !run.stderr.contains("\tat ");
            });
        }
    }

    // --- harness ---------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void check(String name, Check check) {
        try {
            if (check.holds()) {
                passed++;
                System.out.println("ok      " + name);
                return;
            }
            failures.add(name);
        } catch (Exception e) {
            failures.add(name + " (" + e + ")");
        }
        System.out.println("FAILED  " + name);
    }

    /** The IOException {@code action} throws; anything else, or nothing, fails the check. */
    private static IOException expectThrows(Action action) throws Exception {
        try {
            action.run();
        } catch (IOException e) {
            return e;
        }
        throw new IllegalStateException("expected an IOException, and nothing was thrown");
    }

    // --- fixtures --------------------------------------------------------------------------------

    /** A zip holding one byte per file entry; names ending in / are directories. */
    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) out.write('x');
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A release as GitHub's API returns it, trimmed to what matters, field order included. */
    private static String release(String... assets) {
        return "{\"tag_name\":\"v0.9.0\",\"assets\":[" + String.join(",", assets) + "],\"body\":\"notes\"}";
    }

    private static String asset(String name, String digest) {
        return "{\"url\":\"https://api.github.com/x\",\"name\":\"" + name + "\",\"label\":\"\","
                + "\"uploader\":{\"login\":\"github-actions[bot]\"},\"content_type\":\"application/zip\","
                + "\"size\":1,\"digest\":" + (digest == null ? "null" : "\"sha256:" + digest + "\"") + ","
                + "\"browser_download_url\":\"https://github.com/aoreshkov/kotlin-lib-mcp/releases/download/v0.9.0/" + name + "\"}";
    }

    private static KotlinLibMcpLauncher.HttpStatusException status(int code, Map<String, String> headers, String body) {
        Map<String, List<String>> multi = new HashMap<>();
        headers.forEach((k, v) -> multi.put(k, List.of(v)));
        return KotlinLibMcpLauncher.HttpStatusException.of(
                API, code, HttpHeaders.of(multi, (k, v) -> true), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates each directory under {@code root} with a lib/ in it, as a finished install has. */
    private static void installed(Path root, String... names) throws IOException {
        for (String name : names) Files.createDirectories(root.resolve(name).resolve("lib"));
    }

    private static Set<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private record Launch(int exit, String stderr) {}

    /** Runs the launcher's main in a fresh JVM: it ends in System.exit, and that is what is checked. */
    private static Launch launch(Path home) throws Exception {
        Path classes = Path.of(KotlinLibMcpLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), "KotlinLibMcpLauncher");
        builder.environment().put("KOTLIN_LIB_MCP_SERVER_HOME", home.toString());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Launch(process.waitFor(), stderr);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private KotlinLibMcpLauncherTest() {}
}
