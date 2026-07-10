@file:OptIn(ExperimentalPathApi::class)

package app.oreshkov.kotlinlibmcp.cache

import app.oreshkov.kotlinlibmcp.core.LibraryCache
import app.oreshkov.kotlinlibmcp.model.LibraryCoordinate
import app.oreshkov.kotlinlibmcp.model.LibraryIndex
import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Shared on-disk layout under the cache root, used by both the cache and the fetcher so a
 * fetch lands directly in its cached location:
 * `<root>/<group>/<artifact>/<version>/{sources/{common,jvm}/, jars/, fetch-result.json, index.json}`.
 * Group dirs keep their dots (`io.ktor`) so the tree stays a fixed three levels deep and
 * human-browsable (the Phase 08 dashboard lists it).
 */
internal object CacheLayout {
    const val FETCH_RESULT_FILE: String = "fetch-result.json"
    const val INDEX_FILE: String = "index.json"
    const val SOURCES_DIR: String = "sources"
    const val JARS_DIR: String = "jars"

    fun versionDir(root: Path, coordinate: LibraryCoordinate): Path =
        root.resolve(coordinate.group).resolve(coordinate.artifact).resolve(coordinate.version)
}

/**
 * [LibraryCache] backed by a plain directory tree (see [CacheLayout]). All IO runs on
 * [Dispatchers.IO]; corrupt cache entries degrade to a miss instead of failing.
 */
public class OnDiskLibraryCache(
    private val root: Path = defaultCacheRoot(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) : LibraryCache {

    private val log = Logger.withTag("OnDiskLibraryCache")

    override suspend fun get(coordinate: LibraryCoordinate): LibraryIndex? =
        withContext(Dispatchers.IO) {
            val file = CacheLayout.versionDir(root, coordinate).resolve(CacheLayout.INDEX_FILE)
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString<LibraryIndex>(file.readText()) }
                .onFailure { log.w(it) { "Corrupt index for $coordinate at $file; treating as miss" } }
                .getOrNull()
        }

    override suspend fun putIndex(index: LibraryIndex): Unit =
        withContext(Dispatchers.IO) {
            val dir = CacheLayout.versionDir(root, index.coordinate).createDirectories()
            dir.resolve(CacheLayout.INDEX_FILE).writeText(json.encodeToString(index))
        }

    override suspend fun putSources(coordinate: LibraryCoordinate, extractedDir: String): Unit =
        withContext(Dispatchers.IO) {
            val canonical = CacheLayout.versionDir(root, coordinate)
                .resolve(CacheLayout.SOURCES_DIR).toAbsolutePath().normalize()
            val source = Path.of(extractedDir).toAbsolutePath().normalize()
            when {
                source == canonical -> Unit // fetcher already extracted in place
                !source.exists() -> log.w { "putSources($coordinate): $source does not exist; ignoring" }
                else -> {
                    canonical.parent?.createDirectories()
                    source.copyToRecursively(canonical, followLinks = false, overwrite = true)
                }
            }
        }

    override suspend fun list(): List<LibraryCoordinate> =
        withContext(Dispatchers.IO) {
            if (!root.exists()) return@withContext emptyList()
            root.listDirectoryEntries().filter { it.isDirectory() }.flatMap { group ->
                group.listDirectoryEntries().filter { it.isDirectory() }.flatMap { artifact ->
                    artifact.listDirectoryEntries().filter { it.isDirectory() }.mapNotNull { version ->
                        LibraryCoordinate.parseOrNull("${group.name}:${artifact.name}:${version.name}")
                    }
                }
            }
        }

    override suspend fun clear(coordinate: LibraryCoordinate): Unit =
        withContext(Dispatchers.IO) {
            CacheLayout.versionDir(root, coordinate).deleteRecursively()
        }

    override suspend fun size(): Long =
        withContext(Dispatchers.IO) {
            if (!root.exists()) return@withContext 0L
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
            }
        }

    public companion object {
        /**
         * Platform cache dir + `kotlin-lib-mcp`: `%LOCALAPPDATA%` on Windows,
         * `~/Library/Caches` on macOS, `$XDG_CACHE_HOME` (or `~/.cache`) elsewhere.
         */
        public fun defaultCacheRoot(): Path {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            val base = when {
                os.contains("win") ->
                    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: Path.of(home, "AppData", "Local")
                os.contains("mac") -> Path.of(home, "Library", "Caches")
                else ->
                    System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: Path.of(home, ".cache")
            }
            return base.resolve("kotlin-lib-mcp")
        }
    }
}
