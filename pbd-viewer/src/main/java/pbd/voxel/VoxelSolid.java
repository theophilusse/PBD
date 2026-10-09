package pbd.voxel;

import java.util.List;

/**
 * Point/ray queries over a set of octree-aligned cubes (the same
 * {@code int[]{x,y,z,size}} "entry" shape VoxelOctree.collectFilledRegions
 * and every destruction step after it produce) - the structure that
 * answers "is this voxel solid?" without ever expanding a large merged
 * cube into its unit voxels.
 *
 * Every entry must be octree-aligned: size a power of two and x/y/z
 * multiples of size. That is true of everything collectFilledRegions
 * returns and is preserved by VoxelCrater.carve (children of an aligned
 * cube are aligned cubes), so no caller has to do anything special; a
 * non-aligned entry is rejected loudly rather than silently mis-indexed.
 *
 * Lookup: a cell at octree level L (size 2^L) is keyed by (L, x>>L,
 * y>>L, z>>L); a unit voxel is covered by at most one entry, found by
 * probing the (few) levels that actually occur in the set, smallest to
 * largest.
 */
public final class VoxelSolid {

    private final LongIntMap cells;
    private final int[] levels;      // levels that occur, ascending
    private final List<int[]> entries;

    /** Cell bounds of everything in the set: [minX,maxX) etc. All zero for an empty set. */
    public final int minX, minY, minZ, maxX, maxY, maxZ;

    public VoxelSolid(List<int[]> entries) {
        this.entries = entries;
        cells = new LongIntMap(Math.max(16, entries.size()));
        boolean[] present = new boolean[16];
        int mnx = Integer.MAX_VALUE, mny = Integer.MAX_VALUE, mnz = Integer.MAX_VALUE;
        int mxx = Integer.MIN_VALUE, mxy = Integer.MIN_VALUE, mxz = Integer.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            int[] e = entries.get(i);
            int size = e.length > 3 ? e[3] : 1;
            int level = levelOf(size);
            if ((e[0] & (size - 1)) != 0 || (e[1] & (size - 1)) != 0 || (e[2] & (size - 1)) != 0) {
                throw new IllegalArgumentException("entry " + i + " is not octree-aligned: ("
                    + e[0] + "," + e[1] + "," + e[2] + ") size " + size);
            }
            cells.put(LongIntMap.cellKey(level, e[0] >> level, e[1] >> level, e[2] >> level), i);
            present[level] = true;
            mnx = Math.min(mnx, e[0]); mny = Math.min(mny, e[1]); mnz = Math.min(mnz, e[2]);
            mxx = Math.max(mxx, e[0] + size); mxy = Math.max(mxy, e[1] + size); mxz = Math.max(mxz, e[2] + size);
        }
        int count = 0;
        for (boolean b : present) if (b) count++;
        levels = new int[count];
        int k = 0;
        for (int l = 0; l < present.length; l++) if (present[l]) levels[k++] = l;
        if (entries.isEmpty()) { mnx = mny = mnz = mxx = mxy = mxz = 0; }
        minX = mnx; minY = mny; minZ = mnz; maxX = mxx; maxY = mxy; maxZ = mxz;
    }

    static int levelOf(int size) {
        if (size < 1 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("entry size must be a power of two, got " + size);
        }
        return Integer.numberOfTrailingZeros(size);
    }

    public int entryCount() {
        return entries.size();
    }

    /** Edge length (in voxels) of the entry at index. */
    public int sizeOf(int entryIndex) {
        int[] e = entries.get(entryIndex);
        return e.length > 3 ? e[3] : 1;
    }

    /**
     * True when every entry is a power-of-two cube sitting on a multiple of
     * its own size - the precondition for building a VoxelSolid (and for the
     * hidden-face culling in VoxelMeshBuilder). Always true for what
     * VoxelOctree.collectFilledRegions, VoxelCrater.carve and the connected-
     * component split produce; checked rather than assumed for callers that
     * hand-build a list.
     */
    public static boolean isOctreeAligned(List<int[]> entries) {
        for (int[] e : entries) {
            int size = e.length > 3 ? e[3] : 1;
            if (size < 1 || Integer.bitCount(size) != 1) return false;
            if ((e[0] & (size - 1)) != 0 || (e[1] & (size - 1)) != 0 || (e[2] & (size - 1)) != 0) return false;
        }
        return true;
    }

    /** Index (into the list given to the constructor) of the entry covering unit voxel (x,y,z), or -1. */
    public int entryAt(int x, int y, int z) {
        if (x < minX || y < minY || z < minZ || x >= maxX || y >= maxY || z >= maxZ) return -1;
        for (int level : levels) {
            int idx = cells.get(LongIntMap.cellKey(level, x >> level, y >> level, z >> level));
            if (idx != LongIntMap.MISSING) return idx;
        }
        return -1;
    }

    public boolean contains(int x, int y, int z) {
        return entryAt(x, y, z) >= 0;
    }

    /**
     * Walks the ray (grid units, direction need not be normalized) voxel by
     * voxel (Amanatides-Woo traversal - every voxel the ray passes through
     * is visited, none skipped, unlike fixed-step sampling which can step
     * clean over a one-voxel-thick wall) and returns the ray parameter t
     * at which it first ENTERS a solid voxel, or -1 if it never does
     * within tMax. If the origin already lies inside a solid voxel, returns
     * 0. t is in the same units as the direction (distance, if normalized).
     */
    public double firstSolidAlongRay(double ox, double oy, double oz, double dx, double dy, double dz, double tMax) {
        if (entries.isEmpty()) return -1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-12) return -1;
        dx /= len; dy /= len; dz /= len;

        // Clip the ray to the set's own bounding box first - a ray that
        // starts far outside it would otherwise walk a long run of
        // guaranteed-empty voxels before reaching anything.
        double tEnter = 0, tExit = tMax;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] >= hi[a]) return -1;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tEnter = Math.max(tEnter, t1);
            tExit = Math.min(tExit, t2);
            if (tEnter > tExit) return -1;
        }

        // Start a hair inside the box so the first voxel index is
        // unambiguous when the entry point sits exactly on a boundary.
        double t = tEnter + 1e-9;
        double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
        int vx = (int) Math.floor(px), vy = (int) Math.floor(py), vz = (int) Math.floor(pz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : t + ((dx > 0 ? vx + 1 - px : px - vx) * tDeltaX);
        double tMaxY = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : t + ((dy > 0 ? vy + 1 - py : py - vy) * tDeltaY);
        double tMaxZ = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : t + ((dz > 0 ? vz + 1 - pz : pz - vz) * tDeltaZ);

        // Hard cap: a ray cannot cross more voxels than the three axis
        // spans of the box add up to - guards against a degenerate
        // direction looping forever.
        int guard = (maxX - minX) + (maxY - minY) + (maxZ - minZ) + 8;
        for (int i = 0; i < guard; i++) {
            if (contains(vx, vy, vz)) return Math.max(0.0, t);
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX; vx += stepX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY; vy += stepY; tMaxY += tDeltaY;
            } else {
                t = tMaxZ; vz += stepZ; tMaxZ += tDeltaZ;
            }
            if (t > tExit) return -1;
        }
        return -1;
    }
}
