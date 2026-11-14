package pbd.voxel;

import java.util.BitSet;
import java.util.List;

/**
 * What a shot door has lost, seen from the front: where one could now look THROUGH it.
 *
 * A door is a slab, and what hides someone behind it is not how much of its volume is left but whether any line of sight
 * runs straight through. So the slab is looked at along its thinnest axis (the way a person standing at it looks): the cells
 * of its face - one per voxel column - that the whole slab covered form its {@link Footprint}, taken once when the slab is
 * still whole; later, {@link #measure} marks the columns that still hold at least one voxel and calls every covered column
 * with none an OPENING. A crater that only dents the slab leaves the back of every column it touches, so it opens nothing; one
 * that goes right through opens a hole, and a piece that breaks away takes its columns with it.
 *
 * Two numbers come out. {@link Report#integrity} is the share of the face that still blocks the view (1 = whole), for a HUD or
 * an AI that wants a degree; {@link Report#widestGap} is the side of the largest SQUARE of opening cells in metres, which is what
 * decides whether anyone could see through or climb through (a bullet-sized hole is a lot of damage to the percentage and none
 * to the hiding place). Cells are the destruction voxels (5 mm), so a gap is measured to the voxel.
 *
 * Pure and GL-free: the entries are the usual octree-aligned {@code int[]{x,y,z,size}} cubes.
 */
public final class SlabOpenings {

    private SlabOpenings() {}

    /** The cells of a slab's face, fixed while the slab is whole. */
    public static final class Footprint {
        /** The axis the view runs along: the one the slab is thinnest on (0 = x, 1 = y, 2 = z; the lowest when two are equal). */
        public final int axis;
        /** The two axes of the face, in increasing order, and the corner (in cells) and size of the face's box on them. */
        final int uAxis, vAxis, u0, v0, width, height;
        private final BitSet covered;
        /** How many cells of the face the slab covered. */
        public final long coveredCells;

        private Footprint(int axis, int u0, int v0, int width, int height, BitSet covered) {
            this.axis = axis;
            this.uAxis = axis == 0 ? 1 : 0;
            this.vAxis = axis == 2 ? 1 : 2;
            this.u0 = u0;
            this.v0 = v0;
            this.width = width;
            this.height = height;
            this.covered = covered;
            this.coveredCells = covered.cardinality();
        }

        /** The footprint of a whole slab (null or empty: a footprint that covers nothing). */
        public static Footprint of(List<int[]> entries) {
            if (entries == null || entries.isEmpty()) return new Footprint(0, 0, 0, 0, 0, new BitSet());
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] e : entries) {
                int s = size(e);
                for (int k = 0; k < 3; k++) {
                    lo[k] = Math.min(lo[k], e[k]);
                    hi[k] = Math.max(hi[k], e[k] + s);
                }
            }
            int axis = 0;
            for (int k = 1; k < 3; k++) if (hi[k] - lo[k] < hi[axis] - lo[axis]) axis = k;
            int uAxis = axis == 0 ? 1 : 0, vAxis = axis == 2 ? 1 : 2;
            int width = hi[uAxis] - lo[uAxis], height = hi[vAxis] - lo[vAxis];
            BitSet covered = new BitSet(width * height);
            mark(covered, entries, uAxis, vAxis, lo[uAxis], lo[vAxis], width, height);
            return new Footprint(axis, lo[uAxis], lo[vAxis], width, height, covered);
        }
    }

    /** How much of a slab's face one can look through. */
    public static final class Report {
        public final int axis;
        public final long coveredCells, openCells;
        /** Side, in cells, of the largest square of opening cells. */
        public final int widestGapCells;
        public final float voxelWorldSize;

        Report(int axis, long coveredCells, long openCells, int widestGapCells, float voxelWorldSize) {
            this.axis = axis;
            this.coveredCells = coveredCells;
            this.openCells = openCells;
            this.widestGapCells = widestGapCells;
            this.voxelWorldSize = voxelWorldSize;
        }

        /** The share of the face that still blocks the view: 1 = whole, 0 = nothing left in the way. A slab that covered nothing has lost nothing: 1. */
        public double integrity() {
            return coveredCells == 0 ? 1.0 : 1.0 - (double) openCells / coveredCells;
        }

        /** The side, in metres, of the largest square one can look through. 0 = no opening at all. */
        public double widestGap() {
            return widestGapCells * (double) voxelWorldSize;
        }

        @Override
        public String toString() {
            return String.format("integrity %.3f, widest gap %.3f m (%d of %d face cells open)", integrity(), widestGap(), openCells, coveredCells);
        }
    }

    /**
     * The report of a slab that is no more - removed outright, with no cubes left to measure (an object too thin or too empty to turn
     * into cubes is destroyed whole, see DestructionWorld): the whole of its face is open, and the gap is as wide as can be said.
     */
    public static Report gone(float voxelWorldSize) {
        return new Report(0, 1, 1, Integer.MAX_VALUE / 4, voxelWorldSize);
    }

    /** The report of a slab that has not been touched. */
    public static Report whole(float voxelWorldSize) {
        return new Report(0, 0, 0, 0, voxelWorldSize);
    }

    /**
     * Compares what is left of the slab ({@code remaining}, the cubes still attached to it) with its footprint. The slab is
     * measured on the grid of its footprint: a remaining cube outside that grid (the remains of a slab are always inside its
     * whole self) counts for nothing.
     */
    public static Report measure(Footprint original, List<int[]> remaining, float voxelWorldSize) {
        if (original.coveredCells == 0) return new Report(original.axis, 0, 0, 0, voxelWorldSize);
        BitSet blocked = new BitSet(original.width * original.height);
        if (remaining != null && !remaining.isEmpty()) {
            mark(blocked, remaining, original.uAxis, original.vAxis, original.u0, original.v0, original.width, original.height);
        }
        long open = 0;
        int widest = 0;
        int[] above = new int[original.width + 1]; // side of the largest open square ending at the cell above, per column (index 0 is the wall)
        int[] row = new int[original.width + 1];
        for (int v = 0; v < original.height; v++) {
            row[0] = 0;
            for (int u = 0; u < original.width; u++) {
                int bit = v * original.width + u;
                if (original.covered.get(bit) && !blocked.get(bit)) {
                    open++;
                    int side = 1 + Math.min(row[u], Math.min(above[u + 1], above[u]));
                    row[u + 1] = side;
                    if (side > widest) widest = side;
                } else {
                    row[u + 1] = 0;
                }
            }
            int[] t = above;
            above = row;
            row = t;
        }
        return new Report(original.axis, original.coveredCells, open, widest, voxelWorldSize);
    }

    private static int size(int[] e) {
        return e.length > 3 ? e[3] : 1;
    }

    /** Marks the cells of the face that the cubes' columns cover (clipped to the face's box). */
    private static void mark(BitSet bits, List<int[]> entries, int uAxis, int vAxis, int u0, int v0, int width, int height) {
        for (int[] e : entries) {
            int s = size(e);
            int uFrom = Math.max(0, e[uAxis] - u0), uTo = Math.min(width, e[uAxis] + s - u0);
            int vFrom = Math.max(0, e[vAxis] - v0), vTo = Math.min(height, e[vAxis] + s - v0);
            for (int v = vFrom; v < vTo; v++) {
                if (uFrom < uTo) bits.set(v * width + uFrom, v * width + uTo);
            }
        }
    }
}
