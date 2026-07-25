/*
 * Draws the MCP icons (SEP-973) that the server advertises on its serverInfo, tools, prompt,
 * resource and resource template. Not part of the Gradle build — a one-off generator kept in the
 * repo so the PNG assets under `server/src/main/resources/icons/` are reproducible and editable.
 *
 * Run (JDK 11+ single-file source launch), from the repo root:
 *
 *     java assets/icons/GenerateIcons.java
 *
 * PNG is deliberate: the MCP spec requires icon-rendering clients to support image/png, while
 * image/svg+xml is only a SHOULD and carries an executable-content caveat clients may refuse.
 * Every glyph is drawn on a 128-unit grid and rasterized straight to the target size, so the
 * geometry below — not a downscaled bitmap — is the source of truth.
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

public final class GenerateIcons {

    /** Design grid; every glyph below is expressed in these units. Matches assets/icon.svg. */
    private static final double GRID = 128;

    /**
     * Flat, not the gradient of assets/icon.svg: these bytes ride inline (base64 data: URI) in
     * every tools/list, and a flat field halves the PNG. Indistinguishable at 48px.
     */
    private static final Color BRAND = new Color(0x7F52FF);

    /** A named glyph and the pixel sizes it is rasterized at. */
    private record Glyph(String name, int[] sizes, Consumer<Graphics2D> draw) {
        Glyph(String name, Consumer<Graphics2D> draw) {
            this(name, new int[] { 48 }, draw);
        }
    }

    private static final List<Glyph> GLYPHS = List.of(
        // Server brand mark: magnifier over code. Also rendered at 96 — connector UIs show it big.
        new Glyph("server", new int[] { 48, 96 }, g -> {
            stroke(g, 8, circle(58, 56, 26));
            stroke(g, 10, line(77, 75, 98, 96));
            stroke(g, 6, poly(52, 46, 42, 56, 52, 66));
            stroke(g, 6, poly(64, 46, 74, 56, 64, 66));
        }),
        // fetch_library: download into a tray.
        new Glyph("fetch", g -> {
            stroke(g, 10, line(64, 26, 64, 72));
            stroke(g, 10, poly(44, 56, 64, 76, 84, 56));
            stroke(g, 10, line(34, 98, 94, 98));
        }),
        // list_packages: a closed box.
        new Glyph("packages", g -> {
            stroke(g, 9, rrect(28, 34, 72, 62, 8));
            stroke(g, 9, line(28, 58, 100, 58));
            stroke(g, 9, line(64, 34, 64, 58));
        }),
        // list_declarations: a bulleted list.
        new Glyph("declarations", g -> {
            for (double y : new double[] { 42, 64, 86 }) {
                g.fill(circle(34, y, 6));
                stroke(g, 9, line(54, y, 98, y));
            }
        }),
        // get_api_signature: a call signature — parentheses around a value.
        new Glyph("signature", g -> {
            stroke(g, 9, curve(50, 32, 32, 64, 50, 96));
            stroke(g, 9, curve(78, 32, 96, 64, 78, 96));
            g.fill(circle(64, 64, 8));
        }),
        // get_kdoc: a documented page.
        new Glyph("kdoc", g -> {
            stroke(g, 9, rrect(34, 24, 60, 80, 10));
            stroke(g, 7, line(50, 50, 78, 50));
            stroke(g, 7, line(50, 66, 78, 66));
            stroke(g, 7, line(50, 82, 68, 82));
        }),
        // get_source: raw code.
        new Glyph("source", g -> {
            stroke(g, 10, poly(54, 36, 30, 64, 54, 92));
            stroke(g, 10, poly(74, 36, 98, 64, 74, 92));
        }),
        // search_source: a magnifier — deliberately echoes the brand mark, minus the code chevrons.
        new Glyph("search", g -> {
            stroke(g, 10, circle(56, 54, 26));
            stroke(g, 12, line(76, 74, 100, 98));
        }),
        // get_dependencies: a dependency tree.
        new Glyph("dependencies", g -> {
            stroke(g, 7, line(64, 34, 64, 64));
            stroke(g, 7, line(36, 64, 92, 64));
            stroke(g, 7, line(36, 64, 36, 88));
            stroke(g, 7, line(92, 64, 92, 88));
            g.fill(circle(64, 28, 11));
            g.fill(circle(36, 96, 11));
            g.fill(circle(92, 96, 11));
        }),
        // list_versions: a stack of releases.
        new Glyph("versions", g -> {
            g.fill(rrect(28, 32, 72, 18, 8));
            g.fill(rrect(28, 56, 72, 18, 8));
            g.fill(rrect(28, 80, 72, 18, 8));
        }),
        // get_latest_version: the newest one, on top.
        new Glyph("latest", g -> {
            stroke(g, 10, line(64, 96, 64, 36));
            stroke(g, 10, poly(40, 60, 64, 34, 88, 60));
        }),
        // explain_public_api prompt: a spoken explanation.
        new Glyph("prompt", g -> {
            stroke(g, 9, rrect(24, 26, 80, 58, 16));
            stroke(g, 9, poly(44, 84, 44, 106, 66, 84));
        }),
        // Library index resource + template: the parsed API, as a book.
        new Glyph("index", g -> {
            stroke(g, 9, rrect(30, 24, 68, 80, 10));
            stroke(g, 9, line(48, 24, 48, 104));
            stroke(g, 7, line(62, 50, 84, 50));
            stroke(g, 7, line(62, 70, 84, 70));
        })
    );

    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args.length > 0 ? args[0] : "server/src/main/resources/icons");
        Files.createDirectories(out);
        for (Glyph glyph : GLYPHS) {
            for (int size : glyph.sizes()) {
                Path file = out.resolve(glyph.name() + "-" + size + ".png");
                ImageIO.write(render(glyph, size), "png", file.toFile());
                System.out.println(file + " (" + Files.size(file) + " bytes)");
            }
        }
        if (args.length > 1) {
            ImageIO.write(contactSheet(), "png", Paths.get(args[1]).toFile());
            System.out.println(args[1] + " (contact sheet)");
        }
    }

    private static BufferedImage render(Glyph glyph, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / GRID, size / GRID);
        g.setPaint(BRAND);
        g.fill(rrect(0, 0, GRID, GRID, 28));
        g.setPaint(Color.WHITE);
        glyph.draw().accept(g);
        g.dispose();
        return image;
    }

    /** All glyphs at 48px on a neutral background, for eyeballing the set as a whole. */
    private static BufferedImage contactSheet() {
        int cell = 64, cols = 5, rows = (GLYPHS.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setPaint(new Color(0x2A2A2E));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int i = 0; i < GLYPHS.size(); i++) {
            g.drawImage(render(GLYPHS.get(i), 48), (i % cols) * cell + 8, (i / cols) * cell + 8, null);
        }
        g.dispose();
        return sheet;
    }

    // --- shape helpers, all in 128-unit design space ---

    private static void stroke(Graphics2D g, double width, Shape shape) {
        g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
    }

    private static Shape line(double x1, double y1, double x2, double y2) {
        return new Line2D.Double(x1, y1, x2, y2);
    }

    private static Shape circle(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
    }

    private static Shape rrect(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Double(x, y, w, h, 2 * r, 2 * r);
    }

    private static Shape poly(double... xy) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            path.lineTo(xy[i], xy[i + 1]);
        }
        return path;
    }

    private static Shape curve(double x1, double y1, double cx, double cy, double x2, double y2) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.quadTo(cx, cy, x2, y2);
        return path;
    }
}
