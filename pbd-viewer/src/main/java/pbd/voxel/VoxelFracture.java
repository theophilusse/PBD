package pbd.voxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The break: when a shot leaves a piece held to the rest by a connection that is too narrow, the connection does not hold -
 * it shatters, and the whole piece comes away. Without this rule a post shot through so that a 1 cm sliver still joins its
 * top to its stump would simply stay up, balanced on a splinter; with it the sliver is thrown off as splinters and the top
 * falls.
 *
 * WHAT "TOO NARROW" MEANS. {@code neckVoxels} (the material's {@code breakWidth} in voxels, see BreakProfile) is the width of
 * the thinnest connection that still holds. A cell is "thick" when some cube of that many voxels on a side fits entirely in
 * the solid and covers it - the morphological OPENING of the solid by a cube. What an opening leaves out is thin: slivers,
 * ligaments, films, and also the staircase on a curved surface. Two kinds of thin cell are NOT a broken connection and are
 * left alone:
 * <ul>
 *   <li>cells that were already thin BEFORE the hit (the same test on the solid before the crater): the thin tip of a cone,
 *       the staircase of a cylinder, a mesh's own thin handle - an object is not "cut" by what it always was. Only cells that
 *       the crater made thin count;</li>
 *   <li>thin cells that touch only ONE thick piece: the ragged rim a crater leaves on a surface is attached to that piece and
 *       breaks nothing. A thin run is a broken connection only when it joins TWO OR MORE separate thick pieces; then the whole
 *       run is taken out, and what it joined is no longer joined.</li>
 * </ul>
 * The cube is as wide as {@code neckVoxels} on each axis, but never wider than the object itself on that axis: a 2 cm plank
 * needs its whole 2 cm of thickness at a connection, not 3 cm that it never had.
 *
 * WHERE IT LOOKS. Removing the crater can only change the opening of the cells within {@code n - 1} of the crater's box
 * (every cube that covers a cell lies within {@code n - 1} of it), so only those cells can have become thin; and to compute
 * the opening of those cells the solid is needed {@code n - 1} further out. Thick pieces are told apart inside that window
 * only: a thin web that joins two pieces which are also joined further away is broken all the same - a web narrower than
 * the neck does not hold, whatever else holds the pieces.
 *
 * COST. The analysis rasterizes the solid inside that window into flat arrays and works with prefix sums, so it never
 * expands a big merged cube anywhere else (the lesson of the crater's OutOfMemoryError). A window over
 * {@link #MAX_WINDOW_CELLS} cells is not analysed: the hit stays as the crater made it, and the result says so.
 *
 * Pure geometry, no GL. The cells it takes out come back as cubes ({@link Result#splinters}), the volume the splinter
 * particles of a break are thrown from.
 */
public final class VoxelFracture {

    private VoxelFracture() {}

    /** Most cells (prefix table included) one analysis may use; beyond it the hit is left as the crater made it. */
    public static final long MAX_WINDOW_CELLS = 8_000_000L;

    /** What {@link #shatter} found. */
    public static final class Result {
        /** The solid after the break: {@code after} itself (the same list) when nothing broke. */
        public final List<int[]> surviving;
        /** The cubes taken out - the connections that were too narrow. Empty when nothing broke. */
        public final List<int[]> splinters;
        public final long splinterVoxels;
        /** Face-connected pieces before and after the break (both 0 when nothing broke, so nothing was counted). */
        public final int piecesBefore, piecesAfter;
        /** Cells of the analysis window (0 when no analysis ran). */
        public final long windowCells;
        /** Why no analysis ran, or null. */
        public final String skipped;

        Result(List<int[]> surviving, List<int[]> splinters, long splinterVoxels, int piecesBefore, int piecesAfter, long windowCells, String skipped) {
            this.surviving = surviving;
            this.splinters = splinters;
            this.splinterVoxels = splinterVoxels;
            this.piecesBefore = piecesBefore;
            this.piecesAfter = piecesAfter;
            this.windowCells = windowCells;
            this.skipped = skipped;
        }

        /** Whether the break separated something: the solid is in more pieces than the crater alone left it in. */
        public boolean parted() {
            return piecesAfter > piecesBefore;
        }

        /** Whether a connection broke at all: at least one voxel was taken out (even one voxel is a connection that did not hold). */
        public boolean broke() {
            return splinterVoxels > 0;
        }
    }

    private static Result unchanged(List<int[]> after, long windowCells, String skipped) {
        return new Result(after, Collections.emptyList(), 0, 0, 0, windowCells, skipped);
    }

    /**
     * @param before     the octree-aligned cubes before the hit (the solid the crater was carved from)
     * @param after      what the crater left of them
     * @param craterBox  {minX, minY, minZ, maxX, maxY, maxZ} of the crater (the max side exclusive), in grid cells
     * @param neckVoxels width, in voxels, of the thinnest connection that holds; below 2 nothing can be too narrow
     */
    public static Result shatter(List<int[]> before, List<int[]> after, int[] craterBox, int neckVoxels) {
        if (neckVoxels < 2 || before.isEmpty() || after.isEmpty()) return unchanged(after, 0, null);

        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] e : before) {
            int s = e.length > 3 ? e[3] : 1;
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], e[a]);
                hi[a] = Math.max(hi[a], e[a] + s);
            }
        }
        int[] n = new int[3];
        for (int a = 0; a < 3; a++) n[a] = Math.min(neckVoxels, hi[a] - lo[a]);
        if (n[0] < 2 && n[1] < 2 && n[2] < 2) return unchanged(after, 0, null);

        // inner: every cell that can have become thin because of the crater - those within n - 1 of its box (a cube that covers a
        // cell lies within n - 1 of it); outer: the n - 1 more that the opening of those cells looks at
        int[] innerLo = new int[3], innerHi = new int[3], outerLo = new int[3], outerHi = new int[3];
        for (int a = 0; a < 3; a++) {
            int reach = n[a] - 1;
            innerLo[a] = Math.max(lo[a], craterBox[a] - reach);
            innerHi[a] = Math.min(hi[a], craterBox[3 + a] + reach);
            if (innerLo[a] >= innerHi[a]) return unchanged(after, 0, null); // the crater is nowhere near the solid
            outerLo[a] = Math.max(lo[a], innerLo[a] - reach);
            outerHi[a] = Math.min(hi[a], innerHi[a] + reach);
        }
        int w = outerHi[0] - outerLo[0], h = outerHi[1] - outerLo[1], d = outerHi[2] - outerLo[2];
        long cells = (long) (w + 1) * (h + 1) * (d + 1);
        if (cells > MAX_WINDOW_CELLS) {
            return unchanged(after, cells, "analysis window of " + w + "x" + h + "x" + d + " cells is over the limit of " + MAX_WINDOW_CELLS);
        }

        boolean[] solidAfter = raster(after, outerLo, w, h, d);
        Prefix prefix = new Prefix(w, h, d);
        boolean[] thickAfter = opening(solidAfter, w, h, d, n, prefix);
        boolean[] thickBefore = opening(raster(before, outerLo, w, h, d), w, h, d, n, prefix);

        // the cells the crater made thin: solid now, not covered by a thick cube now, but covered by one before
        byte[] thin = new byte[w * h * d]; // 1 = thin and not looked at yet, 2 = looked at
        boolean any = false;
        for (int z = innerLo[2] - outerLo[2]; z < innerHi[2] - outerLo[2]; z++) {
            for (int y = innerLo[1] - outerLo[1]; y < innerHi[1] - outerLo[1]; y++) {
                int row = (z * h + y) * w;
                for (int x = innerLo[0] - outerLo[0]; x < innerHi[0] - outerLo[0]; x++) {
                    int c = row + x;
                    if (solidAfter[c] && !thickAfter[c] && thickBefore[c]) {
                        thin[c] = 1;
                        any = true;
                    }
                }
            }
        }
        if (!any) return unchanged(after, cells, null);

        // a thin run that joins two or more thick pieces is a connection that is too narrow: take the whole run out
        int[] label = new int[w * h * d]; // labels of the thick pieces, 0 = not labelled yet
        boolean[] taken = new boolean[w * h * d];
        IntList stack = new IntList();
        IntList run = new IntList();
        int nextLabel = 0;
        long takenCells = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int start = 0; start < thin.length; start++) {
            if (thin[start] != 1) continue;
            run.clear();
            stack.clear();
            thin[start] = 2;
            stack.push(start);
            int first = 0;
            boolean joinsTwo = false;
            while (stack.size > 0) {
                int c = stack.pop();
                run.push(c);
                int x = c % w, y = (c / w) % h, z = c / (w * h);
                for (int dir = 0; dir < 6; dir++) {
                    int nb;
                    switch (dir) {
                        case 0: if (x == 0) continue; nb = c - 1; break;
                        case 1: if (x == w - 1) continue; nb = c + 1; break;
                        case 2: if (y == 0) continue; nb = c - w; break;
                        case 3: if (y == h - 1) continue; nb = c + w; break;
                        case 4: if (z == 0) continue; nb = c - w * h; break;
                        default: if (z == d - 1) continue; nb = c + w * h; break;
                    }
                    if (thin[nb] == 1) {
                        thin[nb] = 2;
                        stack.push(nb);
                    } else if (thickAfter[nb] && !joinsTwo) {
                        if (label[nb] == 0) nextLabel = flood(nb, thickAfter, label, nextLabel + 1, w, h, d);
                        if (first == 0) first = label[nb];
                        else if (label[nb] != first) joinsTwo = true;
                    }
                }
            }
            if (!joinsTwo) continue;
            for (int i = 0; i < run.size; i++) {
                int c = run.data[i];
                taken[c] = true;
                takenCells++;
                int x = c % w, y = (c / w) % h, z = c / (w * h);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }
        if (takenCells == 0) return unchanged(after, cells, null);

        final int ox = outerLo[0], oy = outerLo[1], oz = outerLo[2];
        final int fw = w, fh = h;
        VoxelCrater.Result carved = VoxelCrater.carveCells((x, y, z) -> taken[((z - oz) * fh + (y - oy)) * fw + (x - ox)],
            minX + ox, minY + oy, minZ + oz, maxX + ox + 1, maxY + oy + 1, maxZ + oz + 1, after);
        int piecesBefore = VoxelConnectivity.split(after).size();
        int piecesAfter = VoxelConnectivity.split(carved.surviving).size();
        return new Result(carved.surviving, carved.removed, carved.removedVoxels, piecesBefore, piecesAfter, cells, null);
    }

    // ------------------------------------------------------------------ rasterizing and the opening

    /** The cubes as a flat boolean grid over the window starting at {@code origin}: index (z * h + y) * w + x. */
    private static boolean[] raster(List<int[]> entries, int[] origin, int w, int h, int d) {
        boolean[] g = new boolean[w * h * d];
        for (int[] e : entries) {
            int s = e.length > 3 ? e[3] : 1;
            int x0 = Math.max(e[0], origin[0]) - origin[0], x1 = Math.min(e[0] + s, origin[0] + w) - origin[0];
            int y0 = Math.max(e[1], origin[1]) - origin[1], y1 = Math.min(e[1] + s, origin[1] + h) - origin[1];
            int z0 = Math.max(e[2], origin[2]) - origin[2], z1 = Math.min(e[2] + s, origin[2] + d) - origin[2];
            if (x0 >= x1 || y0 >= y1 || z0 >= z1) continue;
            for (int z = z0; z < z1; z++) {
                for (int y = y0; y < y1; y++) {
                    int row = (z * h + y) * w;
                    Arrays.fill(g, row + x0, row + x1, true);
                }
            }
        }
        return g;
    }

    /**
     * The opening of a grid by a box of n[0] x n[1] x n[2] cells: the cells covered by some such box that lies wholly in the
     * set. Erosion (the corners where the box fits) and dilation (everything such a box covers), each a box count on a
     * prefix table.
     */
    private static boolean[] opening(boolean[] g, int w, int h, int d, int[] n, Prefix prefix) {
        int nx = n[0], ny = n[1], nz = n[2];
        prefix.build(g);
        boolean[] eroded = new boolean[g.length];
        int full = nx * ny * nz;
        for (int z = 0; z + nz <= d; z++) {
            for (int y = 0; y + ny <= h; y++) {
                int row = (z * h + y) * w;
                for (int x = 0; x + nx <= w; x++) {
                    if (prefix.sum(x, y, z, x + nx, y + ny, z + nz) == full) eroded[row + x] = true;
                }
            }
        }
        prefix.build(eroded);
        boolean[] open = new boolean[g.length];
        for (int z = 0; z < d; z++) {
            int z0 = Math.max(0, z - nz + 1);
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - ny + 1);
                int row = (z * h + y) * w;
                for (int x = 0; x < w; x++) {
                    int x0 = Math.max(0, x - nx + 1);
                    if (prefix.sum(x0, y0, z0, x + 1, y + 1, z + 1) > 0) open[row + x] = true;
                }
            }
        }
        return open;
    }

    /** Labels the face-connected set of {@code solid} cells that contains {@code start} with {@code id}; returns {@code id}. */
    private static int flood(int start, boolean[] solid, int[] label, int id, int w, int h, int d) {
        IntList stack = new IntList();
        label[start] = id;
        stack.push(start);
        while (stack.size > 0) {
            int c = stack.pop();
            int x = c % w, y = (c / w) % h, z = c / (w * h);
            if (x > 0 && solid[c - 1] && label[c - 1] == 0) { label[c - 1] = id; stack.push(c - 1); }
            if (x < w - 1 && solid[c + 1] && label[c + 1] == 0) { label[c + 1] = id; stack.push(c + 1); }
            if (y > 0 && solid[c - w] && label[c - w] == 0) { label[c - w] = id; stack.push(c - w); }
            if (y < h - 1 && solid[c + w] && label[c + w] == 0) { label[c + w] = id; stack.push(c + w); }
            if (z > 0 && solid[c - w * h] && label[c - w * h] == 0) { label[c - w * h] = id; stack.push(c - w * h); }
            if (z < d - 1 && solid[c + w * h] && label[c + w * h] == 0) { label[c + w * h] = id; stack.push(c + w * h); }
        }
        return id;
    }

    /** A 3D prefix-sum table: the number of set cells in any box in O(1). */
    private static final class Prefix {
        final int w, h;
        final int[] p;

        Prefix(int w, int h, int d) {
            this.w = w;
            this.h = h;
            this.p = new int[(w + 1) * (h + 1) * (d + 1)];
        }

        private int at(int x, int y, int z) {
            return p[(z * (h + 1) + y) * (w + 1) + x];
        }

        void build(boolean[] g) {
            int sx = w + 1, sy = h + 1;
            int d = p.length / (sx * sy) - 1;
            for (int z = 0; z < d; z++) {
                for (int y = 0; y < h; y++) {
                    int run = 0;
                    int base = ((z + 1) * sy + (y + 1)) * sx;
                    int gBase = (z * h + y) * w;
                    for (int x = 0; x < w; x++) {
                        if (g[gBase + x]) run++;
                        p[base + x + 1] = run;
                    }
                }
            }
            for (int z = 1; z <= d; z++) {
                for (int y = 2; y <= h; y++) {
                    int base = (z * sy + y) * sx;
                    int prev = (z * sy + y - 1) * sx;
                    for (int x = 1; x <= w; x++) p[base + x] += p[prev + x];
                }
            }
            for (int z = 2; z <= d; z++) {
                for (int y = 1; y <= h; y++) {
                    int base = (z * sy + y) * sx;
                    int prev = ((z - 1) * sy + y) * sx;
                    for (int x = 1; x <= w; x++) p[base + x] += p[prev + x];
                }
            }
        }

        /** Set cells in [x0, x1) x [y0, y1) x [z0, z1). */
        int sum(int x0, int y0, int z0, int x1, int y1, int z1) {
            return at(x1, y1, z1) - at(x0, y1, z1) - at(x1, y0, z1) - at(x1, y1, z0)
                + at(x0, y0, z1) + at(x0, y1, z0) + at(x1, y0, z0) - at(x0, y0, z0);
        }
    }

    private static final class IntList {
        int[] data = new int[256];
        int size;

        void push(int v) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }

        int pop() {
            return data[--size];
        }

        void clear() {
            size = 0;
        }
    }
}
