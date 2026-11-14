package pbd.voxel;

import pbd.format.MaterialUv;
import pbd.format.PbdInstance;

/**
 * The colour of a voxel face from the texture of the object it was cut from, mapped the way the SHADER maps it.
 *
 * <p>On the GPU a primitive's texel is {@code texture(vUV * uvScale)}: vUV is the tessellation coordinate (u, v) of the patch
 * (the face of a cube, the wall or a cap of a cylinder...) the pixel belongs to, and the patch's own orientation decides which way the
 * picture runs on it. Those orientations are not uniform - the +X face of a cube runs u along -Z, the -Z face along -X, so that every
 * side face reads left to right seen from outside - and a voxel colouring that picks its own axes (the dominant axis, u = z on both X
 * faces) mirrors the picture on the faces where the two disagree: the handle of a locker that was on the left became a handle on the
 * right the moment the locker was shot.
 *
 * <p>So the mapping is the shader's, run backwards (everything pure, in double precision, nothing GL):
 * <ol>
 *   <li>a corner of a voxel face, in grid metres, is moved into the instance's own frame (the grid's corner is
 *       {@link PrimitiveVoxelizer.Result#gridOriginLocal}) and divided by the instance scale: a point of the canonical unit shape,
 *       after the modifiers;</li>
 *   <li>the modifiers are undone ({@link ShapeEval#unmap}: taper divided out, bend unrolled, shear unwound);</li>
 *   <li>{@link ShapeEval#locate} names the patch and the (u, v) of the surface point nearest to it - the same patch and (u, v) the
 *       shader had for the pixel that was drawn there (the face a voxel looks toward breaks the tie where a corner sits on an edge);</li>
 *   <li>(u, v) is multiplied by the material's UV scale - negative mirrors the axis (see {@link MaterialUv}) - and the texture is read at
 *       the texel it falls in, the texture repeating (GL_REPEAT) and its row 0 at the bottom (the engine loads textures flipped).</li>
 * </ol>
 * The wall of a crater inside the object has no pixel of its own on the GPU - the shader only draws the skin - so it takes the colour
 * of the skin nearest to it, which is the natural continuation of the picture into the hole.
 *
 * <p>Verified by RegressionTextureMapper against the same 1700 points of every primitive, with and without modifiers, that hold
 * {@link ShapeEval} to the GLSL (an independent port made from the shader's text).
 */
public final class VoxelTextureMapper {
    private final ShapeEval eval;
    private final double sx, sy, sz;
    private final double ox, oy, oz;
    private final double tieMetres;
    private final double voxelMetres;

    /** How far (in voxels) a face may be from the nearest one and still win the tie by looking the right way. */
    static final double TIE_VOXELS = 1.5;

    /**
     * How far (in voxels) a corner is moved toward the middle of its face before the texture is read there. A corner sits exactly on the
     * edge of its face, and with a texture that repeats the edge of a face is very often the edge of a tile (the top of a face is
     * v = 1, which the repeat wraps to 0, the BOTTOM row of the picture): read on the edge, the top corners of every face would take
     * the colour of the bottom of the picture. A thousandth of a voxel is far less than a texel and far more than the rounding of the
     * mesh's float coordinates.
     */
    public static final double INSIDE_VOXELS = 1e-3;

    private VoxelTextureMapper(ShapeEval eval, PbdInstance source, float[] originLocal, double voxelWorldSize) {
        this.eval = eval;
        this.sx = source.scale.x;
        this.sy = source.scale.y;
        this.sz = source.scale.z;
        this.ox = originLocal[0];
        this.oy = originLocal[1];
        this.oz = originLocal[2];
        this.tieMetres = TIE_VOXELS * voxelWorldSize;
        this.voxelMetres = voxelWorldSize;
    }

    /** The mapper of a voxelized primitive, or null when the instance has no analytic surface of its own (a mesh: its triangles carry their own UVs, see {@link MeshTextureMapper}). */
    public static VoxelTextureMapper of(PrimitiveVoxelizer.Result grid) {
        if (grid == null) return null;
        return of(grid.source, grid.gridOriginLocal, grid.voxelWorldSize);
    }

    /**
     * The same from its parts: the voxelized instance (its scale is the world scale the grid was built at), the corner of the grid in the
     * instance's frame ({@code gridOriginLocal}) and the voxel size.
     */
    public static VoxelTextureMapper of(PbdInstance source, org.joml.Vector3f gridOriginLocal, float voxelWorldSize) {
        if (source == null || gridOriginLocal == null) return null;
        // the surface the GPU's triangles cover: a rounded cube's flat band is stretched, not folded (see ShapeEval.ofShell; no widening)
        ShapeEval eval = ShapeEval.ofShell(source, 1e-9);
        if (eval == null) return null;
        return new VoxelTextureMapper(eval, source, new float[]{gridOriginLocal.x, gridOriginLocal.y, gridOriginLocal.z}, voxelWorldSize);
    }

