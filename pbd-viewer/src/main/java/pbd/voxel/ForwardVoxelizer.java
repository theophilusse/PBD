package pbd.voxel;

import pbd.format.PbdInstance;
import pbd.format.PrimitiveParts;

/**
 * Voxelizes what the GPU DRAWS, for the shapes whose parameters the analytic classifiers of PrimitiveVoxelizer cannot describe:
 * a hollow cylinder or cone (a lampshade, a pipe, a cup), a cube with faces removed, a bent or sheared solid, and a plane or
 * disc under a modifier. The surface (or the solid) is taken from {@link ShapeEval}, the port of pbd.tese, and carried forward:
 * a lattice over the shape's own parameters is mapped through the same bend, shear and taper the shader applies, and every cell of
 * it deposits its volume in the voxels its image overlaps, each getting the share it covers. A voxel that gets half its volume or
 * more is filled.
 *
 * Why forward and not "ask each voxel whether it is inside": a bend or a shear has no simple inverse (and a shear none at all in
 * closed form), while the forward map is exactly the shader. The price is that the lattice has to be fine enough that no cell's
 * image is larger than a voxel (it is built so that none is longer than {@link #STEP} of one, and checked afterwards - a lattice
 * that turns out too coarse is refined and the pass repeated).
 *
 * TWO KINDS OF SHAPE, one mechanism:
 * <ul>
 *   <li>a SOLID (a closed primitive under a bend or shear): a 3D lattice over the canonical cube [-0.5,0.5]^3, the cells inside the
 *       canonical shape (partly-inside cells weighted by the part that is) mapped through the modifier chain;</li>
 *   <li>a SHELL (what has no inside): the drawn parts, each a (u,v) lattice grown inward along the surface normal by a thickness -
 *       so the outside is exactly the surface the shader draws and the wall has some substance to be shot at. A hollow lampshade
 *       stays a lampshade, open at both ends; a plane or disc is grown to both sides.</li>
 * </ul>
 *
 * WHICH VOXELS: the voxels are the cells of a lattice laid from the shape's own lower corner (see {@code Plan.anchor}), the way the
 * analytic grid is laid from the object's: a flat face parallel to an axis then lies on voxel boundaries and fills whole voxels, instead
 * of cutting through them at whatever phase the origin of the object gave (a tube five voxels tall, centred on its origin, had half a
 * voxel at each end and lost both). The parts of a shell (the faces of a box, the wall and the caps of a cylinder) overlap where they
 * meet, each grown inward from its own surface; a voxel's coverage is the largest of the parts', not their sum, which would fill the
 * voxels along every edge at a quarter covered.
 *
 * Sizes: the work is proportional to the number of lattice cells, capped at {@link #MAX_CELLS}; a shape that would need more, or
 * whose grid would not fit the octree, is voxelized with larger voxels instead (the result says which size it used, as
 * {@code voxelWorldSize}, exactly like an analytic result does).
 */
final class ForwardVoxelizer {
    private ForwardVoxelizer() {}

    /** The longest side of a lattice cell's image, in voxels. 0.5 puts about eight cells in a voxel: coverage is then good to a few percent. */
    static final double STEP = 0.5;
    /** A voxel is filled when the cells that landed in it cover at least this much of it. */
    static final double FILL_COVERAGE = 0.5;
    /**
     * A coverage is a sum of floats: a voxel covered exactly by half (a face on the middle of a voxel - sizes in round numbers do that
     * all the time) must be filled or left out by the rule, not by the last digit of the sum. Half fills, as Math.round does for the
     * analytic grid.
     */
    static final double FILL_TOLERANCE = 1e-3;
    private static final float FILL_THRESHOLD = (float) (FILL_COVERAGE - FILL_TOLERANCE);
    /** Lattice cells per voxelization: two seconds of work at the very most (about half a microsecond a cell, on a slow machine). */
    static final long MAX_CELLS = 4_000_000L;
    /** Voxels per axis the grid may span (the octree addresses 1024). */
    static final int MAX_GRID = 1000;
    /** Wall thickness of a hollow shape, in voxels: enough to be a wall (three voxels = 15 mm at 5 mm), little enough to still be a shade. */
    static final double SHELL_VOXELS = 3.0;
    /** A wall never grows past this fraction of the extent it grows across: a thin tube stays a tube, it does not fill up. */
    static final double SHELL_MAX_FRACTION = 0.4;
    /** The thickness a plane or disc is given is a fraction of its largest side (as before), but never more than this many metres. */
    static final double SLAB_FRACTION = 0.08;
    static final double SLAB_MAX = 0.1;
    /** A slab is never thinner than this many layers of voxels. */
    static final double SLAB_MIN_VOXELS = 2.0;
    /** The least a hollow cube's edges are rounded by (see {@link ShapeEval#ofShell}), in voxels. */
    static final double MIN_ROUND_VOXELS = 0.25;
    /** A pass is repeated finer when more than this share of the volume went into cells longer than a voxel (a fold of the shader's own surface is a few cells, not a bad lattice). */
    static final double WIDE_VOLUME_TOLERANCE = 0.02;

