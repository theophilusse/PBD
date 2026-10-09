package pbd.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The solid, irregular crater one G-key hit carves out of a set of
 * octree-aligned voxel cubes - the pure-geometry half of what used to be
 * PbdRenderer.computeCraterRemovedSet / applyCraterAdaptive (those two are
 * now thin policy wrappers around this; the radius knob itself stays in
 * PbdRenderer, see CRATER_RADIUS_VOXELS).
 *
 * WHY THIS REPLACED THE OLD SHAPE. The previous crater was 40 random rays
 * from the impact point, each carving only the single voxel under it at
 * every step - i.e. 40 one-voxel-wide tunnels, ~500 voxels in total no
 * matter how large the radius knob was. Measured on the shipped code
 * (radius 20, one hit at the centre): 0.012% of a 0.8 cube, 0.34% of a
 * 0.05x0.25x1.0 plank, 3.3% of a 2 cm x 5 cm x 50 cm slat - and ONE
 * connected component left in every case, i.e. a hit could never cut
 * anything in two, which is what the falling-debris feature needs. The
 * radius only ever lengthened the tunnels.
 *
 * SHAPE. A star-shaped solid: every direction d from the impact point has
 * a reach r(d); a voxel is removed when its centre lies within r of the
 * impact. r(d) is built from the same ingredients the ray crater already
 * used, but turned into a volume instead of a bundle of lines: LOBES
 * random directions, each with its own random reach (0.75..1.25 x the
 * nominal radius), blended smoothly (so the outline is lumpy, not a
 * sphere with a jittered edge - the look the project already rejected
 * twice); the crater digs up to SHOT_ELONGATION deeper along the shot
 * direction than sideways (a hit penetrates); and every voxel's own
 * threshold is perturbed by +-JITTER so the rim is rough like broken
 * wood, not smooth. Everything is derived from the impact's own rounded
 * grid coordinates, so the same impact always produces the same crater
 * (same property the ray version had).
 *
 * COST. The crater's own bounding box (clipped to the cubes' bounding
 * box) is rasterized once into a 3D prefix-sum table, after which "how
 * many voxels of this aligned cube does the crater remove?" is eight
 * lookups: a cube with none is kept whole, a cube fully inside is
 * dropped whole, and only cubes straddling the rim are split - the
 * octree stays as coarse as the geometry allows, and no merged region is
 * ever expanded to unit voxels (the cause of the crater OutOfMemoryError
 * this project already fixed once, see docs/ROADMAP.md).
 */
public final class VoxelCrater {

    private VoxelCrater() {}

    /** Number of random lobes blended into the radial reach function. */
    static final int LOBES = 28;
    /** Cube-map lookup table resolution per face. */
    private static final int FACE_RES = 40;
    /** +- fraction the crater's reach is perturbed by the rim-roughness noise. */
    public static final float JITTER = 0.07f;
    /** Lattice spacing, in voxels, of the rim-roughness noise (see voxelNoise). */
    private static final float NOISE_CELL = 3f;
    /** Extra reach straight along the shot direction (0.5 = 50% deeper). */
    public static final float SHOT_ELONGATION = 0.5f;

    /** The crater's geometry (immutable after construction). */
    public static final class Shape {
        public final float cx, cy, cz;       // impact point, grid units
        public final float radius;           // nominal radius, voxels
        /** Smallest / largest reach over every direction (before per-voxel jitter). */
        public final float minReach, maxReach;
        /** Bounding box of everything the crater can remove: [min,max) in grid cells. */
        public final int boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;

        private final float[] lut = new float[6 * FACE_RES * FACE_RES];
        private final float[] lobeX = new float[LOBES], lobeY = new float[LOBES], lobeZ = new float[LOBES];
        private final float[] lobeLen = new float[LOBES];
        private final float shotX, shotY, shotZ; // unit vector, or all zero for "no preferred direction"
        private final int jitterSeed;

