package pbd.pz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts a parsed TileGeometryFile into one .pbd file per non-empty
 * tile. Geometry only for now (no material/texture assignment - that
 * comes once the .gltb sprite/texture side of this pipeline exists);
 * every box becomes a "default"-material cube.
 *
 * Unit conversion: PZ's own box coordinates run roughly -5000..5000 for
 * a tile's horizontal footprint (X/Z) - consistent with 10000 raw units
 * spanning one full tile width, centered on it. This converter uses
 * 10000 raw units = 1 PBD world unit, so adjacent tiles end up exactly
 * 1.0 apart if placed on a grid later, and a typical piece of furniture
 * comes out in the same rough scale (~1 unit) as this project's other
 * PBD assets. Y (height) isn't re-derived from anything tile-grid-
 * specific, just carried through the same /10000 scale - this project's
 * own PBD content already treats Y as up, and the sample data's Y
 * ranges (always >= 0, growing with object height) are consistent with
 * that, but this hasn't been checked against Project Zomboid's own
 * axis documentation and is worth confirming once more of the real file
 * is available.
 */
public final class TileGeometryConverter {

    private static final double PZ_UNITS_PER_PBD_UNIT = 10000.0;

    public static final class Stats {
        public int tilesConverted = 0;
        public int tilesSkippedEmpty = 0;
        public int tilesSkippedAllDegenerate = 0;
        public int boxesConverted = 0;
        public int boxesSkippedDegenerate = 0;
        public int cylindersConverted = 0;
        public int polygonsConvertedExact = 0;
        public int polygonsConvertedApproximate = 0;
        public int unknownShapesSeen = 0;
        public long outputBytes = 0;
    }

    public Stats convertToDirectory(TileGeometryParser.TileGeometryFile file, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Stats stats = new Stats();

        for (TileGeometryParser.Tileset tileset : file.tilesets) {
            for (TileGeometryParser.Tile tile : tileset.tiles) {
                if (tile.isEmpty()) {
                    stats.tilesSkippedEmpty++;
                    continue;
                }

                String text = convertTile(tileset, tile, stats);
                if (text == null) {
                    // every box in this tile was degenerate - nothing left to write
                    stats.tilesSkippedAllDegenerate++;
                    continue;
                }

                String filename = outputFilename(tileset, tile);
                Path outPath = outputDir.resolve(filename);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                Files.write(outPath, bytes);
                stats.outputBytes += bytes.length;
                stats.tilesConverted++;
            }
        }
        return stats;
    }