    /**
     * The patch and tessellation coordinate the shader has at the point (gx, gy, gz) of the grid (metres from the grid's own corner), for
     * a face looking along (nx, ny, nz) - each -1, 0 or 1, all 0 for no face (a chip of debris). Null when the shape draws nothing.
     */
    public ShapeEval.Locus locusOf(double gx, double gy, double gz, int nx, int ny, int nz) {
        // a negative scale turns the shape over: the face that looks toward +X of the grid is the -X face of the canonical shape
        if (sx < 0) nx = -nx;
        if (sy < 0) ny = -ny;
        if (sz < 0) nz = -nz;
        double[] q = {divide(gx + ox, sx), divide(gy + oy, sy), divide(gz + oz, sz)};
        double[] target = q.clone();     // where the point is in the space the shader leaves the shape in (after the modifiers)
        if (!eval.unmap(q)) {
            // a taper that shrinks this height to nothing: the only point of the shape there is on the axis
            q[0] = 0.0;
            q[2] = 0.0;
        }
        ShapeEval.Locus guess = eval.locate(q, nx, ny, nz, tieMetres);
        if (guess == null || eval.locateIsNearest()) return guess;
        // the inverse of the shader's function is exact for a point on the surface; one off it (every corner of a voxel is, by up to a voxel) is
        // read where the surface is NEAREST it, which for a leaning wall, a rounded edge or a stretching modifier is not where the inverse falls.
        // A plane and a disc are no exception: their voxels are a slab around the sheet, a corner above or below it reads the point of the sheet
        // straight under it, and a taper (whose factor depends on the height) would otherwise be undone at the height of the CORNER, not of the sheet
        ShapeEval.Locus refined = eval.nearest(guess, target);
        if (nx == 0 && ny == 0 && nz == 0) return refined;
        // The look of the voxel face may choose a patch other than the nearest one, within the tie - in metres of the world, which a
        // shear or a bend stretches: the choice is checked here, where the distances are the real ones.
        ShapeEval.Locus plain = eval.locate(q, 0, 0, 0, tieMetres);
        if (plain == null || plain.part() == guess.part()) return refined;
        ShapeEval.Locus other = eval.nearest(plain, target);
        return eval.distanceMetres(refined, target) <= eval.distanceMetres(other, target) + tieMetres ? refined : other;
    }

    /**
     * The point to read the texture at for a corner of a face: the corner itself, moved {@link #INSIDE_VOXELS} toward the middle of the
     * face along each of the two axes the face spreads over (the face of the box {@code voxel} = {x, y, z, size in voxels} looking
     * along (nx, ny, nz)). A chip of debris (no face: nx = ny = nz = 0) is read where it is.
     */
    double[] insideTheFace(int[] voxel, float[] corner, int nx, int ny, int nz) {
        return nudgeInside(voxel, corner, nx, ny, nz, voxelMetres);
    }

    /** {@link #insideTheFace} for any mapper: the shift of a corner toward the middle of its face, for voxels of {@code voxelMetres}. */
    public static double[] nudgeInside(int[] voxel, float[] corner, int nx, int ny, int nz, double voxelMetres) {
        double[] at = {corner[0], corner[1], corner[2]};
        if (nx == 0 && ny == 0 && nz == 0) return at;
        int[] normal = {nx, ny, nz};
        int size = voxel.length > 3 ? voxel[3] : 1;
        for (int axis = 0; axis < 3; axis++) {
            if (normal[axis] != 0) continue;
            double middle = (voxel[axis] + 0.5 * size) * voxelMetres;
            at[axis] += Math.signum(middle - at[axis]) * INSIDE_VOXELS * voxelMetres;
        }
        return at;
    }

    private static double divide(double metres, double scale) {
        return Math.abs(scale) < 1e-12 ? 0.0 : metres / scale;
    }

    // ------------------------------------------------------------------
    // The texture
    // ------------------------------------------------------------------

    /**
     * The colour the GPU would read for the coordinate (u, v) of a texture of {@code width x height} RGBA pixels with its row 0 at the
     * bottom: (u, v) times the scale, wrapped (the texture repeats, whatever the sign of the scale), nearest texel. Components 0..1.
     */
    public static float[] texel(byte[] rgba, int width, int height, double u, double v, MaterialUv.Scale scale) {
        int px = wrapped(u * scale.u(), width);
        int py = wrapped(v * scale.v(), height);
        int at = (py * width + px) * 4;
        return new float[]{(rgba[at] & 0xFF) / 255f, (rgba[at + 1] & 0xFF) / 255f, (rgba[at + 2] & 0xFF) / 255f};
    }

    /** The index of the texel a repeating coordinate falls in (0 .. n-1). A coordinate that is not a number reads the first texel. */
    static int wrapped(double coordinate, int n) {
        if (!Double.isFinite(coordinate)) return 0;
        double fraction = coordinate - Math.floor(coordinate);
        int i = (int) Math.floor(fraction * n);
        return Math.max(0, Math.min(n - 1, i));
    }

    /**
     * The colour of the corners of the faces of a voxelized object, from the pixels of its material's texture: a primitive through the
     * shader's own mapping run backwards (this class), a mesh through the UVs of its own triangles ({@link MeshTextureMapper}).
     * {@code fallback} is the colour of a corner the shape draws nothing at, and of every corner of an object that cannot be mapped (a
     * mesh with no UVs).
     */
    public static VoxelMeshBuilder.FaceColor faceColor(PrimitiveVoxelizer.Result grid, byte[] rgba, int width, int height,
                                                       MaterialUv.Scale scale, float[] fallback) {
        if (grid != null && grid.source != null && "mesh".equals(grid.source.type)) {
            return MeshTextureMapper.faceColor(grid, rgba, width, height, scale, fallback);
        }
        VoxelTextureMapper mapper = of(grid);
        if (mapper == null || rgba == null || width <= 0 || height <= 0 || rgba.length < width * height * 4) {
            return VoxelMeshBuilder.ignoringFace((voxel, corner) -> fallback);
        }
        return (voxel, corner, nx, ny, nz) -> {
            int fx = Math.round(nx), fy = Math.round(ny), fz = Math.round(nz);
            double[] where = mapper.insideTheFace(voxel, corner, fx, fy, fz);
            ShapeEval.Locus at = mapper.locusOf(where[0], where[1], where[2], fx, fy, fz);
            return at == null ? fallback : texel(rgba, width, height, at.u(), at.v(), scale);
        };
    }
}