        Shape(float cx, float cy, float cz, float radius, float shotX, float shotY, float shotZ) {
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.radius = radius;

            float shotLen = (float) Math.sqrt(shotX * shotX + shotY * shotY + shotZ * shotZ);
            if (shotLen > 1e-6f) {
                this.shotX = shotX / shotLen; this.shotY = shotY / shotLen; this.shotZ = shotZ / shotLen;
            } else {
                this.shotX = 0; this.shotY = 0; this.shotZ = 0;
            }

            // Deterministic: seeded from the impact's rounded grid
            // coordinates (same encoding the ray crater used).
            long seed = encodeSeed(Math.round(cx), Math.round(cy), Math.round(cz));
            Random rng = new Random(seed);
            this.jitterSeed = (int) (seed ^ (seed >>> 32));
            for (int i = 0; i < LOBES; i++) {
                float theta = (float) (rng.nextFloat() * Math.PI * 2);
                float phi = (float) Math.acos(2 * rng.nextFloat() - 1);
                lobeX[i] = (float) (Math.sin(phi) * Math.cos(theta));
                lobeY[i] = (float) (Math.sin(phi) * Math.sin(theta));
                lobeZ[i] = (float) Math.cos(phi);
                lobeLen[i] = radius * (0.75f + 0.5f * rng.nextFloat());
            }

            // Fill the lookup table and, in the same pass, the extents.
            float mn = Float.MAX_VALUE, mx = 0;
            float bx0 = cx, by0 = cy, bz0 = cz, bx1 = cx, by1 = cy, bz1 = cz;
            float[] dir = new float[3];
            for (int face = 0; face < 6; face++) {
                for (int j = 0; j < FACE_RES; j++) {
                    for (int i = 0; i < FACE_RES; i++) {
                        texelDirection(face, i, j, dir);
                        float r = exactReach(dir[0], dir[1], dir[2]);
                        lut[(face * FACE_RES + j) * FACE_RES + i] = r;
                        mn = Math.min(mn, r);
                        mx = Math.max(mx, r);
                        float rr = r * (1 + JITTER);
                        bx0 = Math.min(bx0, cx + dir[0] * rr); bx1 = Math.max(bx1, cx + dir[0] * rr);
                        by0 = Math.min(by0, cy + dir[1] * rr); by1 = Math.max(by1, cy + dir[1] * rr);
                        bz0 = Math.min(bz0, cz + dir[2] * rr); bz1 = Math.max(bz1, cz + dir[2] * rr);
                    }
                }
            }
            // A few percent of slack: the table is sampled, the true
            // reach between two samples can marginally exceed both.
            this.minReach = mn * 0.97f;
            this.maxReach = mx * 1.03f;
            this.boxMinX = (int) Math.floor(bx0) - 2; this.boxMaxX = (int) Math.ceil(bx1) + 3;
            this.boxMinY = (int) Math.floor(by0) - 2; this.boxMaxY = (int) Math.ceil(by1) + 3;
            this.boxMinZ = (int) Math.floor(bz0) - 2; this.boxMaxZ = (int) Math.ceil(bz1) + 3;
        }

        /** Reach along a UNIT direction, computed from the lobes directly (slow path - table build only). */
        private float exactReach(float dx, float dy, float dz) {
            float num = 0, den = 0;
            for (int i = 0; i < LOBES; i++) {
                float x = 0.5f * (1f + dx * lobeX[i] + dy * lobeY[i] + dz * lobeZ[i]);
                float x2 = x * x, x4 = x2 * x2, x8 = x4 * x4;
                float w = x8 * x4; // x^12: each lobe's influence falls off to half within ~38 degrees
                num += w * lobeLen[i];
                den += w;
            }
            float r = den > 0 ? num / den : radius;
            if (shotX != 0 || shotY != 0 || shotZ != 0) {
                float along = dx * shotX + dy * shotY + dz * shotZ;
                if (along > 0) r *= 1f + SHOT_ELONGATION * along * along;
            }
            return r;
        }

