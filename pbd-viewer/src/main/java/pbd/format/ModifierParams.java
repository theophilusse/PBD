package pbd.format;

import java.util.Locale;

/**
 * What the shader reads of a modifier - the ONE place that says which parameters each modifier has, what they default to, and how the
 * axis of a bend is spelt.
 *
 * <p>Three consumers need it, and they must agree to the last digit: the live renderer (the modifier buffer it uploads every frame), the
 * baked mesh cache (the same buffer, for geometry baked once) and the voxel conversion (ShapeEval, the Java port of the tessellation
 * shader, which turns a destroyed primitive into the voxels of the shape that was DRAWN). Each used to carry its own copy of these
 * defaults and of the axis lookup; a copy that drifts is a primitive that is drawn one way and shot at another. They all call this class
 * now, and RegressionShaderPort checks both that it gives the right numbers and that nothing else in the engine spells a modifier's
 * parameter names.
 *
 * <p>A value that cannot be read as a finite number counts as missing (the default applies): a hand-written file with
 * {@code angle = ninety} used to make {@code Float.parseFloat} throw in the middle of a frame.
 */
public final class ModifierParams {
    private ModifierParams() {}

    /** The axis a bend turns about, as the shader's number: "x" 0, "y" 1, "z" 2 (any case, spaces ignored); anything else, or none, is 0. */
    public static int axisIndex(String axis) {
        if (axis == null) return 0;
        return switch (axis.trim().toLowerCase(Locale.ROOT)) {
            case "y" -> 1;
            case "z" -> 2;
            default -> 0;
        };
    }

    /** A taper's scale at the -Y end of the shape; 1 (no change) when the modifier names none. */
    public static float bottomScale(PbdModifier taper) {
        return taper.getFloat("bottomScale", 1f);
    }

    /** A taper's scale at the +Y end of the shape; 1 (no change) when the modifier names none. */
    public static float topScale(PbdModifier taper) {
        return taper.getFloat("topScale", 1f);
    }

    /**
     * The four floats of a modifier's slot in the shader's buffer, in the order pbd.tese reads them:
     * <ul>
     *   <li>taper: bottomScale, topScale (both default 1), 0, 0;</li>
     *   <li>bend: angle in radians (default 0 = straight), the axis as {@link #axisIndex}, 0, 0;</li>
     *   <li>shear: factorX, factorY, factorZ (each default 0), 0;</li>
     *   <li>anything else (twist, curve - the GPU does nothing with them yet): angle (default 0), 0, 0, 0.</li>
     * </ul>
     */
    public static float[] vec4(PbdModifier mod) {
        return switch (mod.type) {
            case "taper" -> new float[]{bottomScale(mod), topScale(mod), 0f, 0f};
            case "bend" -> new float[]{mod.getFloat("angle", 0f), axisIndex(mod.get("axis")), 0f, 0f};
            case "shear" -> new float[]{mod.getFloat("factorX", 0f), mod.getFloat("factorY", 0f), mod.getFloat("factorZ", 0f), 0f};
            default -> new float[]{mod.getFloat("angle", 0f), 0f, 0f, 0f};
        };
    }
}
