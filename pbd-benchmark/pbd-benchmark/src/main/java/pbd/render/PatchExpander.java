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
            case "cube" -> new int[]{0, 1, 2, 3, 4, 5};
            case "group" -> new int[]{}; // pbd_ref anchor - no geometry, see PbdParser.parsePbdRef
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
}
