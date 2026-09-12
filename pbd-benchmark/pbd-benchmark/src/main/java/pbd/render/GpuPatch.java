package pbd.render;

/**
 * A logical primitive (PbdInstance) doesn't always fit in a single
 * tessellation patch: a cylinder needs its wall + 2 caps, a cone its
 * wall + 1 cap, a cube one face per side. gl_PrimitiveID in the TES
 * indexes this list of patches, not the instances directly - so each
 * patch keeps a reference back to its source instance (same transform,
 * same modifiers) plus a `part` saying which portion to generate.
 */
public final class GpuPatch {
    public final int instanceIndex;
    public final int part;

    public GpuPatch(int instanceIndex, int part) {
        this.instanceIndex = instanceIndex;
        this.part = part;
    }
}
