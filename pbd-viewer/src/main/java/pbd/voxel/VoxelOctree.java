package pbd.voxel;

import java.util.ArrayList;
import java.util.List;

/**
 * Sparse occupancy octree over an integer voxel grid, sized
 * 2^maxDepth on each axis (coordinates 0 .. 2^maxDepth - 1). A node is
 * either a leaf (uniformly empty or uniformly filled across its whole
 * extent) or has exactly 8 children covering its 8 octants - the
 * "optimization" this exists for (see the roadmap this implements) is
 * exactly this: a large uniformly-filled or uniformly-empty region
 * collapses to ONE leaf node regardless of how many individual voxels
 * it represents, rather than a dense array needing one entry per
 * voxel. A solid cube primitive, filled via insertFilledBox once for
 * its whole volume, stays close to a single root leaf; only genuinely
 * irregular boundaries (a cone's tapering edge, a torus's curved
 * surface) actually need to subdivide down toward individual voxels.
 *
 * Coordinates are in VOXEL units (integers), not world units -
 * PrimitiveVoxelizer is what maps a primitive's world-space geometry
 * onto this integer grid in the first place.
 */
public final class VoxelOctree {

    private final int maxDepth;
    private final int gridSize; // 2^maxDepth
    private Node root;

    private static final class Node {
        Node[] children; // null if this is a leaf
        boolean filled;  // meaningful only when children == null

        static Node leaf(boolean filled) {
            Node n = new Node();
            n.filled = filled;
            return n;
        }
    }

    public VoxelOctree(int maxDepth) {
        if (maxDepth < 1 || maxDepth > 12) {
            // 12 -> a 4096^3 addressable grid, already impractically
            // fine for anything this project voxelizes - bounding depth
            // here catches a caller accidentally passing a voxel COUNT
            // instead of a depth (an easy mistake given both are small
            // integers) before it turns into a many-gigabyte mistake.
            throw new IllegalArgumentException("maxDepth must be 1..12, got " + maxDepth);
        }
        this.maxDepth = maxDepth;
        this.gridSize = 1 << maxDepth;
        this.root = Node.leaf(false);
    }

    public int gridSize() {
        return gridSize;
    }

