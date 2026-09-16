package pbd.render;

import pbd.format.PbdInstance;
import pbd.format.PbdScene;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a PbdScene (logical instances) into the flat list of patches
 * to submit as GL_PATCHES. The number of patches per type reflects the
 * actual geometric shape: a sphere or a plane fit in a single smooth
 * patch, but a cylinder/cone/cube have geometrically distinct parts
 * (walls vs. caps vs. faces) that don't share a continuous (u,v)
 * parameterization.
 *
 * Cylinder and cone read an optional `cap` param (via PbdInstance's
 * free-form params map, so no parser change was needed to support this):
 *   cylinder: cap = both (default) | top | bottom | none
 *   cone:     cap = both (default) | none
 * This only changes which part numbers get emitted here - pbd.tese's
 * switch on gpuPatch.part already handles any subset of 0/1/2 being
 * present, so no shader change was needed either. Cylinder's "top" means
 * +Y (the part 1 disc), "bottom" means -Y (part 2), matching the local Y
 * being the height axis for every canonical primitive in this format.
 */
public final class PatchExpander {

    public List<GpuPatch> expand(PbdScene scene) {
        List<GpuPatch> patches = new ArrayList<>();
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            for (int part : partsFor(inst)) {
                patches.add(new GpuPatch(i, part));
            }
        }
        return patches;
    }

    /**
     * Same per-type/cap logic as expand(), for a single instance considered
     * on its own (instanceIndex always 0) - used by PbdMeshCache when
     * baking one shape in isolation rather than a whole scene.
     */
    public static List<GpuPatch> partsForBaking(PbdInstance inst) {
        List<GpuPatch> patches = new ArrayList<>();
        for (int part : partsFor(inst)) {
            patches.add(new GpuPatch(0, part));
        }
        return patches;
    }

    private static int[] partsFor(PbdInstance inst) {
        // A metadata marker (metadata=true) - a storage-space volume for
        // later bin-packing, say - carries real position/scale like any
        // other instance, but contributes no geometry at all, regardless
        // of its actual type. Checked before the type switch so this
        // applies to a metadata cube exactly the same as a metadata
        // sphere or any other shape someone marks this way.
        if ("true".equals(inst.params.get("metadata"))) {
            return new int[]{};
        }
        return switch (inst.type) {
            case "plane", "sphere", "disc", "torus" -> new int[]{0};
            case "cone" -> coneParts(inst);
            case "cylinder" -> cylinderParts(inst);
            case "cube" -> cubeParts(inst);
            // "mesh" is raw triangulated geometry (see PbdMeshData),
            // rendered entirely through Main.java's classic-mesh path
            // (meshInstanceRenderers), never through this tessellation
            // pipeline - zero GPU patches here, same as "group". Omitting
            // this case entirely used to fall through to default's throw,
            // meaning ANY scene containing a mesh instance crashed the
            // moment PbdRenderer's constructor called expand() on it,
            // before a single frame ever rendered - found while wiring up
            // the rest of the mesh primitive's UI, not by exercising this
            // path directly, worth remembering next time a new type is
            // added: it needs a case here even if its geometry comes from
            // somewhere else entirely.
            case "mesh" -> new int[]{};
            case "group" -> new int[]{}; // pbd_ref anchor - no geometry, see PbdParser.parsePbdRef
            // A light source (see PbdInstance's own light* fields) - no
            // geometry of its own either, same category as "group"/
            // "mesh" just above. Missing here originally despite the
            // "mesh" case's own comment explicitly warning about this
            // exact mistake - PbdRenderer.uploadLights reads a light
            // instance's fields directly (position, color, etc.), never
            // through a GPU patch, so this instance contributes nothing
            // to the tessellation pipeline at all.
            case "light" -> new int[]{};
            default -> throw new IllegalArgumentException(
                "Unknown patch layout for type: " + inst.type
                + " (add it here and in pbd.tese at the same time)");
        };
    }

    private static int[] coneParts(PbdInstance inst) {
        String cap = inst.params.getOrDefault("cap", "both");
        return "none".equals(cap) ? new int[]{0} : new int[]{0, 1};
    }

    /**
     * Cube reads an optional `faces` param (same free-form params map as
     * cap, no parser change needed - see PbdParser's default case) listing
     * REMOVED faces as canonical codes, parenthesized like every other
     * multi-value field in this format (pos/scale/rot) since a bare
     * unquoted comma is structural to the parser and would truncate the
     * value at the first one: `faces=(-y,+z)` removes the -Y and +Z faces,
     * keeping the other four. Absent (the common case) keeps all six,
     * exactly matching every .pbd file written before this existed.
     *
     * Codes map directly to pbd.tese's evalCubeFaceFlat part numbers:
     * +x=0 -x=1 +y=2(top) -y=3(bottom) +z=4 -z=5 - this order matters,
     * it's also what the Blender add-on's faceCodeToCanonicalPart uses on
     * the way out, so a round-trip through both never has to guess.
     */
    private static int[] cubeParts(PbdInstance inst) {
        String[] codes = {"+x", "-x", "+y", "-y", "+z", "-z"};
        String facesParam = inst.params.get("faces");
        if (facesParam == null || facesParam.isBlank()) {
            return new int[]{0, 1, 2, 3, 4, 5};
        }
        String inner = facesParam.trim();
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (String token : inner.split(",")) {
            String t = token.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) removed.add(t);
        }
        java.util.List<Integer> kept = new java.util.ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            if (!removed.contains(codes[i])) kept.add(i);
        }
        int[] result = new int[kept.size()];
        for (int i = 0; i < result.length; i++) result[i] = kept.get(i);
        return result;
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
}
