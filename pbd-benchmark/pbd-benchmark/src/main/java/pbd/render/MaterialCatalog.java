package pbd.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Visual properties for the built-in test materials. This is deliberately
 * separate from pbd.format.MaterialRegistry: the registry only tracks
 * name <-> id (a format-level, engine-agnostic concern - it doesn't know
 * or care what a material looks like), while this catalog is what turns a
 * known name into an actual GPU-side appearance. A name with no entry here
 * (a user typed `mat=stone` in a .pbd file, say) still gets a valid id from
 * the registry and falls back to DEFAULT here rather than failing.
 */
public final class MaterialCatalog {

    public static final class Entry {
        public final float r, g, b;
        public final float shininess;        // Blinn-Phong exponent: low = matte, high = tight highlight
        public final float specularStrength; // 0 = no highlight, 1 = strong

        public Entry(float r, float g, float b, float shininess, float specularStrength) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.shininess = shininess;
            this.specularStrength = specularStrength;
        }
    }

    public static final Entry DEFAULT = new Entry(0.65f, 0.65f, 0.68f, 8f, 0.1f);

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    static {
        // Warm matte brown: low shininess/specular so light spreads out
        // instead of forming a sharp highlight.
        ENTRIES.put("wood", new Entry(0.45f, 0.28f, 0.13f, 6f, 0.08f));
        // Cool grey with a tight, strong highlight - the combination that
        // reads as "metal" even under a single directional light with no
        // environment reflection.
        ENTRIES.put("metal", new Entry(0.62f, 0.63f, 0.66f, 96f, 0.9f));
        // Saturated color with a softer, less intense highlight than metal.
        ENTRIES.put("plastic", new Entry(0.75f, 0.15f, 0.12f, 32f, 0.45f));
    }

    private MaterialCatalog() {}

    public static Entry lookup(String name) {
        return ENTRIES.getOrDefault(name, DEFAULT);
    }
}
