package pbd.voxel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Face-connected components of a set of octree-aligned voxel cubes - the
 * "did that hit cut the object in two?" question the falling-debris
 * feature needs answered after every crater.
 *
 * Two cubes are connected when they share part of a FACE (edge-only or
 * corner-only contact does not count: a piece hanging by one corner edge
 * is not holding anything up). Cubes of different sizes touch constantly
 * here (a big merged interior cube next to the unit voxels a crater left
 * around its rim), so adjacency is resolved through VoxelSolid's
 * "which cube covers this voxel" lookup instead of any per-unit-voxel
 * expansion - a 160x160x160 cube is a handful of entries, not four
 * million voxels, and stays that way here.
 *
 * Every adjacent pair is discovered from the smaller side: querying the
 * voxel just outside one corner of each of a cube's six faces finds the
 * cube covering it; if that cube is the same size or larger it covers the
 * whole neighbouring slab, and if it is smaller then IT will find US when
 * it runs its own queries (our face region lies entirely inside our
 * face). So six lookups per cube find every adjacency.
 */
public final class VoxelConnectivity {

    private VoxelConnectivity() {}

    /** One connected piece. entries share the input's int[] instances. */
    public static final class Component {
        public final List<int[]> entries = new ArrayList<>();
        /** Number of unit voxels (sum of size^3). */
        public long volume;
        /** Bounds of its cubes, [min,max) in grid cells. */
        public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private double sumX, sumY, sumZ; // volume-weighted cube centres

        void add(int[] e) {
            int s = e.length > 3 ? e[3] : 1;
            entries.add(e);
            long v = (long) s * s * s;
            volume += v;
            minX = Math.min(minX, e[0]); minY = Math.min(minY, e[1]); minZ = Math.min(minZ, e[2]);
            maxX = Math.max(maxX, e[0] + s); maxY = Math.max(maxY, e[1] + s); maxZ = Math.max(maxZ, e[2] + s);
            sumX += (e[0] + s / 2.0) * v;
            sumY += (e[1] + s / 2.0) * v;
            sumZ += (e[2] + s / 2.0) * v;
        }

        /** Centre of mass (uniform density), grid units. */
        public double[] centerOfMass() {
            return new double[]{sumX / volume, sumY / volume, sumZ / volume};
        }

        /** Largest value of (point . dir) over every point of this piece (grid units). */
        public double maxProjection(double dx, double dy, double dz) {
            return VoxelConnectivity.maxProjection(entries, dx, dy, dz);
        }
    }

    /**
     * Largest value of (point . dir) over every point of the given cubes -
     * the support of their union along dir, exact for cubes (the centre's
     * projection plus half the cube's extent along dir). With dir = the
     * world "down" expressed in grid space this is how far down the
     * object reaches.
     */
    public static double maxProjection(List<int[]> entries, double dx, double dy, double dz) {
        double reach = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        double best = Double.NEGATIVE_INFINITY;
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            double half = s / 2.0;
            double p = (e[0] + half) * dx + (e[1] + half) * dy + (e[2] + half) * dz + half * reach;
            if (p > best) best = p;
        }
        return best;
    }

    /** Connected components, largest first. An empty input gives an empty list. */
    public static List<Component> split(List<int[]> entries) {
        int n = entries.size();
        List<Component> result = new ArrayList<>();
        if (n == 0) return result;

        VoxelSolid solid = new VoxelSolid(entries);
        int[] parent = new int[n];
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            int[] e = entries.get(i);
            int s = e.length > 3 ? e[3] : 1;
            int x = e[0], y = e[1], z = e[2];
            // The voxel just outside the low corner of each of the six faces.
            int[][] probes = {
                {x - 1, y, z}, {x + s, y, z},
                {x, y - 1, z}, {x, y + s, z},
                {x, y, z - 1}, {x, y, z + s},
            };
            for (int[] p : probes) {
                int j = solid.entryAt(p[0], p[1], p[2]);
                if (j >= 0 && j != i) union(parent, rank, i, j);
            }
        }

        java.util.Map<Integer, Component> byRoot = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            byRoot.computeIfAbsent(root, k -> new Component()).add(entries.get(i));
        }
        result.addAll(byRoot.values());
        result.sort(Comparator.comparingLong((Component c) -> c.volume).reversed());
        return result;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]; // path halving
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int[] rank, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra == rb) return;
        if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;
    }
}
