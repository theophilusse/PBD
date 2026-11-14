package pbd.format;

import java.util.List;
import java.util.Map;

/**
 * The UV scale of a material, read the one way every consumer of a {@code .pbdmat} block shares: the tessellation shader's material
 * buffer, the classic mesh renderer, and the voxels a shot object is made of. They must agree, or a texture would tile one way on the
 * whole object and another on its pieces.
 *
 * <ul>
 *   <li>{@code uvScale = n} sets both axes; {@code uvScaleU = n} and {@code uvScaleV = n} override one axis each, whatever order the
 *       keys are written in (the single key is applied first).</li>
 *   <li>The texture coordinate is multiplied by the scale and the texture repeats, so a scale of 4 shows the picture four times across
 *       a face - and a <b>negative scale mirrors that axis</b>: {@code uvScaleU = -1} shows the picture flipped left to right,
 *       {@code uvScaleV = -2} upside down, twice. This is the one way the format says "mirror the texture" (the Blender add-on's
 *       Mirror U / Mirror V buttons just change the sign).</li>
 *   <li>A value that is not a finite number is ignored (the axis keeps what it had) and reported, never thrown: a typo in a
 *       material must not stop a scene from loading.</li>
 *   <li>{@code uvRotation = 90} turns the picture a quarter turn counter-clockwise on the surface (as seen with u to the right and v up),
 *       {@code 180} upside down, {@code 270} (or {@code -90}) a quarter turn clockwise: degrees, <b>multiples of 90 only</b>, because
 *       the picture is turned once, where it is loaded ({@link TextureTurn}), and read by the shader, the classic mesh renderer and the
 *       voxels exactly as it was - a turn of any other angle would need the shader to do it. The scale stays what it says: it counts
 *       repeats along the surface's u and v, whatever the picture's turn; a mirror (negative scale) happens before the turn, as in
 *       Blender's Mapping node. Any other value is ignored (no turn) and reported, like a scale that is not a number.</li>
 * </ul>
 */
public final class MaterialUv {
    private MaterialUv() {}

    /** The scale on each axis of the texture. */
    public record Scale(float u, float v) {
        public static final Scale IDENTITY = new Scale(1f, 1f);
    }

    /**
     * The scale a block of fields asks for (1, 1 for none, or for a block of null). Every field that cannot be read is named in
     * {@code problems} (when it is not null) and otherwise ignored.
     */
    public static Scale of(Map<String, String> fields, List<String> problems) {
        float u = 1f, v = 1f;
        if (fields == null) return Scale.IDENTITY;
        Float both = read(fields, "uvScale", problems);
        if (both != null) u = v = both;
        Float onlyU = read(fields, "uvScaleU", problems);
        if (onlyU != null) u = onlyU;
        Float onlyV = read(fields, "uvScaleV", problems);
        if (onlyV != null) v = onlyV;
        return new Scale(u, v);
    }

    /**
     * The number of quarter turns (counter-clockwise, 0 to 3) a block of fields asks for with {@code uvRotation} (degrees; 0 for none, for
     * a block of null, and for a value that is not a multiple of 90 - reported in {@code problems}, when it is not null, and ignored). A
     * value within a thousandth of a degree of a multiple of 90 is that multiple (an angle that went through a float on its way here).
     */
    public static int quarterTurns(Map<String, String> fields, List<String> problems) {
        if (fields == null) return 0;
        String raw = fields.get(ROTATION_KEY);
        if (raw == null) return 0;
        double degrees;
        try {
            degrees = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            degrees = Double.NaN;
        }
        if (!Double.isFinite(degrees)) {
            if (problems != null) problems.add(ROTATION_KEY + " = '" + raw + "' is not a number: ignored");
            return 0;
        }
        double turns = Math.rint(degrees / 90.0);
        if (Math.abs(turns) > MAX_TURNS) {
            if (problems != null) problems.add(ROTATION_KEY + " = '" + raw + "' is far too large for a rotation: ignored (the picture is not turned)");
            return 0;
        }
        if (Math.abs(degrees - turns * 90.0) > ROTATION_TOLERANCE_DEGREES) {
            if (problems != null) problems.add(ROTATION_KEY + " = '" + raw + "' is not a multiple of 90 degrees: ignored (the picture is not turned)");
            return 0;
        }
        return (int) Math.floorMod((long) turns, 4L);
    }

    /** The material key that holds the rotation of the picture, in degrees. */
    public static final String ROTATION_KEY = "uvRotation";
    /** How far from a multiple of 90 a rotation may be and still be that multiple. */
    static final double ROTATION_TOLERANCE_DEGREES = 1e-3;
    /** A rotation of more turns than this is a typo, not a rotation (and a double this large no longer counts exactly). */
    private static final double MAX_TURNS = 1e12;

    private static Float read(Map<String, String> fields, String key, List<String> problems) {
        String raw = fields.get(key);
        if (raw == null) return null;
        try {
            float value = Float.parseFloat(raw.trim());
            if (Float.isFinite(value)) return value;
        } catch (NumberFormatException e) {
            // reported below
        }
        if (problems != null) problems.add(key + " = '" + raw + "' is not a number: ignored");
        return null;
    }
}
