package pbd.voxel;

import pbd.format.PbdInstance;
import pbd.format.PrimitiveParts;

/**
 * The inside of a closed primitive in its canonical space (the unit shape centred on the origin): the point test the volume
 * lattice of {@link ForwardVoxelizer} asks, and the same shapes {@link PrimitiveVoxelizer} classifies by region. A rounded cube
 * ({@code smooth}) is the Minkowski sum of a box and an ellipsoid with the per-axis radii, exactly what pbd.tese's
 * evalCubeFaceSmooth draws: a point is inside when its distance to the inner box, measured in units of the radii, is at most 1.
 */
final class ShapeVolume {
    private enum Kind { CUBE, SPHERE, CYLINDER, CONE, TORUS, NONE }

    private final Kind kind;
    private final double sx, sy, sz;      // roundness per axis (cube)
    private final boolean rounded;

    private ShapeVolume(Kind kind, double sx, double sy, double sz) {
        this.kind = kind;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.rounded = sx > 0.001 || sy > 0.001 || sz > 0.001;
    }

    static ShapeVolume of(PbdInstance inst) {
        return switch (inst.type) {
            case "cube" -> {
                float[] s = PrimitiveParts.cubeSmoothness(inst);
                boolean any = s[0] > 0.001f || s[1] > 0.001f || s[2] > 0.001f;
                yield new ShapeVolume(Kind.CUBE, any ? s[0] : 0, any ? s[1] : 0, any ? s[2] : 0);
            }
            case "sphere" -> new ShapeVolume(Kind.SPHERE, 0, 0, 0);
            case "cylinder" -> new ShapeVolume(Kind.CYLINDER, 0, 0, 0);
            case "cone" -> new ShapeVolume(Kind.CONE, 0, 0, 0);
            case "torus" -> new ShapeVolume(Kind.TORUS, 0, 0, 0);
            default -> new ShapeVolume(Kind.NONE, 0, 0, 0);
        };
    }

    /** Whether this is a cube with rounded edges. */
    boolean isRounded() {
        return kind == Kind.CUBE && rounded;
    }

    /** Whether the point (x,y,z), canonical space, is inside the closed shape. */
    boolean inside(double x, double y, double z) {
        switch (kind) {
            case CUBE -> {
                if (!rounded) return Math.abs(x) <= 0.5 && Math.abs(y) <= 0.5 && Math.abs(z) <= 0.5;
                if (Math.abs(x) > 0.5 || Math.abs(y) > 0.5 || Math.abs(z) > 0.5) return false;
                double dx = roundOffset(Math.abs(x), sx), dy = roundOffset(Math.abs(y), sy), dz = roundOffset(Math.abs(z), sz);
                return dx * dx + dy * dy + dz * dz <= 1.0;
            }
            case SPHERE -> { return x * x + y * y + z * z <= 0.25; }
            case CYLINDER -> { return Math.abs(y) <= 0.5 && x * x + z * z <= 0.25; }
            case CONE -> {
                if (Math.abs(y) > 0.5) return false;
                double r = 0.5 * (0.5 - y);
                return x * x + z * z <= r * r;
            }
            case TORUS -> {
                double d = Math.sqrt(x * x + z * z) - 0.35;
                return d * d + y * y <= 0.15 * 0.15;
            }
            default -> { return false; }
        }
    }

    /** How far (in units of the radius) |c| is past the inner box of an axis with roundness s; 0 inside it; an axis with no roundness has no room past 0.5. */
    static double roundOffset(double absC, double s) {
        double inner = 0.5 - s;
        if (absC <= inner) return 0.0;
        return s > 1e-6 ? (absC - inner) / s : Double.POSITIVE_INFINITY;
    }

    /**
     * The same test over a region (a box of canonical space) for the adaptive rasterizer: +1 when the whole box is inside, -1 when
     * no point of it is, 0 when it cannot tell. For the rounded cube the distance to the inner box is convex, so its minimum over
     * the box is reached at the closest point and its maximum at a corner, axis by axis.
     */
    int classify(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (kind != Kind.CUBE) throw new IllegalStateException("classify is for the cube only");
        if (maxX < -0.5 || minX > 0.5 || maxY < -0.5 || minY > 0.5 || maxZ < -0.5 || minZ > 0.5) return -1;
        boolean within = minX >= -0.5 && maxX <= 0.5 && minY >= -0.5 && maxY <= 0.5 && minZ >= -0.5 && maxZ <= 0.5;
        if (!rounded) return within ? 1 : 0;
        double lowSq = 0, highSq = 0;
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ}, s = {sx, sy, sz};
        for (int i = 0; i < 3; i++) {
            double closest = lo[i] <= 0 && hi[i] >= 0 ? 0.0 : Math.min(Math.abs(lo[i]), Math.abs(hi[i]));
            double farthest = Math.max(Math.abs(lo[i]), Math.abs(hi[i]));
            double a = roundOffset(Math.min(closest, 0.5), s[i]);
            double b = roundOffset(Math.min(farthest, 0.5), s[i]);
            if (closest > 0.5) a = Double.POSITIVE_INFINITY;
            if (farthest > 0.5) b = Double.POSITIVE_INFINITY;
            lowSq += a * a;
            highSq += b * b;
        }
        if (lowSq > 1.0) return -1;
        if (within && highSq <= 1.0) return 1;
        return 0;
    }
}