    private boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < gridSize && y < gridSize && z < gridSize;
    }

    public void insert(int x, int y, int z) {
        if (!inBounds(x, y, z)) return; // silently ignored - a voxelizer's shape test can legitimately produce a coordinate right at the grid's edge; this isn't the place to enforce that
        root = insert(root, 0, 0, 0, gridSize, x, y, z);
    }

    public void remove(int x, int y, int z) {
        if (!inBounds(x, y, z)) return;
        root = setFilled(root, 0, 0, 0, gridSize, x, y, z, false);
    }

    public boolean isFilled(int x, int y, int z) {
        if (!inBounds(x, y, z)) return false;
        return isFilled(root, 0, 0, 0, gridSize, x, y, z);
    }

    /** Fills every voxel in [minX,maxX) x [minY,maxY) x [minZ,maxZ)
     * (half-open, so a full-grid fill is insertFilledBox(0,0,0,
     * gridSize,gridSize,gridSize)) - the bulk-fill path a voxelizer
     * should prefer over calling insert() once per voxel for a
     * primitive's solid interior, since THIS is what actually gets the
     * "large region -> one node" compression insert() alone can't:
     * insert() only ever marks ONE voxel and has to subdivide the
     * whole path down to that leaf to do it, so filling a large box one
     * insert() at a time would still end up fully subdivided by the
     * time it's done, defeating the whole point. */
    public void insertFilledBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int cMinX = Math.max(0, minX), cMinY = Math.max(0, minY), cMinZ = Math.max(0, minZ);
        int cMaxX = Math.min(gridSize, maxX), cMaxY = Math.min(gridSize, maxY), cMaxZ = Math.min(gridSize, maxZ);
        if (cMinX >= cMaxX || cMinY >= cMaxY || cMinZ >= cMaxZ) return; // empty after clamping to grid bounds
        root = fillBox(root, 0, 0, 0, gridSize, cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ);
    }

    /** Every filled leaf's voxel coordinate range, as (x,y,z,sizeInVoxels)
     * tuples - a single entry for a large uniformly-filled node covers
     * its WHOLE extent at once (sizeInVoxels > 1), which is the actual
     * payoff of the sparse representation: a renderer or physics step
     * consuming this can treat one such entry as one large box instead
     * of iterating every individual voxel inside it. Use
     * collectFilledUnitVoxels() instead if per-unit-voxel granularity
     * is specifically what's needed (e.g. for a destruction effect
     * that wants individually-fallable pieces). */
    public List<int[]> collectFilledRegions() {
        List<int[]> out = new ArrayList<>();
        collectFilledRegions(root, 0, 0, 0, gridSize, out);
        return out;
    }

    /** Same as collectFilledRegions, but a large filled node is
     * expanded into its individual 1x1x1 voxels rather than kept as one
     * big region - the granularity a destruction/falling-piece system
     * needs, at the cost of losing the sparse representation's own size
     * advantage for anything consuming the result (this list is exactly
     * as large as the voxel count, same as a dense grid would produce -
     * the octree's own storage stayed compact, this output just isn't).
     * Not the right choice for large solid volumes where
     * collectFilledRegions' own boxes are all a renderer actually
     * needs. */
    public List<int[]> collectFilledUnitVoxels() {
        List<int[]> out = new ArrayList<>();
        for (int[] region : collectFilledRegions()) {
            int size = region[3];
            for (int dx = 0; dx < size; dx++) {
                for (int dy = 0; dy < size; dy++) {
                    for (int dz = 0; dz < size; dz++) {
                        out.add(new int[]{region[0] + dx, region[1] + dy, region[2] + dz, 1});
                    }
                }
            }
        }
        return out;
    }

    public int countFilledUnitVoxels() {
        int[] total = {0};
        countFilled(root, gridSize, total);
        return total[0];
    }

    // ============================================================
    // Internal recursive implementation
    // ============================================================

    private Node insert(Node node, int ox, int oy, int oz, int size, int x, int y, int z) {
        if (size == 1) return Node.leaf(true);
        if (node.children == null) {
            if (node.filled) return node; // already fully filled here - inserting one more voxel changes nothing
            node = subdivide(node, size);
        }
        int half = size / 2;
        int idx = childIndex(ox, oy, oz, half, x, y, z);
        int[] childOrigin = childOrigin(ox, oy, oz, half, idx);
        node.children[idx] = insert(node.children[idx], childOrigin[0], childOrigin[1], childOrigin[2], half, x, y, z);
        maybeCollapse(node);
        return node;
    }

    private Node setFilled(Node node, int ox, int oy, int oz, int size, int x, int y, int z, boolean value) {
        if (size == 1) return Node.leaf(value);
        if (node.children == null) {
            if (node.filled == value) return node; // no-op: already the target state everywhere in this region
            node = subdivide(node, size);
        }
        int half = size / 2;
        int idx = childIndex(ox, oy, oz, half, x, y, z);
        int[] childOrigin = childOrigin(ox, oy, oz, half, idx);
        node.children[idx] = setFilled(node.children[idx], childOrigin[0], childOrigin[1], childOrigin[2], half, x, y, z, value);
        maybeCollapse(node);
        return node;
    }

    private boolean isFilled(Node node, int ox, int oy, int oz, int size, int x, int y, int z) {
        if (node.children == null) return node.filled;
        int half = size / 2;
        int idx = childIndex(ox, oy, oz, half, x, y, z);
        int[] childOrigin = childOrigin(ox, oy, oz, half, idx);
        return isFilled(node.children[idx], childOrigin[0], childOrigin[1], childOrigin[2], half, x, y, z);
    }

    private Node fillBox(Node node, int ox, int oy, int oz, int size,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // Fully outside the requested box -> unchanged.
        if (ox >= maxX || oy >= maxY || oz >= maxZ || ox + size <= minX || oy + size <= minY || oz + size <= minZ) {
            return node;
        }
        // Fully inside the requested box -> this whole node becomes one
        // filled leaf, regardless of what it was before - exactly the
        // collapse that makes a solid primitive cheap to represent.
        if (ox >= minX && oy >= minY && oz >= minZ && ox + size <= maxX && oy + size <= maxY && oz + size <= maxZ) {
            return Node.leaf(true);
        }
        // Partial overlap - must subdivide (unless already a single
        // voxel, nothing smaller to subdivide into) and recurse per
        // child.
        if (size == 1) return Node.leaf(true); // a 1x1x1 node with ANY overlap is fully inside by definition
        if (node.children == null) node = subdivide(node, size);
        int half = size / 2;
        for (int i = 0; i < 8; i++) {
            int[] childOrigin = childOrigin(ox, oy, oz, half, i);
            node.children[i] = fillBox(node.children[i], childOrigin[0], childOrigin[1], childOrigin[2], half,
                minX, minY, minZ, maxX, maxY, maxZ);
        }
        maybeCollapse(node);
        return node;
    }

    private void collectFilledRegions(Node node, int ox, int oy, int oz, int size, List<int[]> out) {
        if (node.children == null) {
            if (node.filled) out.add(new int[]{ox, oy, oz, size});
            return;
        }
        int half = size / 2;
        for (int i = 0; i < 8; i++) {
            int[] childOrigin = childOrigin(ox, oy, oz, half, i);
            collectFilledRegions(node.children[i], childOrigin[0], childOrigin[1], childOrigin[2], half, out);
        }
    }

    private void countFilled(Node node, int size, int[] total) {
        if (node.children == null) {
            if (node.filled) total[0] += (long) size * size * size > Integer.MAX_VALUE ? Integer.MAX_VALUE : size * size * size;
            return;
        }
        int half = size / 2;
        for (Node child : node.children) countFilled(child, half, total);
    }

    private Node subdivide(Node node, int size) {
        Node result = new Node();
        result.children = new Node[8];
        for (int i = 0; i < 8; i++) result.children[i] = Node.leaf(node.filled);
        return result;
    }

    /** If all 8 children ended up as leaves with the SAME filled state,
     * collapses them back into a single leaf - keeps the tree compact
     * after a sequence of inserts/removes that happens to leave a
     * whole subtree uniform again, rather than only ever growing more
     * subdivided over time. */
    private void maybeCollapse(Node node) {
        if (node.children == null) return;
        boolean first = node.children[0].filled;
        for (Node child : node.children) {
            if (child.children != null || child.filled != first) return; // not uniform - stays subdivided
        }
        node.children = null;
        node.filled = first;
    }

    private static int childIndex(int ox, int oy, int oz, int half, int x, int y, int z) {
        int bit = 0;
        if (x >= ox + half) bit |= 1;
        if (y >= oy + half) bit |= 2;
        if (z >= oz + half) bit |= 4;
        return bit;
    }

    private static int[] childOrigin(int ox, int oy, int oz, int half, int idx) {
        return new int[]{
            ox + ((idx & 1) != 0 ? half : 0),
            oy + ((idx & 2) != 0 ? half : 0),
            oz + ((idx & 4) != 0 ? half : 0),
        };
    }
}
