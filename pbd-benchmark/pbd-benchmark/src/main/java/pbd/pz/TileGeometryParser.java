package pbd.pz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Project Zomboid's tileGeometry.txt format: a version header, then
 * one or more tilesets, each holding a grid of tiles, each tile holding
 * zero or more axis-aligned boxes (the collision/render geometry) plus a
 * free-form properties block.
 *
 * Grammar (reverse-engineered from a sample of the real file, not from
 * official PZ documentation - if a full file reveals a construct this
 * doesn't handle, that's the first place to look, not a sign the whole
 * approach is wrong):
 *
 * <pre>
 * tileGeometry {
 *     VERSION = 2,
 *     tileset {
 *         name = someName,
 *         /&#42; optional per-tile asset name comment &#42;/
 *         tile {
 *             xy = 0x0,
 *             box {
 *                 translate = 0x0x0,
 *                 rotate = 0x0x0,
 *                 min = -4500x0x-6000,
 *                 max = 5000x7200x3700,
 *             }
 *             properties {
 *                 Surface = 29,
 *                 Translucent = true,
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * Vectors are written "AxBxC" (3D) or "AxB" (2D for the tile grid
 * position) - a literal lowercase 'x' between components, never
 * confusable with a numeric value since 'x' doesn't otherwise appear in
 * a number, negative or not.
 */
public final class TileGeometryParser {

    public static final class Box {
        public final double[] translate;
        public final double[] rotate;
        public final double[] min;
        public final double[] max;
        public int order; // position among ALL shapes (box/cylinder/polygon/unknown) in this tile, in file order

        public Box(double[] translate, double[] rotate, double[] min, double[] max) {
            this.translate = translate;
            this.rotate = rotate;
            this.min = min;
            this.max = max;
        }

        /** True if this box has zero or negative volume on any axis - a
         * data artifact (or an intentional zero-thickness marker), not
         * real collision/render geometry, and not safely representable
         * as a PBD cube (which needs a positive size on every axis). */
        public boolean isDegenerate() {
            for (int i = 0; i < 3; i++) {
                if (max[i] - min[i] <= 0.0) return true;
            }
            return false;
        }
    }

    public static final class Cylinder {
        public final double[] translate;
        public final double[] rotate;
        public final double radius1, radius2, height;
        public int order;

        public Cylinder(double[] translate, double[] rotate, double radius1, double radius2, double height) {
            this.translate = translate;
            this.rotate = rotate;
            this.radius1 = radius1;
            this.radius2 = radius2;
            this.height = height;
        }
    }

    public static final class Polygon {
        public final double[] translate;
        public final double[] rotate;
        public final String plane; // "XZ" in every sample seen - which 2D axes the point pairs address
        public final double[][] points; // each entry is {a, b} in the named plane's own 2D axes
        public int order;

        public Polygon(double[] translate, double[] rotate, String plane, double[][] points) {
            this.translate = translate;
            this.rotate = rotate;
            this.plane = plane;
            this.points = points;
        }
    }

    /** Any block type this parser doesn't specifically know about yet -
     * kept (name + raw fields) rather than dropped, and the converter
     * logs every one of these to the console rather than silently
     * skipping it, since a shape type nobody's told this parser about
     * yet is exactly the kind of thing worth surfacing loudly. */
    public static final class UnknownShape {
        public final String blockName;
        public final Map<String, String> fields;

        public UnknownShape(String blockName, Map<String, String> fields) {
            this.blockName = blockName;
            this.fields = fields;
        }
    }

    public static final class Tile {
        public final int xyX, xyY;
        public final String comment; // per-tile asset name annotation, e.g. "furniture_bedding_01_0" - nullable
        public final List<Box> boxes = new ArrayList<>();
        public final List<Cylinder> cylinders = new ArrayList<>();
        public final List<Polygon> polygons = new ArrayList<>();
        public final List<UnknownShape> unknownShapes = new ArrayList<>();
        public final Map<String, String> properties = new LinkedHashMap<>();

        public Tile(int xyX, int xyY, String comment) {
            this.xyX = xyX;
            this.xyY = xyY;
            this.comment = comment;
        }

        public boolean isEmpty() {
            return boxes.isEmpty() && cylinders.isEmpty() && polygons.isEmpty() && unknownShapes.isEmpty();
        }
    }

    public static final class Tileset {
        public final String name;
        public final List<Tile> tiles = new ArrayList<>();

        public Tileset(String name) {
            this.name = name;
        }
    }

    public static final class TileGeometryFile {
        public int version;
        public final List<Tileset> tilesets = new ArrayList<>();
    }

    public TileGeometryFile parseFile(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return parse(text);
    }

    public TileGeometryFile parse(String text) {
        Cursor c = new Cursor(text);
        TileGeometryFile file = new TileGeometryFile();

        c.expectIdentifier("tileGeometry");
        c.expect('{');
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            if (key.equals("VERSION")) {
                c.expect('=');
                file.version = Integer.parseInt(c.readRawValue());
                c.skipOptionalComma();
            } else if (key.equals("tileset")) {
                file.tilesets.add(parseTileset(c));
            } else {
                throw c.error("Unexpected key at tileGeometry level: '" + key + "'");
            }
        }
        c.expect('}');
        return file;
    }

    private Tileset parseTileset(Cursor c) {
        c.expect('{');
        c.expectIdentifier("name");
        c.expect('=');
        String name = c.readRawValue();
        c.skipOptionalComma();

        Tileset tileset = new Tileset(name);
        while (c.peek() != '}') {
            String pendingComment = c.consumePendingComment(); // captured by skipWhitespaceAndComments just before this identifier
            c.expectIdentifier("tile");
            tileset.tiles.add(parseTile(c, pendingComment));
        }
        c.expect('}');
        return tileset;
    }

    private Tile parseTile(Cursor c, String comment) {
        c.expect('{');
        c.expectIdentifier("xy");
        c.expect('=');
        int[] xy = parseIntVec(c.readRawValue(), 2);
        c.skipOptionalComma();

        Tile tile = new Tile(xy[0], xy[1], comment);
        int shapeOrder = 0;
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            if (key.equals("box")) {
                Box b = parseBox(c);
                b.order = shapeOrder++;
                tile.boxes.add(b);
            } else if (key.equals("cylinder")) {
                Cylinder cyl = parseCylinder(c);
                cyl.order = shapeOrder++;
                tile.cylinders.add(cyl);
            } else if (key.equals("polygon")) {
                Polygon poly = parsePolygon(c);
                poly.order = shapeOrder++;
                tile.polygons.add(poly);
            } else if (key.equals("properties")) {
                parseProperties(c, tile.properties);
            } else {
                // Unknown block type - captured generically (every
                // field as a raw string) rather than treated as a parse
                // error, so a shape type this parser hasn't been taught
                // about yet doesn't take down the whole file. The
                // converter logs every one of these to the console.
                tile.unknownShapes.add(new UnknownShape(key, parseGenericFields(c)));
            }
        }
        c.expect('}');
        return tile;
    }

    private Map<String, String> parseGenericFields(Cursor c) {
        Map<String, String> fields = new LinkedHashMap<>();
        c.expect('{');
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            c.expect('=');
            fields.put(key, c.readRawValue());
            c.skipOptionalComma();
        }
        c.expect('}');
        return fields;
    }

    private Cylinder parseCylinder(Cursor c) {
        c.expect('{');
        double[] translate = {0, 0, 0};
        double[] rotate = {0, 0, 0};
        double radius1 = 0, radius2 = 0, height = 0;
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            c.expect('=');
            String raw = c.readRawValue();
            c.skipOptionalComma();
            switch (key) {
                case "translate" -> translate = parseDoubleVec(raw, 3);
                case "rotate" -> rotate = parseDoubleVec(raw, 3);
                case "radius1" -> radius1 = Double.parseDouble(raw);
                case "radius2" -> radius2 = Double.parseDouble(raw);
                case "height" -> height = Double.parseDouble(raw);
                default -> throw c.error("Unexpected key inside cylinder: '" + key + "'");
            }
        }
        c.expect('}');
        return new Cylinder(translate, rotate, radius1, radius2, height);
    }

    private Polygon parsePolygon(Cursor c) {
        c.expect('{');
        double[] translate = {0, 0, 0};
        double[] rotate = {0, 0, 0};
        String plane = "XZ";
        double[][] points = new double[0][];
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            c.expect('=');
            String raw = c.readRawValue();
            c.skipOptionalComma();
            switch (key) {
                case "translate" -> translate = parseDoubleVec(raw, 3);
                case "rotate" -> rotate = parseDoubleVec(raw, 3);
                case "plane" -> plane = raw;
                case "points" -> points = parsePointList(raw);
                default -> throw c.error("Unexpected key inside polygon: '" + key + "'");
            }
        }
        c.expect('}');
        return new Polygon(translate, rotate, plane, points);
    }

    /** "points" is a space-separated list of "AxB" pairs on one line,
     * e.g. "1047x-1047 2063x-1282 2532x-1751" - unlike every other
     * vector field in this format, which is a single "AxBxC"/"AxB"
     * token, so it needs splitting on whitespace first before each
     * piece is split on 'x' the normal way. */
    private static double[][] parsePointList(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        double[][] points = new double[tokens.length][];
        for (int i = 0; i < tokens.length; i++) {
            points[i] = parseDoubleVec(tokens[i], 2);
        }
        return points;
    }

    private Box parseBox(Cursor c) {
        c.expect('{');
        double[] translate = {0, 0, 0};
        double[] rotate = {0, 0, 0};
        double[] min = null, max = null;
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            c.expect('=');
            String raw = c.readRawValue();
            c.skipOptionalComma();
            switch (key) {
                case "translate" -> translate = parseDoubleVec(raw, 3);
                case "rotate" -> rotate = parseDoubleVec(raw, 3);
                case "min" -> min = parseDoubleVec(raw, 3);
                case "max" -> max = parseDoubleVec(raw, 3);
                default -> throw c.error("Unexpected key inside box: '" + key + "'");
            }
        }
        c.expect('}');
        if (min == null || max == null) {
            throw c.error("box block missing min or max");
        }
        return new Box(translate, rotate, min, max);
    }

    private void parseProperties(Cursor c, Map<String, String> out) {
        c.expect('{');
        while (c.peek() != '}') {
            String key = c.readIdentifier();
            c.expect('=');
            out.put(key, c.readRawValue());
            c.skipOptionalComma();
        }
        c.expect('}');
    }

    private static double[] parseDoubleVec(String raw, int expectedComponents) {
        String[] parts = raw.split("x");
        if (parts.length != expectedComponents) {
            throw new IllegalArgumentException("Expected " + expectedComponents + " components in '" + raw + "', got " + parts.length);
        }
        double[] out = new double[expectedComponents];
        for (int i = 0; i < expectedComponents; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }

    private static int[] parseIntVec(String raw, int expectedComponents) {
        String[] parts = raw.split("x");
        if (parts.length != expectedComponents) {
            throw new IllegalArgumentException("Expected " + expectedComponents + " components in '" + raw + "', got " + parts.length);
        }
        int[] out = new int[expectedComponents];
        for (int i = 0; i < expectedComponents; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    // ---- low-level reader, mirroring pbd.format.PbdParser's own style ----

    private static final class Cursor {
        final String text;
        int pos = 0;
        String pendingComment = null; // last /* ... */ seen while skipping whitespace, not yet consumed

        Cursor(String text) {
            this.text = text;
        }

        char peek() {
            skipWsAndComments();
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        String consumePendingComment() {
            skipWsAndComments();
            String c = pendingComment;
            pendingComment = null;
            return c;
        }

        private void skipWsAndComments() {
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isWhitespace(ch)) {
                    pos++;
                } else if (ch == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                    int end = text.indexOf("*/", pos + 2);
                    if (end == -1) throw error("Unterminated /* comment");
                    pendingComment = text.substring(pos + 2, end).trim();
                    pos = end + 2;
                } else if (ch == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                    int nl = text.indexOf('\n', pos);
                    pos = (nl == -1) ? text.length() : nl + 1;
                } else {
                    break;
                }
            }
        }

        void expect(char ch) {
            if (peek() != ch) {
                throw error("Expected '" + ch + "'");
            }
            pos++;
        }

        void expectIdentifier(String expected) {
            String got = readIdentifier();
            if (!got.equals(expected)) {
                throw error("Expected '" + expected + "', got '" + got + "'");
            }
        }

        String readIdentifier() {
            skipWsAndComments();
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) throw error("Expected identifier");
            return text.substring(start, pos);
        }

        /** A bare value up to the next ',' or '}' at this nesting level -
         * everything in this format (numbers, vectors, bare words like
         * "true") is unquoted, so this is simpler than PBD's own
         * readRawValue: no quotes, no nested tuple punctuation to track. */
        String readRawValue() {
            skipWsAndComments();
            int start = pos;
            while (pos < text.length() && text.charAt(pos) != ',' && text.charAt(pos) != '}') {
                pos++;
            }
            return text.substring(start, pos).trim();
        }

        void skipOptionalComma() {
            if (peek() == ',') pos++;
        }

        RuntimeException error(String message) {
            int line = 1;
            for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
            String context = text.substring(Math.max(0, pos - 20), Math.min(text.length(), pos + 20));
            return new IllegalStateException(message + " (line ~" + line + ", near: " + context.replace("\n", "\\n") + ")");
        }
    }
}