        /** Reach along any (not necessarily unit) direction, via the cube-map table, bilinearly interpolated. */
        public float reachAlong(float dx, float dy, float dz) {
            float ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            int face; float u, v, m;
            if (ax >= ay && ax >= az) {
                m = ax; face = dx > 0 ? 0 : 1; u = dy / m; v = dz / m;
            } else if (ay >= az) {
                m = ay; face = dy > 0 ? 2 : 3; u = dx / m; v = dz / m;
            } else {
                m = az; face = dz > 0 ? 4 : 5; u = dx / m; v = dy / m;
            }
            if (m < 1e-12f) return radius;
            float fu = (u + 1f) * 0.5f * (FACE_RES - 1);
            float fv = (v + 1f) * 0.5f * (FACE_RES - 1);
            int i0 = Math.max(0, Math.min(FACE_RES - 2, (int) fu));
            int j0 = Math.max(0, Math.min(FACE_RES - 2, (int) fv));
            float tu = Math.max(0f, Math.min(1f, fu - i0));
            float tv = Math.max(0f, Math.min(1f, fv - j0));
            int base = face * FACE_RES * FACE_RES;
            float a = lut[base + j0 * FACE_RES + i0];
            float b = lut[base + j0 * FACE_RES + i0 + 1];
            float c = lut[base + (j0 + 1) * FACE_RES + i0];
            float d = lut[base + (j0 + 1) * FACE_RES + i0 + 1];
            return (a * (1 - tu) + b * tu) * (1 - tv) + (c * (1 - tu) + d * tu) * tv;
        }

        /** Direction (unit) of cube-map texel (i,j) on face; same basis reachAlong inverts. */
        private static void texelDirection(int face, int i, int j, float[] out) {
            float u = -1f + 2f * i / (FACE_RES - 1);
            float v = -1f + 2f * j / (FACE_RES - 1);
            float x, y, z;
            switch (face) {
                case 0 -> { x = 1; y = u; z = v; }
                case 1 -> { x = -1; y = u; z = v; }
                case 2 -> { y = 1; x = u; z = v; }
                case 3 -> { y = -1; x = u; z = v; }
                case 4 -> { z = 1; x = u; y = v; }
                default -> { z = -1; x = u; y = v; }
            }
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            out[0] = x / len; out[1] = y / len; out[2] = z / len;
        }

        /** True if the unit voxel (x,y,z) - judged by its centre - is inside the crater. */
        public boolean contains(int x, int y, int z) {
            float dx = x + 0.5f - cx, dy = y + 0.5f - cy, dz = z + 0.5f - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            float lo = minReach * (1 - JITTER);
            if (d2 <= lo * lo) return true;
            float hi = maxReach * (1 + JITTER);
            if (d2 >= hi * hi) return false;
            float dist = (float) Math.sqrt(d2);
            float inv = 1f / dist;
            float r = reachAlong(dx * inv, dy * inv, dz * inv) * (1f + JITTER * voxelNoise(x, y, z));
            return dist <= r;
        }

        /**
         * Rim-roughness noise in [-1,1]: smooth value noise on a NOISE_CELL-voxel lattice,
         * a pure function of the voxel and this crater's seed. It has to be SMOOTH, not
         * independent per voxel: an earlier version drew a fresh random number for every
         * voxel, which turns the rim into salt-and-pepper - kept voxels with every face
         * neighbour removed, i.e. single voxels floating in the air around the crater
         * (measured: 157 disconnected specks around one hit on a plain cube). With the
         * threshold varying by less than a voxel from one voxel to the next, no kept
         * voxel can end up fully surrounded by removed ones.
         */
        private float voxelNoise(int x, int y, int z) {
            float fx = (x + 0.5f) / NOISE_CELL, fy = (y + 0.5f) / NOISE_CELL, fz = (z + 0.5f) / NOISE_CELL;
            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
            float tx = smooth(fx - ix), ty = smooth(fy - iy), tz = smooth(fz - iz);
            float c000 = lattice(ix, iy, iz), c100 = lattice(ix + 1, iy, iz);
            float c010 = lattice(ix, iy + 1, iz), c110 = lattice(ix + 1, iy + 1, iz);
            float c001 = lattice(ix, iy, iz + 1), c101 = lattice(ix + 1, iy, iz + 1);
            float c011 = lattice(ix, iy + 1, iz + 1), c111 = lattice(ix + 1, iy + 1, iz + 1);
            float x00 = c000 + (c100 - c000) * tx, x10 = c010 + (c110 - c010) * tx;
            float x01 = c001 + (c101 - c001) * tx, x11 = c011 + (c111 - c011) * tx;
            float y0 = x00 + (x10 - x00) * ty, y1 = x01 + (x11 - x01) * ty;
            return y0 + (y1 - y0) * tz;
        }

