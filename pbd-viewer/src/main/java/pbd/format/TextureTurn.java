package pbd.format;

/**
 * Turns a picture by quarter turns: the whole of the engine's "UV rotation" (the material key {@code uvRotation}, see {@link MaterialUv}).
 *
 * <p><b>Why the pixels are turned, and not the coordinates.</b> A material's texture is read in four places that must agree to the
 * texel - the tessellation shader (a layer of a texture array, read at {@code vUV * uvScale}), the classic mesh renderer (a texture
 * and a {@code uvScale} uniform), the voxels a shot object is cut into ({@link pbd.voxel.VoxelTextureMapper}) and the triangles of a
 * shot mesh ({@link pbd.voxel.MeshTextureMapper}). Turning the coordinates would mean the same turn written into a shader, a uniform
 * and two CPU mappers; turning the picture once, where it is loaded, and giving every reader the turned picture, makes it impossible for
 * them to differ. For a quarter turn the two are the same thing: the texture repeats, so turning the unit square about its corner or
 * about its middle gives the same tiling, and the scale ({@code uvScale}) stays what it was - repeats along the SURFACE's u and v -
 * because the picture is turned inside the tile it fills, not the tile on the surface. (This is also what Blender's Mapping node does:
 * it scales, then rotates, so the preview in Blender is the same picture.)
 *
 * <p><b>The direction.</b> {@code quarterTurns = 1} turns the picture a quarter turn <i>counter-clockwise</i> as it is seen with u to
 * the right and v up (what a picture editor calls "rotate 90 degrees left"): what was at the right edge is now at the top, what was at
 * the top is at the left. 2 is upside down; 3 is a quarter turn clockwise. The pixels are rows from the BOTTOM (row 0 is v = 0 - the
 * engine loads its textures flipped), and a picture that is not square changes shape: width and height swap for 1 and 3.
 *
 * <p>Written as the coordinate rotation it equals ({@code q = R(-90 k deg) (u, v)}, the texture read at q), the turned picture satisfies
 * {@code turned(u, v) = original(q)}; RegressionTextureRotation checks that against {@link pbd.voxel.VoxelTextureMapper#texel}.
 */
public final class TextureTurn {
    private TextureTurn() {}

    /** A picture of RGBA pixels, 4 bytes each, row 0 at the bottom. */
    public record Image(byte[] rgba, int width, int height) {}

    /** The number of quarter turns brought into 0..3 (any int, any sign: -1 is 3). */
    public static int normalize(int quarterTurns) {
        return ((quarterTurns % 4) + 4) % 4;
    }

    /**
     * The picture turned {@code quarterTurns} quarter turns counter-clockwise. A multiple of four returns the picture itself (no copy);
     * otherwise a new array - the original is never changed.
     *
     * @throws IllegalArgumentException when the array is too short for the size it is said to have, or the size is not positive
     */
    public static Image rotate(byte[] rgba, int width, int height, int quarterTurns) {
        // (compared as pixels, not bytes: width x height x 4 does not fit a long when both are near the top of the int range)
        if (width <= 0 || height <= 0 || rgba == null || (long) width * height > rgba.length / 4L) {
            throw new IllegalArgumentException("a picture of " + width + " x " + height + " needs 4 bytes a pixel, and "
                + (rgba == null ? "there is no array" : "the array holds " + rgba.length + " bytes"));
        }
        int k = normalize(quarterTurns);
        if (k == 0) return new Image(rgba, width, height);
        int outW = (k == 2) ? width : height;
        int outH = (k == 2) ? height : width;
        byte[] out = new byte[outW * outH * 4];
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx, sy;                // where in the original this pixel of the turned picture comes from
                switch (k) {
                    case 1:  sx = y;             sy = height - 1 - x; break; // counter-clockwise: the right edge goes to the top
                    case 2:  sx = width - 1 - x; sy = height - 1 - y; break;
                    default: sx = width - 1 - y; sy = x;              break; // clockwise: the left edge goes to the top
                }
                System.arraycopy(rgba, (sy * width + sx) * 4, out, (y * outW + x) * 4, 4);
            }
        }
        return new Image(out, outW, outH);
    }

    public static Image rotate(Image image, int quarterTurns) {
        return rotate(image.rgba(), image.width(), image.height(), quarterTurns);
    }
}
