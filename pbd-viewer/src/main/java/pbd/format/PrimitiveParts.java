package pbd.format;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which PARTS (patches) a primitive is made of, and the numbers of its cube roundness - the facts about a primitive's SHAPE
 * that depend on its parameters rather than on its type alone, in one place so that what is drawn and what is voxelized
 * cannot disagree: {@code pbd.render.PatchExpander} (what the GPU draws) and {@code pbd.voxel} (what a G hit turns into
 * cubes) both read them here.
 *
 * Part numbers are pbd.tese's own:
 * <ul>
 *   <li>cylinder: 0 wall, 1 top disc (+Y), 2 bottom disc (-Y); {@code cap=both|top|bottom|none} chooses the discs;</li>
 *   <li>cone: 0 wall, 1 base disc (-Y); {@code cap=none} drops the base (any other value keeps it);</li>
 *   <li>cube: 0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z; {@code faces=(+x,-y,...)} lists the faces that are REMOVED;</li>
 *   <li>plane, sphere, disc, torus: one part, 0.</li>
 * </ul>
 */
public final class PrimitiveParts {
    private PrimitiveParts() {}

    /** The cube's faces in part-number order. */
    public static final String[] CUBE_FACE_CODES = {"+x", "-x", "+y", "-y", "+z", "-z"};

    /**
     * The part numbers an instance is drawn with. Empty for a metadata marker (no geometry whatever its type), for mesh (drawn
     * through the classic mesh path), group, light and every other instance with no surface of its own. Throws
     * IllegalArgumentException for a type that has no patch layout - add it here and in pbd.tese at the same time.
     */
    public static int[] of(PbdInstance inst) {
        if ("true".equals(inst.params.get("metadata"))) {
            return new int[]{};
        }
        return switch (inst.type) {
            case "plane", "sphere", "disc", "torus" -> new int[]{0};
            case "cone" -> coneParts(inst);
            case "cylinder" -> cylinderParts(inst);
            case "cube" -> cubeParts(inst);
            // "mesh" is raw triangulated geometry (see PbdMeshData), rendered entirely through the classic-mesh path, never
            // through the tessellation pipeline - zero GPU patches here, same as "group" (a pbd_ref anchor) and "light" (read
            // directly by PbdRenderer.uploadLights). A new type needs a case here even if its geometry comes from elsewhere.
            case "mesh", "group", "light" -> new int[]{};
            default -> throw new IllegalArgumentException(
                "Unknown patch layout for type: " + inst.type
                + " (add it here and in pbd.tese at the same time)");
        };
    }

    private static int[] coneParts(PbdInstance inst) {
        String cap = inst.params.getOrDefault("cap", "both");
        return "none".equals(cap) ? new int[]{0} : new int[]{0, 1};
    }

    private static int[] cylinderParts(PbdInstance inst) {
        String cap = inst.params.getOrDefault("cap", "both");
        boolean keepTop = !"none".equals(cap) && !"bottom".equals(cap);
        boolean keepBottom = !"none".equals(cap) && !"top".equals(cap);

        List<Integer> parts = new ArrayList<>(3);
        parts.add(0); // wall always present
        if (keepTop) parts.add(1);
        if (keepBottom) parts.add(2);

        int[] result = new int[parts.size()];
        for (int i = 0; i < result.length; i++) result[i] = parts.get(i);
        return result;
    }

    /**
     * The faces a cube keeps. {@code faces} lists the REMOVED ones as canonical codes, parenthesized like every multi-value field
     * of the format (a bare comma is structural to the parser): {@code faces=(-y,+z)} keeps the other four. Absent or blank keeps
     * all six, exactly like every file written before the parameter existed. The codes are the ones the Blender add-on writes.
     */
    private static int[] cubeParts(PbdInstance inst) {
        String facesParam = inst.params.get("faces");
        if (facesParam == null || facesParam.isBlank()) {
            return new int[]{0, 1, 2, 3, 4, 5};
        }
        String inner = facesParam.trim();
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        Set<String> removed = new HashSet<>();
        for (String token : inner.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) removed.add(t);
        }
        List<Integer> kept = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            if (!removed.contains(CUBE_FACE_CODES[i])) kept.add(i);
        }
        int[] result = new int[kept.size()];
        for (int i = 0; i < result.length; i++) result[i] = kept.get(i);
        return result;
    }

    /**
     * Whether the instance is drawn with every part its type can have - a CLOSED shape that encloses a volume (a sphere, a torus,
     * a cylinder with both caps, a cone with its base, a cube with six faces). A plane or a disc is a surface and never counts;
     * neither does an instance without a surface of its own (mesh, group, light, metadata).
     */
    public static boolean isClosedSolid(PbdInstance inst) {
        int[] parts = of(inst);
        return switch (inst.type) {
            case "sphere", "torus" -> parts.length == 1;
            case "cylinder" -> parts.length == 3;
            case "cone" -> parts.length == 2;
            case "cube" -> parts.length == 6;
            default -> false;
        };
    }

    /**
     * The roundness of a cube's edges per axis, each 0 (sharp) .. 0.5 (fully round): {@code smoothX/Y/Z}, each falling back to the
     * uniform {@code smooth} (default 0). The same three numbers the GPU gets as the instance's params0.xyz. A value that does not
     * parse as a number counts as 0; the shader clamps to 0..0.5 and so does this.
     */
    public static float[] cubeSmoothness(PbdInstance inst) {
        float base = parseOr(inst.params.get("smooth"), 0f);
        return new float[]{
            clamp(parseOr(inst.params.get("smoothX"), base)),
            clamp(parseOr(inst.params.get("smoothY"), base)),
            clamp(parseOr(inst.params.get("smoothZ"), base)),
        };
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 0.5f);
    }

    private static float parseOr(String raw, float fallback) {
        if (raw == null) return fallback;
        try {
            float f = Float.parseFloat(raw.trim());
            return Float.isFinite(f) ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