        private static float smooth(float t) {
            return t * t * (3f - 2f * t);
        }

        /** Random value in [-1,1] at an integer lattice point. */
        private float lattice(int x, int y, int z) {
            int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x9e3779b1 ^ jitterSeed;
            h ^= h >>> 15; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;
            return ((h & 0xFFFF) / 32767.5f) - 1f;
        }
    }

    /** Seed encoding kept identical to the ray crater's encodeGridCoord. */
    private static long encodeSeed(int x, int y, int z) {
        long ox = x + 200000L, oy = y + 200000L, oz = z + 200000L;
        return ox * 1_000_000_000_000L + oy * 1_000_000L + oz;
    }

    /**
     * @param impact* impact point in GRID units (voxel coordinates, float)
     * @param radiusVoxels nominal radius, in voxels
     * @param shot* direction the hit travelled, in the same grid frame
     *        (need not be normalized; all zero = no preferred direction)
     */
    public static Shape create(float impactX, float impactY, float impactZ, float radiusVoxels,
                               float shotX, float shotY, float shotZ) {
        if (!(radiusVoxels > 0)) throw new IllegalArgumentException("radius must be > 0, got " + radiusVoxels);
        return new Shape(impactX, impactY, impactZ, radiusVoxels, shotX, shotY, shotZ);
    }

    /** What carve() produces. surviving shares unchanged entries with the input (entries are never mutated). */
    public static final class Result {
        public final List<int[]> surviving;
        public final long removedVoxels;
        Result(List<int[]> surviving, long removedVoxels) {
            this.surviving = surviving;
            this.removedVoxels = removedVoxels;
        }
    }

    /**
     * Applies the crater to octree-aligned cubes and returns what is left.
     * Input entries must be octree-aligned (see VoxelSolid); the output
     * is too. Entries the crater cannot touch are returned as-is (same
     * int[] instances), so a hit near one corner of a big object costs
     * nothing proportional to the rest of it.
     */
    public static Result carve(Shape shape, List<int[]> entries) {
        if (entries.isEmpty()) return new Result(new ArrayList<>(), 0);

        // Bounding box of the cubes, to clip the crater's own box to
        // (a crater is far bigger than a thin plank - no point
        // rasterizing the part of it that is open air).
        int ax0 = Integer.MAX_VALUE, ay0 = Integer.MAX_VALUE, az0 = Integer.MAX_VALUE;
        int ax1 = Integer.MIN_VALUE, ay1 = Integer.MIN_VALUE, az1 = Integer.MIN_VALUE;
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            ax0 = Math.min(ax0, e[0]); ay0 = Math.min(ay0, e[1]); az0 = Math.min(az0, e[2]);
            ax1 = Math.max(ax1, e[0] + s); ay1 = Math.max(ay1, e[1] + s); az1 = Math.max(az1, e[2] + s);
        }
        int x0 = Math.max(shape.boxMinX, ax0), y0 = Math.max(shape.boxMinY, ay0), z0 = Math.max(shape.boxMinZ, az0);
        int x1 = Math.min(shape.boxMaxX, ax1), y1 = Math.min(shape.boxMaxY, ay1), z1 = Math.min(shape.boxMaxZ, az1);
        if (x0 >= x1 || y0 >= y1 || z0 >= z1) {
            return new Result(new ArrayList<>(entries), 0); // crater box does not touch the cubes at all
        }

