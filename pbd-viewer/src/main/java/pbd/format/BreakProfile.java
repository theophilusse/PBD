package pbd.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * How a MATERIAL breaks: the part of a {@code .pbdmat} block that tells the destruction system what a hit on a thing made
 * of it throws up and when a piece comes away whole. It is defined by the material - never by the metadata of a primitive -
 * so every primitive made of "oak" breaks like oak, and one {@code .pbdmat} line retunes them all.
 *
 * <pre>
 * material oak {
 *     color=(0.45, 0.28, 0.13)
 *     breakWidth=0.03          # thinnest connection that still holds a piece (metres)
 *     debrisCount=160          # particles thrown up by a full-size crater
 *     breakCount=60            # particles thrown up when a piece parts
 *     debrisSize=0.022         # mean edge of a particle (metres)
 *     debrisSpeed=3            # mean ejection speed (metres per second)
 *     debrisLifetime=4         # mean time a particle lives (seconds)
 *     debrisGravity=1          # multiple of gravity
 *     debrisBounce=0.25        # 0 = a particle stays where it lands, 0.95 = it nearly keeps its speed
 *     debrisColor=(0.4,0.3,0.2) # tint of the particles; absent = the colour of the surface that broke
 *     breakSound=wood-crack.wav # played when a piece parts (a file of resources/sounds)
 * }
 * </pre>
 *
 * Every key is optional. What the file leaves out comes from the material's name (wood, metal and plastic have a profile of
 * their own; any other name gets the generic one), so a {@code .pbdmat} that only sets {@code color} still breaks sensibly.
 * A value that cannot be read (not a number, outside its range, a colour that is not three numbers) is NOT guessed at: the
 * default of that key is used and the problem is listed in {@link #warnings}, which the loader prints once. A key that begins
 * with {@code break} or {@code debris} but is not one of the above is reported too, because a typo ({@code debrisCout})
 * would otherwise quietly leave the default in place.
 *
 * Plain data, no GL and no engine types: it is parsed from the raw strings {@code PbdScene.materialOverrides} holds.
 */
public final class BreakProfile {

    // ------------------------------------------------------------------ the keys

    public static final String KEY_BREAK_WIDTH = "breakWidth";
    public static final String KEY_DEBRIS_COUNT = "debrisCount";
    public static final String KEY_BREAK_COUNT = "breakCount";
    public static final String KEY_DEBRIS_SIZE = "debrisSize";
    public static final String KEY_DEBRIS_SPEED = "debrisSpeed";
    public static final String KEY_DEBRIS_LIFETIME = "debrisLifetime";
    public static final String KEY_DEBRIS_GRAVITY = "debrisGravity";
    public static final String KEY_DEBRIS_BOUNCE = "debrisBounce";
    public static final String KEY_DEBRIS_COLOR = "debrisColor";
    public static final String KEY_BREAK_SOUND = "breakSound";

    /** Every key this class reads, in the order the documentation lists them. */
    public static final List<String> KEYS = List.of(KEY_BREAK_WIDTH, KEY_DEBRIS_COUNT, KEY_BREAK_COUNT, KEY_DEBRIS_SIZE,
        KEY_DEBRIS_SPEED, KEY_DEBRIS_LIFETIME, KEY_DEBRIS_GRAVITY, KEY_DEBRIS_BOUNCE, KEY_DEBRIS_COLOR, KEY_BREAK_SOUND);

    // ------------------------------------------------------------------ the ranges (a value outside is refused, not clamped)

    /** 0 turns the "attachment too small" rule off for the material (rubber, say). */
    public static final float MAX_BREAK_WIDTH = 1.0f;
    /** Particles per burst. The particle system holds {@code ParticleSystem.MAX_PARTICLES} in all. */
    public static final int MAX_COUNT = 400;
    public static final float MIN_SIZE = 0.001f, MAX_SIZE = 0.25f;
    public static final float MAX_SPEED = 30f;
    public static final float MIN_LIFETIME = 0.1f, MAX_LIFETIME = 30f;
    public static final float MIN_GRAVITY = -1f, MAX_GRAVITY = 5f;
    public static final float MAX_BOUNCE = 0.95f;

    // ------------------------------------------------------------------ the profile

    /** Thinnest connection (world units) that still holds a piece to the rest; a piece joined by less comes away whole. 0 = never. */
    public final float breakWidth;
    /** Particles thrown up by a crater of the nominal size (smaller craters throw proportionally fewer). */
    public final int debrisCount;
    /** Particles thrown up when a piece parts. */
    public final int breakCount;
    public final float debrisSize;
    public final float debrisSpeed;
    public final float debrisLifetime;
    public final float debrisGravity;
    public final float debrisBounce;
    /** Particle tint {r,g,b}, or null: the particles take the colour of the surface that broke. */
    public final float[] debrisColor;
    /** File name (in resources/sounds) played when a piece parts, or null. */
    public final String breakSound;
    /** What could not be read in the fields this profile was made from (empty for a clean material). */
    public final List<String> warnings;

    private BreakProfile(float breakWidth, int debrisCount, int breakCount, float debrisSize, float debrisSpeed,
                         float debrisLifetime, float debrisGravity, float debrisBounce, float[] debrisColor,
                         String breakSound, List<String> warnings) {
        this.breakWidth = breakWidth;
        this.debrisCount = debrisCount;
        this.breakCount = breakCount;
        this.debrisSize = debrisSize;
        this.debrisSpeed = debrisSpeed;
        this.debrisLifetime = debrisLifetime;
        this.debrisGravity = debrisGravity;
        this.debrisBounce = debrisBounce;
        this.debrisColor = debrisColor;
        this.breakSound = breakSound;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /** The profile of a material nobody described: what {@link #defaultsFor} gives an unknown name. */
    public static final BreakProfile GENERIC = defaultsFor(null);

    /**
     * What a material of this name breaks like when its {@code .pbdmat} block says nothing. Wood splinters in many
     * chunky pieces that barely bounce and parts easily; metal throws fewer, smaller, livelier fragments and holds on
     * to much thinner connections; plastic sits between. Any other name (or null) gets the generic profile.
     */
    public static BreakProfile defaultsFor(String materialName) {
        List<String> none = new ArrayList<>();
        if (materialName == null) materialName = "";
        switch (materialName) { // exact names, like MaterialCatalog.lookup: "Wood" is not "wood" there either
            case "wood":
                return new BreakProfile(0.030f, 160, 60, 0.022f, 3.0f, 4.0f, 1.0f, 0.25f, null, null, none);
            case "metal":
                return new BreakProfile(0.010f, 100, 45, 0.014f, 4.5f, 3.0f, 1.0f, 0.45f, null, null, none);
            case "plastic":
                return new BreakProfile(0.015f, 140, 55, 0.018f, 3.8f, 3.5f, 1.0f, 0.40f, null, null, none);
            default:
                return new BreakProfile(0.020f, 120, 50, 0.020f, 3.5f, 3.5f, 1.0f, 0.35f, null, null, none);
        }
    }

    /**
     * The profile of material {@code materialName} given the raw fields of its {@code .pbdmat} block (null or empty = none).
     * Keys missing or unreadable take the value {@link #defaultsFor} gives that name.
     */
    public static BreakProfile of(String materialName, Map<String, String> fields) {
        BreakProfile base = defaultsFor(materialName);
        if (fields == null || fields.isEmpty()) return base;
        List<String> warnings = new ArrayList<>();
        String label = materialName == null ? "material" : "material '" + materialName + "'";

        float breakWidth = readFloat(fields, KEY_BREAK_WIDTH, 0f, MAX_BREAK_WIDTH, base.breakWidth, label, warnings);
        int debrisCount = readCount(fields, KEY_DEBRIS_COUNT, base.debrisCount, label, warnings);
        int breakCount = readCount(fields, KEY_BREAK_COUNT, base.breakCount, label, warnings);
        float debrisSize = readFloat(fields, KEY_DEBRIS_SIZE, MIN_SIZE, MAX_SIZE, base.debrisSize, label, warnings);
        float debrisSpeed = readFloat(fields, KEY_DEBRIS_SPEED, 0f, MAX_SPEED, base.debrisSpeed, label, warnings);
        float debrisLifetime = readFloat(fields, KEY_DEBRIS_LIFETIME, MIN_LIFETIME, MAX_LIFETIME, base.debrisLifetime, label, warnings);
        float debrisGravity = readFloat(fields, KEY_DEBRIS_GRAVITY, MIN_GRAVITY, MAX_GRAVITY, base.debrisGravity, label, warnings);
        float debrisBounce = readFloat(fields, KEY_DEBRIS_BOUNCE, 0f, MAX_BOUNCE, base.debrisBounce, label, warnings);
        float[] debrisColor = base.debrisColor;
        String rawColor = fields.get(KEY_DEBRIS_COLOR);
        if (rawColor != null) {
            float[] parsed = parseColor(rawColor);
            if (parsed == null) {
                warnings.add(label + ": " + KEY_DEBRIS_COLOR + "='" + rawColor + "' is not a colour (three numbers from 0 to 1, like (0.4, 0.3, 0.2)) - the particles take the colour of the surface");
            } else {
                debrisColor = parsed;
            }
        }
        String breakSound = base.breakSound;
        String rawSound = fields.get(KEY_BREAK_SOUND);
        if (rawSound != null) {
            String s = rawSound.trim();
            if (s.isEmpty()) {
                warnings.add(label + ": " + KEY_BREAK_SOUND + " is empty - no sound");
            } else {
                breakSound = s;
            }
        }

        for (String key : fields.keySet()) {
            if ((key.startsWith("break") || key.startsWith("debris")) && !KEYS.contains(key)) {
                warnings.add(label + ": unknown key '" + key + "' (the keys that shape a break are " + String.join(", ", KEYS) + ")");
            }
        }
        return new BreakProfile(breakWidth, debrisCount, breakCount, debrisSize, debrisSpeed, debrisLifetime, debrisGravity,
            debrisBounce, debrisColor, breakSound, warnings);
    }

    // ------------------------------------------------------------------ reading

    private static float readFloat(Map<String, String> fields, String key, float min, float max, float fallback,
                                   String label, List<String> warnings) {
        String raw = fields.get(key);
        if (raw == null) return fallback;
        Float v = parseFinite(raw);
        if (v == null) {
            warnings.add(label + ": " + key + "='" + raw + "' is not a number - using " + fallback);
            return fallback;
        }
        if (v < min || v > max) {
            warnings.add(label + ": " + key + "=" + raw.trim() + " is outside " + min + " .. " + max + " - using " + fallback);
            return fallback;
        }
        return v;
    }

    private static int readCount(Map<String, String> fields, String key, int fallback, String label, List<String> warnings) {
        String raw = fields.get(key);
        if (raw == null) return fallback;
        Float v = parseFinite(raw);
        if (v == null || v != Math.rint(v)) {
            warnings.add(label + ": " + key + "='" + raw + "' is not a whole number - using " + fallback);
            return fallback;
        }
        if (v < 0 || v > MAX_COUNT) {
            warnings.add(label + ": " + key + "=" + raw.trim() + " is outside 0 .. " + MAX_COUNT + " - using " + fallback);
            return fallback;
        }
        return Math.round(v);
    }

    /** A decimal number (an optional sign, digits, a point, an exponent) - not NaN, not an infinity, not a hex float. Null otherwise. */
    static Float parseFinite(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
            if (!ok) return null;
        }
        try {
            float v = Float.parseFloat(s);
            return Float.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "(r, g, b)" or "r, g, b" with each component in 0..1; null when it is anything else. */
    static float[] parseColor(String raw) {
        String s = raw.trim();
        if (s.startsWith("(")) {
            if (!s.endsWith(")")) return null;
            s = s.substring(1, s.length() - 1);
        }
        String[] parts = s.split(",", -1);
        if (parts.length != 3) return null;
        float[] rgb = new float[3];
        for (int i = 0; i < 3; i++) {
            Float v = parseFinite(parts[i]);
            if (v == null || v < 0f || v > 1f) return null;
            rgb[i] = v;
        }
        return rgb;
    }

    // ------------------------------------------------------------------ for the preload list

    /** The sound a material parts with, or null: what the loader preloads (see PbdScene.soundFiles). */
    public static String breakSoundOf(Map<String, String> fields) {
        if (fields == null) return null;
        String raw = fields.get(KEY_BREAK_SOUND);
        if (raw == null || raw.trim().isEmpty()) return null;
        return raw.trim();
    }
}
