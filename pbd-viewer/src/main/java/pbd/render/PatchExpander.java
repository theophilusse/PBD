package pbd.render;

import pbd.format.PbdInstance;
import pbd.format.PbdScene;
import pbd.format.PrimitiveParts;

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
        // One definition of which parts an instance has, shared with the voxelizer - see PrimitiveParts for the cap/faces rules
        // (a metadata marker contributes no geometry whatever its type; mesh, group and light are not drawn by this pipeline).
        return PrimitiveParts.of(inst);
    }
}
