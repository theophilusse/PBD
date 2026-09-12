package pbd.format;

import java.util.Map;

/**
 * "Raw" representation of a modifier: the parser just extracts the
 * key=value pairs from the block without interpreting their meaning
 * (axis, angle, curve reference...). That interpretation is left to
 * whoever consumes the scene (the future GPU buffer builder, or the
 * viewer) - the parser stays agnostic to the semantics of each modifier
 * type, which avoids having to touch it every time a new modifier is
 * introduced.
 */
public final class PbdModifier {
    public final String type;              // "bend", "taper", "twist", "curve"... validated via ModifierRegistry
    public final Map<String, String> params;

    public PbdModifier(String type, Map<String, String> params) {
        this.type = type;
        this.params = params;
    }

    public String get(String key) {
        return params.get(key);
    }

    public float getFloat(String key, float fallback) {
        String v = params.get(key);
        return v == null ? fallback : Float.parseFloat(v);
    }
}
