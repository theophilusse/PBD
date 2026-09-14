package pbd.pz;

/**
 * Writes a TileGeometryFile back out as tileGeometry.txt text - the
 * inverse of TileGeometryParser. Exists so a PBD-derived tile can be
 * added to an EXISTING file: parse it, insert the new tile into the
 * right tileset, serialize the whole thing back out - rather than only
 * ever producing a brand new, standalone file.
 */
public final class TileGeometrySerializer {

    public String serialize(TileGeometryParser.TileGeometryFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("tileGeometry\n{\n");
        sb.append("    VERSION = ").append(file.version).append(",\n\n");
        for (TileGeometryParser.Tileset tileset : file.tilesets) {
            writeTileset(sb, tileset);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private void writeTileset(StringBuilder sb, TileGeometryParser.Tileset tileset) {
        sb.append("    tileset\n    {\n");
        sb.append("        name = ").append(tileset.name).append(",\n\n");
        for (TileGeometryParser.Tile tile : tileset.tiles) {
            writeTile(sb, tile);
        }
        sb.append("    }\n\n");
    }

    private void writeTile(StringBuilder sb, TileGeometryParser.Tile tile) {
        if (tile.comment != null) {
            sb.append("        /* ").append(tile.comment).append(" */\n");
        }
        sb.append("        tile\n        {\n");
        sb.append("            xy = ").append(tile.xyX).append('x').append(tile.xyY).append(",\n\n");
        for (TileGeometryParser.Box b : tile.boxes) writeBox(sb, b);
        for (TileGeometryParser.Cylinder c : tile.cylinders) writeCylinder(sb, c);
        for (TileGeometryParser.Polygon p : tile.polygons) writePolygon(sb, p);
        for (TileGeometryParser.UnknownShape u : tile.unknownShapes) writeUnknown(sb, u);
        if (!tile.properties.isEmpty()) {
            sb.append("            properties\n            {\n");
            for (var e : tile.properties.entrySet()) {
                sb.append("                ").append(e.getKey()).append(" = ").append(e.getValue()).append(",\n");
            }
            sb.append("            }\n");
        }
        sb.append("        }\n\n");
    }

    private void writeBox(StringBuilder sb, TileGeometryParser.Box b) {
        sb.append("            box\n            {\n");
        sb.append("                translate = ").append(vec3(b.translate)).append(",\n");
        sb.append("                rotate = ").append(vec3(b.rotate)).append(",\n");
        sb.append("                min = ").append(vec3(b.min)).append(",\n");
        sb.append("                max = ").append(vec3(b.max)).append(",\n");
        sb.append("            }\n\n");
    }

    private void writeCylinder(StringBuilder sb, TileGeometryParser.Cylinder c) {
        sb.append("            cylinder\n            {\n");
        sb.append("                translate = ").append(vec3(c.translate)).append(",\n");
        sb.append("                rotate = ").append(vec3(c.rotate)).append(",\n");
        sb.append("                radius1 = ").append(num(c.radius1)).append(",\n");
        sb.append("                radius2 = ").append(num(c.radius2)).append(",\n");
        sb.append("                height = ").append(num(c.height)).append(",\n");
        sb.append("            }\n\n");
    }

    private void writePolygon(StringBuilder sb, TileGeometryParser.Polygon p) {
        sb.append("            polygon\n            {\n");
        sb.append("                translate = ").append(vec3(p.translate)).append(",\n");
        sb.append("                rotate = ").append(vec3(p.rotate)).append(",\n");
        sb.append("                plane = ").append(p.plane).append(",\n");
        sb.append("                points = ");
        for (double[] pt : p.points) {
            sb.append(num(pt[0])).append('x').append(num(pt[1])).append(' ');
        }
        sb.append(",\n            }\n\n");
    }

    private void writeUnknown(StringBuilder sb, TileGeometryParser.UnknownShape u) {
        sb.append("            ").append(u.blockName).append("\n            {\n");
        for (var e : u.fields.entrySet()) {
            sb.append("                ").append(e.getKey()).append(" = ").append(e.getValue()).append(",\n");
        }
        sb.append("            }\n\n");
    }

    private String vec3(double[] v) {
        return num(v[0]) + "x" + num(v[1]) + "x" + num(v[2]);
    }

    /** Whole numbers without a trailing ".0" (matching every real
     * example seen, which are all integers) - kept as a plain double
     * internally throughout this package rather than switching to long,
     * since a few fields (radius, height) could plausibly need
     * fractional values even though none have been observed yet. */
    private String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
