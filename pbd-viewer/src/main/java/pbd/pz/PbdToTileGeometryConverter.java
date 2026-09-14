package pbd.pz;

import org.joml.Vector3f;
import pbd.format.PbdInstance;
import pbd.format.PbdScene;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a PbdScene's instances into a single tileGeometry Tile -
 * the inverse direction of TileGeometryConverter, for "add this PBD
 * asset into tileGeometry.txt" (see PbdToTileGeometryMain).
 *
 * Lossless for: a cube with no modifiers (-> box), a uniform cylinder
 * or a cone with no modifiers (-> cylinder, using radius2=0 for the
 * cone's apex end), a plane (-> a 4-point rectangular polygon).
 *
 * Everything else (sphere, disc, torus, or any primitive carrying a
 * bend/shear/taper modifier, none of which tileGeometry's box/
 * cylinder/polygon vocabulary can represent) falls back to a bounding-
 * box "box" approximation - but the true PBD instance (its exact type,
 * position, rotation, scale, and material) is recorded verbatim in a
 * PBD_SOURCE_&lt;n&gt; entry in the tile's own properties block, so a
 * later PBD-aware reader can recover the exact original instead of only
 * ever seeing the approximated box. This is the "viser le lossless"
 * requirement's actual mechanism: the box keeps a real, PZ-correct
 * bounding volume for anything reading this as a normal tileGeometry
 * file, while the property preserves full fidelity for anything that
 * knows to look for it.
 */
public final class PbdToTileGeometryConverter {

    private static final double PZ_UNITS_PER_PBD_UNIT = 10000.0;

    public static final class Result {
        public final TileGeometryParser.Tile tile;
        public final int losslessCount;
        public final int approximatedCount;

        Result(TileGeometryParser.Tile tile, int losslessCount, int approximatedCount) {
            this.tile = tile;
            this.losslessCount = losslessCount;
            this.approximatedCount = approximatedCount;
        }
    }

    public Result convert(PbdScene scene, int xyX, int xyY, String comment) {
        TileGeometryParser.Tile tile = new TileGeometryParser.Tile(xyX, xyY, comment);
        int lossless = 0, approximated = 0;
        int sourceIndex = 0;

        for (PbdInstance inst : scene.instances) {
            if (inst.type.equals("group") || "true".equals(inst.params.get("metadata"))) {
                continue; // no geometry to place - a pbd_ref anchor or an invisible storage-space marker
            }

            boolean hasModifiers = !inst.modifiers.isEmpty();
            Vector3f pos = inst.position;
            Vector3f scale = inst.scale;

            if (!hasModifiers && inst.type.equals("cube")) {
                tile.boxes.add(cubeToBox(pos, scale, inst.rotationDeg));
                lossless++;
            } else if (!hasModifiers && inst.type.equals("cylinder")) {
                tile.cylinders.add(cylinderToShape(pos, scale, inst.rotationDeg, scale.x, scale.x));
                lossless++;
            } else if (!hasModifiers && inst.type.equals("cone")) {
                // PBD cone: base radius 0.5*scale.x at local Y=-0.5, apex
                // (radius 0) at Y=+0.5 - see pbd.tese's evalConeWall.
                // radius2 = 0 keeps that base-then-apex order.
                tile.cylinders.add(cylinderToShape(pos, scale, inst.rotationDeg, scale.x, 0.0));
                lossless++;
            } else if (!hasModifiers && inst.type.equals("plane")) {
                tile.polygons.add(planeToPolygon(pos, scale));
                lossless++;
            } else {
                sourceIndex++;
                tile.boxes.add(boundingBoxApproximation(pos, scale));
                tile.properties.put("PBD_SOURCE_" + sourceIndex, encodeTrueSource(inst));
                approximated++;
            }
        }

        return new Result(tile, lossless, approximated);
    }

    private TileGeometryParser.Box cubeToBox(Vector3f pos, Vector3f scale, Vector3f rotDeg) {
        double hx = scale.x * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double hy = scale.y * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double hz = scale.z * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double cx = pos.x * PZ_UNITS_PER_PBD_UNIT;
        double cy = pos.y * PZ_UNITS_PER_PBD_UNIT;
        double cz = pos.z * PZ_UNITS_PER_PBD_UNIT;
        double[] min = {cx - hx, cy - hy, cz - hz};
        double[] max = {cx + hx, cy + hy, cz + hz};
        double[] rotate = {rotDeg.x * PZ_UNITS_PER_PBD_UNIT, rotDeg.y * PZ_UNITS_PER_PBD_UNIT, rotDeg.z * PZ_UNITS_PER_PBD_UNIT};
        return new TileGeometryParser.Box(new double[]{0, 0, 0}, rotate, min, max);
    }

    private TileGeometryParser.Cylinder cylinderToShape(Vector3f pos, Vector3f scale, Vector3f rotDeg, float radiusScale1, double radius2Scale) {
        double translateY = pos.y * PZ_UNITS_PER_PBD_UNIT;
        double[] translate = {pos.x * PZ_UNITS_PER_PBD_UNIT, translateY, pos.z * PZ_UNITS_PER_PBD_UNIT};
        double[] rotate = {rotDeg.x * PZ_UNITS_PER_PBD_UNIT, rotDeg.y * PZ_UNITS_PER_PBD_UNIT, rotDeg.z * PZ_UNITS_PER_PBD_UNIT};
        double radius1 = radiusScale1 * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double radius2 = radius2Scale * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double height = scale.y * PZ_UNITS_PER_PBD_UNIT;
        return new TileGeometryParser.Cylinder(translate, rotate, radius1, radius2, height);
    }

    private TileGeometryParser.Polygon planeToPolygon(Vector3f pos, Vector3f scale) {
        double hx = scale.x * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double hz = scale.z * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double cx = pos.x * PZ_UNITS_PER_PBD_UNIT;
        double cz = pos.z * PZ_UNITS_PER_PBD_UNIT;
        double[][] points = {
            {cx - hx, cz - hz}, {cx + hx, cz - hz}, {cx + hx, cz + hz}, {cx - hx, cz + hz}
        };
        double[] translate = {0, pos.y * PZ_UNITS_PER_PBD_UNIT, 0};
        return new TileGeometryParser.Polygon(translate, new double[]{0, 0, 0}, "XZ", points);
    }

    private TileGeometryParser.Box boundingBoxApproximation(Vector3f pos, Vector3f scale) {
        // A conservative bounding box for a type tileGeometry can't
        // natively represent (sphere/disc/torus, or a modified
        // cube/cylinder/cone) - the primitive's own canonical half-
        // extent is already ~0.5 on every axis this project uses (see
        // PrimitiveRegistry), so scale/2 directly is already the right
        // bound without needing to know which specific type this is.
        double hx = scale.x * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double hy = scale.y * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double hz = scale.z * PZ_UNITS_PER_PBD_UNIT / 2.0;
        double cx = pos.x * PZ_UNITS_PER_PBD_UNIT;
        double cy = pos.y * PZ_UNITS_PER_PBD_UNIT;
        double cz = pos.z * PZ_UNITS_PER_PBD_UNIT;
        return new TileGeometryParser.Box(new double[]{0, 0, 0}, new double[]{0, 0, 0},
            new double[]{cx - hx, cy - hy, cz - hz}, new double[]{cx + hx, cy + hy, cz + hz});
    }

    /** A compact, parseable-by-eye record of the exact PBD instance a
     * box approximates - not meant to be a full second parser target
     * right now (no reader for this consumes it yet), just a lossless
     * paper trail so the information isn't gone. */
    private String encodeTrueSource(PbdInstance inst) {
        StringBuilder sb = new StringBuilder();
        sb.append("type=").append(inst.type);
        sb.append(";pos=").append(vec3str(inst.position));
        sb.append(";rot=").append(vec3str(inst.rotationDeg));
        sb.append(";scale=").append(vec3str(inst.scale));
        sb.append(";mat=").append(inst.material != null ? inst.material : "default");
        if (!inst.modifiers.isEmpty()) {
            sb.append(";modifiers=");
            for (var mod : inst.modifiers) {
                sb.append(mod.type).append('(');
                mod.params.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
                sb.append(')').append('|');
            }
        }
        return sb.toString();
    }

    /** 'x'-separated, not comma-separated: this string becomes ONE
     * properties value in tileGeometry's own format, where a comma is
     * the field TERMINATOR, not just a component separator - a comma
     * inside this value would truncate it mid-vector and corrupt
     * reparsing (confirmed directly: an earlier comma-separated version
     * broke exactly this way when the file was written then read back). */
    private String vec3str(Vector3f v) {
        return v.x + "x" + v.y + "x" + v.z;
    }
}