    /**
     * How many layers of voxels a plane or disc of this size is made of, at this voxel size: 8% of its larger side, at most SLAB_MAX,
     * rounded (half up, as the analytic grid rounds) to whole layers, never fewer than SLAB_MIN_VOXELS. WHOLE layers, because the slab is
     * laid from its lower face up ({@code Plan.anchor}): both its faces fall on voxel boundaries and every layer is full. A slab whose
     * faces fell INSIDE a layer left that layer speckled - the columns that happened to collect half a voxel of cells were filled, the
     * others not (a leaf of 0.8 m made 264 voxels of 1264 in each outer layer).
     */
    static double slabLayers(double sizeX, double sizeZ, double voxelWorldSize) {
        double thickness = Math.min(SLAB_FRACTION * Math.max(sizeX, sizeZ), SLAB_MAX);
        return Math.max(SLAB_MIN_VOXELS, Math.floor(thickness / voxelWorldSize + 0.5));
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Whether the forward path is the one for this instance: a hollow shape, or a bend/shear, or a modified plane/disc. */
    static boolean handles(PbdInstance inst, ShapeEval eval) {
        if (eval == null) return false;
        boolean flat = "plane".equals(inst.type) || "disc".equals(inst.type);
        if (flat) return eval.hasEffectiveModifier();
        if (!PrimitiveParts.isClosedSolid(inst)) return PrimitiveParts.of(inst).length > 0;
        return eval.hasBendOrShear();
    }

    static PrimitiveVoxelizer.Result voxelize(PbdInstance inst, ShapeEval eval, float targetVoxelWorldSize) {
        double vws = targetVoxelWorldSize;
        boolean solid = PrimitiveParts.isClosedSolid(inst);
        Plan plan = null;
        ShapeEval surface = eval;
        for (int attempt = 0; attempt < 24; attempt++) {
            // a hollow rounded cube is sampled through a surface whose rounding is at least a fraction of a voxel on every axis (see ShapeEval.ofShell)
            surface = solid || !eval.isRoundedCube() ? eval : ShapeEval.ofShell(inst, MIN_ROUND_VOXELS * vws);
            plan = solid ? planSolid(inst, surface, vws) : planShell(inst, surface, vws);
            if (plan == null) return null;
            if (plan.cells <= MAX_CELLS && plan.gridMax <= MAX_GRID) break;
            double byCells = plan.cells > MAX_CELLS ? Math.cbrt((double) plan.cells / MAX_CELLS) : 1.0;
            double byGrid = plan.gridMax > MAX_GRID ? (double) plan.gridMax / MAX_GRID : 1.0;
            vws *= Math.max(1.1, Math.max(byCells, byGrid));
            plan = null;
        }
        if (plan == null) return null;

        VoxelMap map = null;
        // The lattice is checked after the pass: if cells whose image is bigger than a voxel carry a real share of the volume, the pass is repeated finer.
        for (int pass = 0; pass < 3; pass++) {
            map = new VoxelMap(1 << 16);
            Stats stats = new Stats();
            if (solid) accumulateSolid(inst, surface, plan, vws, map, stats); else accumulateShell(inst, surface, plan, vws, map, stats);
            if (stats.wideVolume <= WIDE_VOLUME_TOLERANCE * stats.totalVolume) break;
            Plan refined = plan.finer();
            if (refined.cells > MAX_CELLS * 3L / 2L) break; // too costly to refine: keep what was computed
            plan = refined;
        }
        return buildResult(inst, map, vws, solid, plan.anchor);
    }

    /** What an accumulation pass learned about its own lattice: how much of the volume sits in cells longer than one voxel (of all of it). */
    private static final class Stats {
        double wideVolume;
        double totalVolume;

        void cell(double edgeInVoxels, double volumeInVoxels) {
            totalVolume += volumeInVoxels;
            if (edgeInVoxels > 1.0) wideVolume += volumeInVoxels;
        }
    }

    // ------------------------------------------------------------------
    // Plans: how fine the lattice is
    // ------------------------------------------------------------------

    /** The lattice of one shape: cells per direction (for a shell, per part), the estimated box in voxels, and the thickness. */
    private static final class Plan {
        // solid: nx,ny,nz.  shell: per part nu[], nv[], nd[]
        int nx, ny, nz;
        int[] parts;
        int[] nu, nv, nd;
        /** Per part, the u and v values of the lattice lines when they are not evenly spaced (a rounded cube's faces are denser inside the rounded bands); null = even. */
        double[][] uLines, vLines;
        double thickness;          // shell wall (or slab) thickness in metres
        double[] thicknessOfPart;  // per part, after the thin-object cap
        boolean symmetric;         // slab: grown to both sides
        long cells;
        int gridMax;
        /**
         * A corner of the lattice of voxels, in the object's local frame (metres): the voxels are the cells of the lattice that starts there.
         * The lower corner of what the shape is drawn with - for a slab, of the slab (half its thickness under the sheet).
         */
        double[] anchor;

        /** The same lattice with every cell split in two along each direction. */
        Plan finer() {
            Plan p = new Plan();
            p.parts = parts; p.thickness = thickness; p.thicknessOfPart = thicknessOfPart; p.symmetric = symmetric; p.gridMax = gridMax; p.anchor = anchor;
            p.nx = nx * 2; p.ny = ny * 2; p.nz = nz * 2;
            long c = 0;
            if (parts != null) {
                p.nu = new int[parts.length]; p.nv = new int[parts.length]; p.nd = new int[parts.length];
                p.uLines = new double[parts.length][]; p.vLines = new double[parts.length][];
                for (int i = 0; i < parts.length; i++) {
                    if (uLines[i] != null) { p.uLines[i] = subdivide(uLines[i]); p.nu[i] = p.uLines[i].length - 1; }
                    else p.nu[i] = nu[i] * 2;
                    if (vLines[i] != null) { p.vLines[i] = subdivide(vLines[i]); p.nv[i] = p.vLines[i].length - 1; }
                    else p.nv[i] = nv[i] * 2;
                    p.nd[i] = nd[i] * 2;
                    c += (long) p.nu[i] * p.nv[i] * p.nd[i];
                }
            } else {
                c = (long) p.nx * p.ny * p.nz;
            }
            p.cells = c;
            return p;
        }

        /** The u values of part index i's lattice lines. */
        double[] us(int i) { return uLines != null && uLines[i] != null ? uLines[i] : even(nu[i]); }
        double[] vs(int i) { return vLines != null && vLines[i] != null ? vLines[i] : even(nv[i]); }
    }

    private static double[] even(int n) {
        double[] a = new double[n + 1];
        for (int i = 0; i <= n; i++) a[i] = (double) i / n;
        return a;
    }

    /** Every interval of the sorted lines split in two. */
    private static double[] subdivide(double[] lines) {
        double[] out = new double[(lines.length - 1) * 2 + 1];
        for (int i = 0; i < lines.length - 1; i++) {
            out[i * 2] = lines[i];
            out[i * 2 + 1] = (lines[i] + lines[i + 1]) / 2;
        }
        out[out.length - 1] = lines[lines.length - 1];
        return out;
    }

    private static double[] scaleOf(PbdInstance inst) {
        return new double[]{inst.scale.x, inst.scale.y, inst.scale.z};
    }

    /** The SOLID: how many cells per canonical axis so that no cell's image is longer than STEP voxels. */
    private static Plan planSolid(PbdInstance inst, ShapeEval eval, double vws) {
        double[] sc = scaleOf(inst);
        int coarse = 8;
        double[] stretch = new double[3];
        double[] a = new double[3], b = new double[3];
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double h = 1.0 / coarse;
        for (int i = 0; i <= coarse; i++) for (int j = 0; j <= coarse; j++) for (int k = 0; k <= coarse; k++) {
            double[] c = {-0.5 + i * h, -0.5 + j * h, -0.5 + k * h};
            map(eval, sc, c, a);
            if (!finite(a)) return null;
            for (int d = 0; d < 3; d++) { lo[d] = Math.min(lo[d], a[d]); hi[d] = Math.max(hi[d], a[d]); }
            for (int d = 0; d < 3; d++) {
                double[] c2 = c.clone();
                c2[d] += h;
                map(eval, sc, c2, b);
                if (!finite(b)) return null;
                double len = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])) / h;
                stretch[d] = Math.max(stretch[d], len);
            }
        }
        Plan p = new Plan();
        p.nx = cellsFor(stretch[0], vws);
        p.ny = cellsFor(stretch[1], vws);
        p.nz = cellsFor(stretch[2], vws);
        p.cells = (long) p.nx * p.ny * p.nz;
        p.gridMax = gridMaxOf(lo, hi, vws);
        p.anchor = lo;
        return p;
    }

    private static int cellsFor(double stretchPerUnit, double vws) {
        double n = Math.ceil(stretchPerUnit * 1.2 / (STEP * vws));
        return (int) Math.max(1, Math.min(n, 1_000_000));
    }

    private static int gridMaxOf(double[] lo, double[] hi, double vws) {
        double ext = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        return (int) Math.min(Integer.MAX_VALUE / 2.0, Math.ceil(ext / vws) + 2);
    }

    /** The SHELL: for each drawn part, how many cells along u, along v, and across the wall. */
    private static Plan planShell(PbdInstance inst, ShapeEval eval, double vws) {
        double[] sc = scaleOf(inst);
        int[] parts = eval.parts();
        if (parts.length == 0) return null;
        boolean flat = "plane".equals(inst.type) || "disc".equals(inst.type);
        Plan p = new Plan();
        p.parts = parts;
        p.symmetric = flat;
        p.nu = new int[parts.length]; p.nv = new int[parts.length]; p.nd = new int[parts.length];
        p.uLines = new double[parts.length][]; p.vLines = new double[parts.length][];
        p.thicknessOfPart = new double[parts.length];
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double wall = flat ? slabLayers(sc[0], sc[2], vws) * vws : SHELL_VOXELS * vws;
        p.thickness = wall;
        long cells = 0;
        int coarse = 16;
        double[] pos = new double[3], nrm = new double[3];
        for (int pi = 0; pi < parts.length; pi++) {
            double maxU = 0, maxV = 0;
            double[][] grid = new double[(coarse + 1) * (coarse + 1)][];
            for (int i = 0; i <= coarse; i++) for (int j = 0; j <= coarse; j++) {
                eval.eval(parts[pi], (double) i / coarse, (double) j / coarse, pos, nrm);
                if (!finite(pos)) return null;
                grid[i * (coarse + 1) + j] = new double[]{pos[0] * sc[0], pos[1] * sc[1], pos[2] * sc[2]};
                for (int d = 0; d < 3; d++) { lo[d] = Math.min(lo[d], grid[i * (coarse + 1) + j][d]); hi[d] = Math.max(hi[d], grid[i * (coarse + 1) + j][d]); }
            }
            for (int j = 0; j <= coarse; j++) {   // the u-lines (constant v)
                double len = 0;
                for (int i = 0; i < coarse; i++) len += dist(grid[i * (coarse + 1) + j], grid[(i + 1) * (coarse + 1) + j]);
                maxU = Math.max(maxU, len);
            }
            for (int i = 0; i <= coarse; i++) {   // the v-lines (constant u)
                double len = 0;
                for (int j = 0; j < coarse; j++) len += dist(grid[i * (coarse + 1) + j], grid[i * (coarse + 1) + j + 1]);
                maxV = Math.max(maxV, len);
            }
            double t = wall;
            if (!flat) t = Math.min(t, SHELL_MAX_FRACTION * crossExtent(inst, parts[pi], sc));
            t = Math.max(t, 1.2 * STEP * vws);          // below this the wall would be thinner than a lattice cell
            p.thicknessOfPart[pi] = t;
            p.nu[pi] = (int) Math.max(1, Math.min(Math.ceil(maxU * 1.2 / (STEP * vws)), 1_000_000));
            p.nv[pi] = (int) Math.max(1, Math.min(Math.ceil(maxV * 1.2 / (STEP * vws)), 1_000_000));
            if (eval.isRoundedCube()) {
                // inside a rounded band the surface turns a quarter ellipse within a sliver of the (u,v) square: put lattice lines there
                p.uLines[pi] = cubeLines(eval, sc, parts[pi], 0, p.nu[pi], vws);
                p.vLines[pi] = cubeLines(eval, sc, parts[pi], 1, p.nv[pi], vws);
                p.nu[pi] = p.uLines[pi].length - 1;
                p.nv[pi] = p.vLines[pi].length - 1;
            }
            p.nd[pi] = (int) Math.min(1_000_000, Math.ceil(t / (STEP * vws))); // at least 2: t is not below 1.2 cells
            cells += (long) p.nu[pi] * p.nv[pi] * p.nd[pi];
        }
        // the voxels start at the lower corner of the surface - and a slab, grown to both sides of its sheet, at the lower face of the slab
        p.anchor = lo.clone();
        if (flat) p.anchor[1] -= wall / 2;
        for (int d = 0; d < 3; d++) { lo[d] -= wall; hi[d] += wall; }
        p.cells = cells;
        p.gridMax = gridMaxOf(lo, hi, vws);
        return p;
    }

    /**
     * The lattice lines of one direction (0 = u, 1 = v) of a rounded cube's face: evenly spaced, plus a dense run inside each of the two
     * bands (the first and last `smoothness of that axis` of the range) where the surface turns round the edge. The run is long
     * enough that no cell of it is longer than STEP voxels along the arc, a quarter of an ellipse no longer than 1.6 times its
     * largest radius.
     */
    private static double[] cubeLines(ShapeEval eval, double[] sc, int part, int slot, int evenCells, double vws) {
        double[] s = eval.smoothness();
        int axis = ShapeEval.cubeFaceAxes(part)[slot];
        double band = Math.min(0.5, Math.max(0.0, s[axis]));
        double rMax = Math.max(s[0] * Math.abs(sc[0]), Math.max(s[1] * Math.abs(sc[1]), s[2] * Math.abs(sc[2])));
        int dense = (int) Math.max(2, Math.min(400, Math.ceil(1.2 * 1.6 * rMax / (STEP * vws))));
        double[] raw = new double[evenCells + 1 + 2 * (dense + 1)];
        int n = 0;
        for (int i = 0; i <= evenCells; i++) raw[n++] = (double) i / evenCells;
        for (int i = 0; i <= dense; i++) {
            double f = band * i / dense;
            raw[n++] = f;
            raw[n++] = 1.0 - f;
        }
        java.util.Arrays.sort(raw, 0, n);
        double[] out = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            double v = Math.min(1.0, Math.max(0.0, raw[i]));
            if (m == 0 || v - out[m - 1] > 1e-9) out[m++] = v;
        }
        if (m < 2) return new double[]{0.0, 1.0};
        out[0] = 0.0;
        out[m - 1] = 1.0;
        return java.util.Arrays.copyOf(out, m);
    }

    /** How far across the object a wall of this part grows, in metres: the extent along the part's own normal direction. */
    private static double crossExtent(PbdInstance inst, int part, double[] sc) {
        return switch (inst.type) {
            case "cylinder" -> part == 0 ? Math.min(sc[0], sc[2]) : sc[1];
            case "cone" -> part == 0 ? Math.min(sc[0], sc[2]) : sc[1];
            case "cube" -> switch (part) { case 0, 1 -> sc[0]; case 2, 3 -> sc[1]; default -> sc[2]; };
            default -> Math.min(sc[0], Math.min(sc[1], sc[2]));
        };
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static boolean finite(double[] v) {
        return Double.isFinite(v[0]) && Double.isFinite(v[1]) && Double.isFinite(v[2]);
    }

    /** The canonical point c through the modifier chain, then the instance scale: where it stands in the object's local frame, in metres. */
    private static void map(ShapeEval eval, double[] sc, double[] c, double[] out) {
        out[0] = c[0]; out[1] = c[1]; out[2] = c[2];
        eval.map(out);
        out[0] *= sc[0]; out[1] *= sc[1]; out[2] *= sc[2];
    }

    // ------------------------------------------------------------------
    // Accumulation
    // ------------------------------------------------------------------

    /** The 3D lattice of a solid. Returns the longest edge of any cell's image, in voxels (more than 1 means the lattice was too coarse). */
    private static void accumulateSolid(PbdInstance inst, ShapeEval eval, Plan plan, double vws, VoxelMap map, Stats stats) {
        double[] sc = scaleOf(inst);
        ShapeVolume vol = ShapeVolume.of(inst);
        int nx = plan.nx, ny = plan.ny, nz = plan.nz;
        double[][] layer0 = new double[(nx + 1) * (ny + 1)][3], layer1 = new double[(nx + 1) * (ny + 1)][3];
        boolean[] in0 = new boolean[(nx + 1) * (ny + 1)], in1 = new boolean[(nx + 1) * (ny + 1)];
        fillSolidLayer(vol, eval, sc, nx, ny, nz, 0, layer0, in0);
        double[][] p = new double[8][];
        double invV = 1.0 / vws;
        double cellVolume3 = vws * vws * vws;
        Splatter splat = new Splatter(plan.anchor, invV);
        for (int k = 0; k < nz; k++) {
            fillSolidLayer(vol, eval, sc, nx, ny, nz, k + 1, layer1, in1);
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int a = j * (nx + 1) + i, b = a + 1, cc = a + (nx + 1), d = cc + 1;
                    int inside = (in0[a] ? 1 : 0) + (in0[b] ? 1 : 0) + (in0[cc] ? 1 : 0) + (in0[d] ? 1 : 0)
                        + (in1[a] ? 1 : 0) + (in1[b] ? 1 : 0) + (in1[cc] ? 1 : 0) + (in1[d] ? 1 : 0);
                    if (inside == 0) continue; // completely outside: contributes nothing (a canonical sliver thinner than a cell can be missed; cells are < 1 voxel)
                    double fraction = inside == 8 ? 1.0 : partFraction(vol, nx, ny, nz, i, j, k);
                    if (fraction <= 0) continue;
                    p[0] = layer0[a]; p[1] = layer0[b]; p[2] = layer0[cc]; p[3] = layer0[d];
                    p[4] = layer1[a]; p[5] = layer1[b]; p[6] = layer1[cc]; p[7] = layer1[d];
                    double volume = hexVolume(p);
                    stats.cell(edgeLength(p) * invV, fraction * volume / cellVolume3);
                    if (!(volume > 0 && volume < Double.POSITIVE_INFINITY)) continue;
                    splat.deposit(map, p, fraction * volume / cellVolume3);
                }
            }
            double[][] t = layer0; layer0 = layer1; layer1 = t;
            boolean[] tb = in0; in0 = in1; in1 = tb;
        }
    }

    private static void fillSolidLayer(ShapeVolume vol, ShapeEval eval, double[] sc, int nx, int ny, int nz, int k, double[][] out, boolean[] inside) {
        double[] c = new double[3];
        for (int j = 0; j <= ny; j++) for (int i = 0; i <= nx; i++) {
            c[0] = -0.5 + (double) i / nx;
            c[1] = -0.5 + (double) j / ny;
            c[2] = -0.5 + (double) k / nz;
            inside[j * (nx + 1) + i] = vol.inside(c[0], c[1], c[2]);
            map(eval, sc, c, out[j * (nx + 1) + i]);
        }
    }

    /** The part of a lattice cell (the canonical box of cell i,j,k) that lies inside the canonical shape, estimated on a 4x4x4 sub-lattice. */
    private static double partFraction(ShapeVolume vol, int nx, int ny, int nz, int i, int j, int k) {
        int n = 4, in = 0;
        double[] c = new double[3];
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int d = 0; d < n; d++) {
            c[0] = -0.5 + (i + (a + 0.5) / n) / nx;
            c[1] = -0.5 + (j + (b + 0.5) / n) / ny;
            c[2] = -0.5 + (k + (d + 0.5) / n) / nz;
            if (vol.inside(c[0], c[1], c[2])) in++;
        }
        return (double) in / (n * n * n);
    }

    /**
     * The shell lattice: every drawn part, grown inward (or both ways for a slab). Each part is accumulated on its own and a voxel's
     * coverage is the largest any part gave it: the parts overlap where they meet (the walls of a box in its corners, the wall and the
     * cap of a cylinder at its rim), and the sum of two walls that both cover the same quarter of a voxel is a quarter, not a half.
     */
    private static void accumulateShell(PbdInstance inst, ShapeEval eval, Plan plan, double vws, VoxelMap map, Stats stats) {
        double[] sc = scaleOf(inst);
        double invV = 1.0 / vws;
        double cellVolume3 = vws * vws * vws;
        Splatter splat = new Splatter(plan.anchor, invV);
        for (int pi = 0; pi < plan.parts.length; pi++) {
            VoxelMap own = new VoxelMap(1 << 12);
            int part = plan.parts[pi];
            int nu = plan.nu[pi], nv = plan.nv[pi], nd = plan.nd[pi];
            double[] us = plan.us(pi), vs = plan.vs(pi);
            double t = plan.thicknessOfPart[pi];
            double[][] row0 = new double[nu + 1][6], row1 = new double[nu + 1][6];
            fillSurfaceRow(eval, sc, part, us, vs[0], row0);
            double[][] p = new double[8][3];
            for (int j = 0; j < nv; j++) {
                fillSurfaceRow(eval, sc, part, us, vs[j + 1], row1);
                for (int i = 0; i < nu; i++) {
                    for (int k = 0; k < nd; k++) {
                        double d0 = plan.symmetric ? (-0.5 + (double) k / nd) * t : (double) k / nd * t;
                        double d1 = plan.symmetric ? (-0.5 + (double) (k + 1) / nd) * t : (double) (k + 1) / nd * t;
                        corner(p[0], row0[i], d0); corner(p[1], row0[i + 1], d0); corner(p[2], row1[i], d0); corner(p[3], row1[i + 1], d0);
                        corner(p[4], row0[i], d1); corner(p[5], row0[i + 1], d1); corner(p[6], row1[i], d1); corner(p[7], row1[i + 1], d1);
                        double volume = hexVolume(p);
                        stats.cell(edgeLength(p) * invV, volume / cellVolume3);
                        if (!(volume > 0 && volume < Double.POSITIVE_INFINITY)) continue;
                        splat.deposit(own, p, volume / cellVolume3);
                    }
                }
                double[][] swap = row0; row0 = row1; row1 = swap;
            }
            own.mergeMaxInto(map);
        }
    }

    /** Inward-grown corner: surface point (row[0..2]) minus the unit normal (row[3..5]) times depth d. */
    private static void corner(double[] out, double[] node, double d) {
        out[0] = node[0] - node[3] * d;
        out[1] = node[1] - node[4] * d;
        out[2] = node[2] - node[5] * d;
    }

    /**
     * The surface points (metres, local frame) and unit outward normals of row j of a part's lattice. The normal is the geometric one
     * of the scaled, modified surface - the cross product of its two tangents - oriented like the shader's own normal, which is
     * right in sign but only approximate in direction under a bend of a non-uniformly scaled shape; where the tangents degenerate (an apex)
     * the shader's normal, taken to the frame through the inverse of the scale, stands in.
     */
    private static void fillSurfaceRow(ShapeEval eval, double[] sc, int part, double[] us, double v, double[][] out) {
        double[] pos = new double[3], nrm = new double[3], q = new double[3], r = new double[3], tu = new double[3], tv = new double[3];
        double eps = 1e-4;
        for (int i = 0; i < us.length; i++) {
            double u = us[i];
            eval.eval(part, u, v, pos, nrm);
            double px = pos[0] * sc[0], py = pos[1] * sc[1], pz = pos[2] * sc[2];
            // the shader normal brought to the frame of the scaled object: the inverse transpose of the scale, diag(1/sx, 1/sy, 1/sz), times the
            // positive factor sx*sy*sz - which is diag(sy*sz, sx*sz, sx*sy): the same direction, and still defined when a scale is zero (a plane or
            // a disc flattened on Y has the normal of its flat axis; dividing by that scale gave NaN at the centre of a disc and no voxel at all)
            double wx = nrm[0] * sc[1] * sc[2], wy = nrm[1] * sc[0] * sc[2], wz = nrm[2] * sc[0] * sc[1];
            double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl > 0) { wx /= wl; wy /= wl; wz /= wl; }
            eval.eval(part, u + eps, v, q, r); double ux = q[0] * sc[0], uy = q[1] * sc[1], uz = q[2] * sc[2];
            eval.eval(part, u - eps, v, q, r); tu[0] = ux - q[0] * sc[0]; tu[1] = uy - q[1] * sc[1]; tu[2] = uz - q[2] * sc[2];
            eval.eval(part, u, v + eps, q, r); double vx = q[0] * sc[0], vy = q[1] * sc[1], vz = q[2] * sc[2];
            eval.eval(part, u, v - eps, q, r); tv[0] = vx - q[0] * sc[0]; tv[1] = vy - q[1] * sc[1]; tv[2] = vz - q[2] * sc[2];
            double nx = tu[1] * tv[2] - tu[2] * tv[1], ny = tu[2] * tv[0] - tu[0] * tv[2], nz = tu[0] * tv[1] - tu[1] * tv[0];
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double tul = Math.sqrt(tu[0] * tu[0] + tu[1] * tu[1] + tu[2] * tu[2]), tvl = Math.sqrt(tv[0] * tv[0] + tv[1] * tv[1] + tv[2] * tv[2]);
            if (nl > 1e-6 * tul * tvl && nl > 1e-18) {
                nx /= nl; ny /= nl; nz /= nl;
                if (nx * wx + ny * wy + nz * wz < 0) { nx = -nx; ny = -ny; nz = -nz; }
            } else {
                nx = wx; ny = wy; nz = wz;
            }
            double[] o = out[i];
            o[0] = px; o[1] = py; o[2] = pz; o[3] = nx; o[4] = ny; o[5] = nz;
        }
    }

    // ------------------------------------------------------------------
    // Cell geometry
    // ------------------------------------------------------------------

    /** The volume of a hexahedral cell (corners indexed by bits x=1, y=2, z=4): the triple product of its three mean edge vectors. */
    private static double hexVolume(double[][] p) {
        double ax = (p[1][0] + p[3][0] + p[5][0] + p[7][0] - p[0][0] - p[2][0] - p[4][0] - p[6][0]) / 4.0;
        double ay = (p[1][1] + p[3][1] + p[5][1] + p[7][1] - p[0][1] - p[2][1] - p[4][1] - p[6][1]) / 4.0;
        double az = (p[1][2] + p[3][2] + p[5][2] + p[7][2] - p[0][2] - p[2][2] - p[4][2] - p[6][2]) / 4.0;
        double bx = (p[2][0] + p[3][0] + p[6][0] + p[7][0] - p[0][0] - p[1][0] - p[4][0] - p[5][0]) / 4.0;
        double by = (p[2][1] + p[3][1] + p[6][1] + p[7][1] - p[0][1] - p[1][1] - p[4][1] - p[5][1]) / 4.0;
        double bz = (p[2][2] + p[3][2] + p[6][2] + p[7][2] - p[0][2] - p[1][2] - p[4][2] - p[5][2]) / 4.0;
        double cx = (p[4][0] + p[5][0] + p[6][0] + p[7][0] - p[0][0] - p[1][0] - p[2][0] - p[3][0]) / 4.0;
        double cy = (p[4][1] + p[5][1] + p[6][1] + p[7][1] - p[0][1] - p[1][1] - p[2][1] - p[3][1]) / 4.0;
        double cz = (p[4][2] + p[5][2] + p[6][2] + p[7][2] - p[0][2] - p[1][2] - p[2][2] - p[3][2]) / 4.0;
        return Math.abs(ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx));
    }

    /** The longest of the cell's twelve edges. */
    private static double edgeLength(double[][] p) {
        double m = 0;
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) {
                    double dx = p[i][0] - p[i | bit][0], dy = p[i][1] - p[i | bit][1], dz = p[i][2] - p[i | bit][2];
                    m = Math.max(m, dx * dx + dy * dy + dz * dz);
                }
            }
        }
        return Math.sqrt(m);
    }

    // ------------------------------------------------------------------
    // From coverage to an octree
    // ------------------------------------------------------------------

    private static final int KEY_BIAS = 1 << 20;

    /** A voxel of the lattice (indices counted from the anchor) as one long. */
    private static long pack(long ix, long iy, long iz) {
        return ((ix + KEY_BIAS) << 42) | ((iy + KEY_BIAS) << 21) | (iz + KEY_BIAS);
    }

    /** A cell's volume is spread over at most this many voxels per axis; a longer cell (a fold of the surface) goes whole into the voxel of its middle. */
    private static final int MAX_SPAN = 4;
    /** Voxels farther than this from the anchor are not part of any shape that fits the grid: a NaN or an overflow in disguise, dropped. */
    private static final long MAX_INDEX = 1L << 19;
    /** A cell narrower than this (in voxels) on an axis lies in one voxel of it. */
    private static final double FLAT = 1e-9;

    /**
     * Hands a lattice cell's volume (in voxels) to the voxels its bounding box overlaps, each getting the share of the box it covers.
     * Binning the cell whole into the voxel of its centre made a boundary voxel's coverage depend on how the cells happened to fall on the
     * voxel grid - between 0.6 and 2 times the truth - so that one row of a flat face was filled and the next not. Spread like this the
     * coverage is exact for the cells of a face parallel to an axis; for a slanted or curved one the box is a little wider than the cell,
     * which smooths the boundary by less than a cell.
     */
    private static final class Splatter {
        private final double ax, ay, az, invV;

        Splatter(double[] anchor, double invV) {
            this.ax = anchor[0];
            this.ay = anchor[1];
            this.az = anchor[2];
            this.invV = invV;
        }

        void deposit(VoxelMap map, double[][] corners, double volume) {
            double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            for (double[] q : corners) {
                double x = (q[0] - ax) * invV, y = (q[1] - ay) * invV, z = (q[2] - az) * invV;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                if (z < z0) z0 = z;
                if (z > z1) z1 = z;
            }
            if (!(x1 - x0 <= MAX_SPAN)) { x0 = x1 = (x0 + x1) / 2; } // too long to spread (or not a number): whole into the voxel of its middle
            if (!(y1 - y0 <= MAX_SPAN)) { y0 = y1 = (y0 + y1) / 2; }
            if (!(z1 - z0 <= MAX_SPAN)) { z0 = z1 = (z0 + z1) / 2; }
            if (!(Math.abs(x0) < MAX_INDEX && Math.abs(x1) < MAX_INDEX && Math.abs(y0) < MAX_INDEX && Math.abs(y1) < MAX_INDEX
                && Math.abs(z0) < MAX_INDEX && Math.abs(z1) < MAX_INDEX)) return;
            double wx = x1 - x0, wy = y1 - y0, wz = z1 - z0;
            long i1 = (long) Math.floor(x1), j1 = (long) Math.floor(y1), k1 = (long) Math.floor(z1);
            long j0 = (long) Math.floor(y0), k0 = (long) Math.floor(z0);
            for (long i = (long) Math.floor(x0); i <= i1; i++) {
                double fx = wx < FLAT ? 1.0 : (Math.min(x1, i + 1) - Math.max(x0, i)) / wx;
                if (!(fx > 0)) continue;
                double vx = volume * fx;
                for (long j = j0; j <= j1; j++) {
                    double fy = wy < FLAT ? 1.0 : (Math.min(y1, j + 1) - Math.max(y0, j)) / wy;
                    if (!(fy > 0)) continue;
                    double vxy = vx * fy;
                    for (long k = k0; k <= k1; k++) {
                        double fz = wz < FLAT ? 1.0 : (Math.min(z1, k + 1) - Math.max(z0, k)) / wz;
                        if (fz > 0) map.add(pack(i, j, k), (float) (vxy * fz));
                    }
                }
            }
        }
    }

    private static PrimitiveVoxelizer.Result buildResult(PbdInstance inst, VoxelMap map, double vws, boolean solid, double[] anchor) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int filled = 0;
        for (int s = 0; s < map.capacity(); s++) {
            if (!map.used(s) || map.value(s) < FILL_THRESHOLD) continue;
            long k = map.key(s);
            int[] v = unkey(k);
            for (int d = 0; d < 3; d++) { min[d] = Math.min(min[d], v[d]); max[d] = Math.max(max[d], v[d]); }
            filled++;
        }
        if (filled == 0) return null; // nothing reached half a voxel: a shape thinner than the voxels asked for
        int gx = max[0] - min[0] + 1, gy = max[1] - min[1] + 1, gz = max[2] - min[2] + 1;
        int gridMax = Math.max(gx, Math.max(gy, gz));
        int depth = 1;
        while ((1 << depth) < gridMax && depth < PrimitiveVoxelizer.MAX_OCTREE_DEPTH) depth++;
        VoxelOctree octree = new VoxelOctree(depth);
        int size = octree.gridSize();
        int offX = (size - gx) / 2, offY = (size - gy) / 2, offZ = (size - gz) / 2;
        java.util.BitSet filledBits = solid && (long) gx * gy * gz < Integer.MAX_VALUE ? new java.util.BitSet(gx * gy * gz) : null;
        for (int s = 0; s < map.capacity(); s++) {
            if (!map.used(s) || map.value(s) < FILL_THRESHOLD) continue;
            int[] v = unkey(map.key(s));
            octree.insert(offX + v[0] - min[0], offY + v[1] - min[1], offZ + v[2] - min[2]);
            if (filledBits != null) filledBits.set(((v[0] - min[0]) * gy + (v[1] - min[1])) * gz + (v[2] - min[2]));
        }
        if (filledBits != null) {
            int[] holes = VoxelCavities.enclosedEmpty(filledBits, gx, gy, gz);
            if (holes != null) {
                for (int index : holes) {
                    int z = index % gz, y = (index / gz) % gy, x = index / (gz * gy);
                    octree.insert(offX + x, offY + y, offZ + z);
                }
            }
        }
        org.joml.Vector3f origin = new org.joml.Vector3f(
            (float) ((min[0] - offX) * vws + anchor[0]), (float) ((min[1] - offY) * vws + anchor[1]), (float) ((min[2] - offZ) * vws + anchor[2]));
        return new PrimitiveVoxelizer.Result(octree, (float) vws, origin, inst);
    }

    private static int[] unkey(long k) {
        return new int[]{(int) ((k >>> 42) & 0x1FFFFF) - KEY_BIAS, (int) ((k >>> 21) & 0x1FFFFF) - KEY_BIAS, (int) (k & 0x1FFFFF) - KEY_BIAS};
    }

    /** long -> float summing hash map (open addressing): the coverage each voxel has received. */
    private static final class VoxelMap {
        private long[] keys;
        private float[] values;
        private boolean[] used;
        private int size, mask;

        VoxelMap(int expected) {
            int cap = 16;
            while (cap < expected * 2L) cap <<= 1;
            keys = new long[cap]; values = new float[cap]; used = new boolean[cap]; mask = cap - 1;
        }

        int capacity() { return keys.length; }
        boolean used(int slot) { return used[slot]; }
        long key(int slot) { return keys[slot]; }
        float value(int slot) { return values[slot]; }

        private static int hash(long k) {
            k ^= (k >>> 33); k *= 0xff51afd7ed558ccdL; k ^= (k >>> 33); k *= 0xc4ceb9fe1a85ec53L; k ^= (k >>> 33);
            return (int) k;
        }

        void add(long key, float v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) { values[i] += v; return; }
                i = (i + 1) & mask;
            }
            used[i] = true; keys[i] = key; values[i] = v; size++;
        }

        /** Every voxel of this map into {@code target}, keeping the larger of the two values where both have one. */
        void mergeMaxInto(VoxelMap target) {
            for (int i = 0; i < keys.length; i++) if (used[i]) target.max(keys[i], values[i]);
        }

        private void max(long key, float v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = hash(key) & mask;
            while (used[i]) {
                if (keys[i] == key) { if (v > values[i]) values[i] = v; return; }
                i = (i + 1) & mask;
            }
            used[i] = true; keys[i] = key; values[i] = v; size++;
        }

        private void grow() {
            long[] ok = keys; float[] ov = values; boolean[] ou = used;
            int cap = ok.length << 1;
            keys = new long[cap]; values = new float[cap]; used = new boolean[cap]; mask = cap - 1; size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) add(ok[i], ov[i]);
        }
    }
}