        Mask mask = new Mask(shape, x0, y0, z0, x1, y1, z1);
        List<int[]> surviving = new ArrayList<>(entries.size() + 64);
        long[] removed = {0};
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            if (e[0] >= x1 || e[1] >= y1 || e[2] >= z1 || e[0] + s <= x0 || e[1] + s <= y0 || e[2] + s <= z0) {
                surviving.add(e); // outside the (clipped) crater box
            } else {
                carveCell(mask, e[0], e[1], e[2], s, surviving, removed);
            }
        }
        return new Result(surviving, removed[0]);
    }

    private static void carveCell(Mask mask, int x, int y, int z, int size, List<int[]> out, long[] removed) {
        long count = mask.count(x, y, z, x + size, y + size, z + size);
        long total = (long) size * size * size;
        if (count == 0) {
            out.add(new int[]{x, y, z, size});
        } else if (count == total) {
            removed[0] += total;
        } else {
            // size == 1 cannot reach here: a unit cell is either 0 or 1 of 1.
            int half = size / 2;
            for (int i = 0; i < 8; i++) {
                carveCell(mask, x + ((i & 1) != 0 ? half : 0), y + ((i & 2) != 0 ? half : 0), z + ((i & 4) != 0 ? half : 0),
                    half, out, removed);
            }
        }
    }

    /** The crater rasterized over a clipped box, as a 3D prefix-sum table for O(1) cube counts. */
    private static final class Mask {
        final int x0, y0, z0, w, h, d;
        final int[] prefix; // (w+1)*(h+1)*(d+1)

        Mask(Shape shape, int x0, int y0, int z0, int x1, int y1, int z1) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0;
            this.w = x1 - x0; this.h = y1 - y0; this.d = z1 - z0;
            long cells = (long) (w + 1) * (h + 1) * (d + 1);
            if (cells > 120_000_000L) {
                // Not reachable at any sane radius (that is a ~490-voxel-wide box) -
                // refuse loudly instead of attempting a multi-hundred-MB allocation.
                throw new IllegalArgumentException("crater box too large: " + w + "x" + h + "x" + d);
            }
            prefix = new int[(int) cells];
            int sx = w + 1, sy = h + 1;
            for (int k = 0; k < d; k++) {
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        int v = shape.contains(x0 + i, y0 + j, z0 + k) ? 1 : 0;
                        int a = at(i, j + 1, k + 1), b = at(i + 1, j, k + 1), c = at(i + 1, j + 1, k);
                        int ab = at(i, j, k + 1), ac = at(i, j + 1, k), bc = at(i + 1, j, k);
                        int abc = at(i, j, k);
                        prefix[((k + 1) * sy + (j + 1)) * sx + (i + 1)] = v + a + b + c - ab - ac - bc + abc;
                    }
                }
            }
        }

        private int at(int i, int j, int k) {
            return prefix[(k * (h + 1) + j) * (w + 1) + i];
        }

        /** Voxels removed inside [ax,bx) x [ay,by) x [az,bz) (grid coordinates; anything outside the box counts as 0). */
        long count(int ax, int ay, int az, int bx, int by, int bz) {
            int i0 = Math.max(ax, x0) - x0, i1 = Math.min(bx, x0 + w) - x0;
            int j0 = Math.max(ay, y0) - y0, j1 = Math.min(by, y0 + h) - y0;
            int k0 = Math.max(az, z0) - z0, k1 = Math.min(bz, z0 + d) - z0;
            if (i0 >= i1 || j0 >= j1 || k0 >= k1) return 0;
            return (long) at(i1, j1, k1) - at(i0, j1, k1) - at(i1, j0, k1) - at(i1, j1, k0)
                + at(i0, j0, k1) + at(i0, j1, k0) + at(i1, j0, k0) - at(i0, j0, k0);
        }
    }
}