    /** Returns null if there was nothing left to write (every box
     * degenerate, and no cylinders/polygons/unknowns at all). */
    private String convertTile(TileGeometryParser.Tileset tileset, TileGeometryParser.Tile tile, Stats stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("pbd_version 1\n");
        sb.append("# Converted from tileGeometry.txt\n");
        sb.append("# tileset=").append(tileset.name).append(" xy=").append(tile.xyX).append('x').append(tile.xyY).append('\n');
        if (tile.comment != null) {
            sb.append("# asset=").append(tile.comment).append('\n');
        }
        for (var entry : tile.properties.entrySet()) {
            sb.append("# property ").append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        sb.append('\n');

        int written = 0;
        String assetLabel = tile.comment != null ? tile.comment : (tileset.name + "_" + tile.xyX + "x" + tile.xyY);

        // One combined, order-sorted pass instead of "all boxes, then
        // all cylinders, then all polygons" - preserves the original
        // file's interleaving (a box/polygon/box/box tile keeps that
        // exact sequence in the output) rather than silently
        // reshuffling shapes into groups by type.
        record OrderedShape(int order, Object shape) {}
        List<OrderedShape> ordered = new ArrayList<>();
        for (TileGeometryParser.Box b : tile.boxes) ordered.add(new OrderedShape(b.order, b));
        for (TileGeometryParser.Cylinder c : tile.cylinders) ordered.add(new OrderedShape(c.order, c));
        for (TileGeometryParser.Polygon p : tile.polygons) ordered.add(new OrderedShape(p.order, p));
        ordered.sort((a, b) -> Integer.compare(a.order, b.order));

        int shapeIndex = 0;
        for (OrderedShape os : ordered) {
            shapeIndex++;
            if (os.shape instanceof TileGeometryParser.Box box) {
                if (box.isDegenerate()) {
                    stats.boxesSkippedDegenerate++;
                    continue;
                }
                appendBoxAsCube(sb, box, shapeIndex);
                stats.boxesConverted++;
                written++;
            } else if (os.shape instanceof TileGeometryParser.Cylinder cyl) {
                appendCylinder(sb, cyl, shapeIndex);
                stats.cylindersConverted++;
                written++;
            } else if (os.shape instanceof TileGeometryParser.Polygon poly) {
                boolean exact = appendPolygonAsPlane(sb, poly, shapeIndex, assetLabel);
                if (exact) stats.polygonsConvertedExact++; else stats.polygonsConvertedApproximate++;
                written++;
            }
        }

        for (TileGeometryParser.UnknownShape unknown : tile.unknownShapes) {
            stats.unknownShapesSeen++;
            System.out.println("[TileGeometry] UNKNOWN shape type '" + unknown.blockName + "' in tile '" + assetLabel
                + "' (xy=" + tile.xyX + "x" + tile.xyY + ") - fields: " + unknown.fields
                + " - not converted, add support for this type once its meaning is known");
        }

        return written > 0 ? sb.toString() : null;
    }

    private static final double DEGREES_PER_RAW_UNIT = 1.0 / PZ_UNITS_PER_PBD_UNIT;

    private void appendBoxAsCube(StringBuilder sb, TileGeometryParser.Box box, int index) {
        double cx = (box.min[0] + box.max[0]) / 2.0 + box.translate[0];
        double cy = (box.min[1] + box.max[1]) / 2.0 + box.translate[1];
        double cz = (box.min[2] + box.max[2]) / 2.0 + box.translate[2];
        double sx = (box.max[0] - box.min[0]) / PZ_UNITS_PER_PBD_UNIT;
        double sy = (box.max[1] - box.min[1]) / PZ_UNITS_PER_PBD_UNIT;
        double sz = (box.max[2] - box.min[2]) / PZ_UNITS_PER_PBD_UNIT;
        double px = cx / PZ_UNITS_PER_PBD_UNIT;
        double py = cy / PZ_UNITS_PER_PBD_UNIT;
        double pz = cz / PZ_UNITS_PER_PBD_UNIT;

        sb.append("cube shape").append(index).append(" {\n");
        sb.append(String.format(Locale.ROOT, "  pos   = (%.6g, %.6g, %.6g)%n", px, py, pz));
        appendRotIfNonZero(sb, box.rotate);
        sb.append(String.format(Locale.ROOT, "  scale = (%.6g, %.6g, %.6g)%n", sx, sy, sz));
        sb.append("  mat   = default\n");
        sb.append("}\n\n");
    }

    /**
     * PZ's cylinder is assumed Y-native (height along local Y before its
     * own rotate is applied) exactly like PBD's own cylinder - inferred,
     * not confirmed from documentation, from one real example: a
     * cooking-appliance knob with rotate=(270,0,0) which, applied to a
     * Y-axis shape, points it along -Z (horizontal, sticking toward the
     * viewer) - the physically sensible orientation for a stove knob.
     * radius1/radius2 equal in every example seen so far (a true
     * cylinder, not a cone/frustum) - if they ever differ, this falls
     * back to PBD's cone using the larger of the two and logs a warning,
     * since PBD has no true two-non-zero-radius frustum primitive.
     */
    private void appendCylinder(StringBuilder sb, TileGeometryParser.Cylinder cyl, int index) {
        double px = cyl.translate[0] / PZ_UNITS_PER_PBD_UNIT;
        double py = cyl.translate[1] / PZ_UNITS_PER_PBD_UNIT;
        double pz = cyl.translate[2] / PZ_UNITS_PER_PBD_UNIT;
        double height = cyl.height / PZ_UNITS_PER_PBD_UNIT;

        boolean uniform = Math.abs(cyl.radius1 - cyl.radius2) < 1.0; // within 1 raw unit (0.0001 PBD units) - float noise, not a real difference
        String type = uniform ? "cylinder" : "cone";
        if (!uniform) {
            System.out.println("[TileGeometry] Cylinder with radius1=" + cyl.radius1 + " radius2=" + cyl.radius2
                + " (shape" + index + ") - using PBD cone with the larger radius as an approximation; "
                + "PBD has no true two-non-zero-radius frustum primitive");
        }
        double radius = Math.max(cyl.radius1, cyl.radius2);
        double diameter = 2.0 * radius / PZ_UNITS_PER_PBD_UNIT;

        sb.append(type).append(" shape").append(index).append(" {\n");
        sb.append(String.format(Locale.ROOT, "  pos   = (%.6g, %.6g, %.6g)%n", px, py, pz));
        appendRotIfNonZero(sb, cyl.rotate);
        sb.append(String.format(Locale.ROOT, "  scale = (%.6g, %.6g, %.6g)%n", diameter, height, diameter));
        sb.append("  mat   = default\n");
        sb.append("}\n\n");
    }

    /**
     * PBD's plane primitive is already natively XZ (horizontal, Y
     * constant) - matching the "plane" field's own "XZ" value directly
     * in every example seen, so this uses that mapping as-is and does
     * NOT additionally apply the polygon's own rotate value on top of
     * it. That's a deliberate choice, not an oversight: reasoning out
     * what a rotate should mean for an already-oriented plane risks the
     * same kind of mistake as this project's earlier rotation
     * composition bugs (see PbdParser's "rot" field history) - trusting
     * the explicit plane=XZ statement of intent is safer than
     * re-deriving orientation from a rotation value whose relationship
     * to a face already declared flat isn't independently confirmed.
     * Worth checking visually against a real render once possible.
     *
     * Returns true if the 4 points were an exact axis-aligned rectangle
     * (the common case for floor/counter footprints), false if this
     * fell back to a bounding-box approximation of a more complex shape
     * (logged to the console either way that fires).
     */
    private boolean appendPolygonAsPlane(StringBuilder sb, TileGeometryParser.Polygon poly, int index, String assetLabel) {
        if (!poly.plane.equalsIgnoreCase("XZ")) {
            System.out.println("[TileGeometry] Polygon with plane='" + poly.plane + "' in '" + assetLabel
                + "' (shape" + index + ") - only XZ has been seen and mapped so far; treating it as XZ anyway, verify visually");
        }

        double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
        double minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        for (double[] p : poly.points) {
            minA = Math.min(minA, p[0]); maxA = Math.max(maxA, p[0]);
            minB = Math.min(minB, p[1]); maxB = Math.max(maxB, p[1]);
        }

        boolean isExactRectangle = poly.points.length == 4 && isAxisAlignedRectangle(poly.points, minA, maxA, minB, maxB);

        if (!isExactRectangle) {
            System.out.println("[TileGeometry] Polygon in '" + assetLabel + "' (shape" + index + ") has "
                + poly.points.length + " point(s), not a simple axis-aligned rectangle - approximating with its "
                + "bounding-box footprint as a single plane (see appendPolygonAsPlane's comment for why a "
                + "multi-plane silhouette isn't attempted yet)");
        }

        double sizeA = (maxA - minA) / PZ_UNITS_PER_PBD_UNIT;
        double sizeB = (maxB - minB) / PZ_UNITS_PER_PBD_UNIT;
        double centerA = (minA + maxA) / 2.0 + poly.translate[0];
        double centerB = (minB + maxB) / 2.0 + poly.translate[2];
        double py = poly.translate[1] / PZ_UNITS_PER_PBD_UNIT;

        sb.append("plane shape").append(index).append(" {\n");
        sb.append(String.format(Locale.ROOT, "  pos   = (%.6g, %.6g, %.6g)%n", centerA / PZ_UNITS_PER_PBD_UNIT, py, centerB / PZ_UNITS_PER_PBD_UNIT));
        sb.append(String.format(Locale.ROOT, "  scale = (%.6g, 1, %.6g)%n", sizeA, sizeB));
        sb.append("  mat   = default\n");
        if (!isExactRectangle) {
            sb.append("  # APPROXIMATION: bounding box of a ").append(poly.points.length)
                .append("-point polygon, not its exact silhouette - see console output at conversion time\n");
        }
        sb.append("}\n\n");
        return isExactRectangle;
    }

    private boolean isAxisAlignedRectangle(double[][] points, double minA, double maxA, double minB, double maxB) {
        // A true axis-aligned rectangle's 4 points, in some rotation of
        // corner order, are exactly {min,min} {max,min} {max,max}
        // {min,max} - check each point lands on one of those 4 corners
        // and all 4 corners get hit exactly once, rather than assuming
        // point order.
        boolean[] hit = new boolean[4];
        double[][] corners = {{minA, minB}, {maxA, minB}, {maxA, maxB}, {minA, maxB}};
        for (double[] p : points) {
            boolean matched = false;
            for (int i = 0; i < 4; i++) {
                if (!hit[i] && Math.abs(p[0] - corners[i][0]) < 1.0 && Math.abs(p[1] - corners[i][1]) < 1.0) {
                    hit[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return hit[0] && hit[1] && hit[2] && hit[3];
    }

    private void appendRotIfNonZero(StringBuilder sb, double[] rotateRaw) {
        double rx = rotateRaw[0] * DEGREES_PER_RAW_UNIT;
        double ry = rotateRaw[1] * DEGREES_PER_RAW_UNIT;
        double rz = rotateRaw[2] * DEGREES_PER_RAW_UNIT;
        if (Math.abs(rx) > 0.01 || Math.abs(ry) > 0.01 || Math.abs(rz) > 0.01) {
            sb.append(String.format(Locale.ROOT, "  rot   = (%.4g, %.4g, %.4g)%n", rx, ry, rz));
        }
    }

    private String outputFilename(TileGeometryParser.Tileset tileset, TileGeometryParser.Tile tile) {
        String base = tile.comment != null ? tile.comment : (tileset.name + "_" + tile.xyX + "_" + tile.xyY);
        return sanitizeFilename(base) + ".pbd";
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
