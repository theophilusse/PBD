package pbd.format;

import java.util.List;
import java.util.Locale;

/**
 * Writes a PbdScene back to .pbd text - pretty (indented, one field per
 * line, easy to read and diff) or minified (every separator trimmed to
 * the minimum, one line per instance) from the same underlying data, so
 * a file can round-trip parse -> serialize -> parse without loss and be
 * reformatted either way on demand.
 *
 * Not a general-purpose formatter for ARBITRARY hand-written .pbd text
 * (comments, unusual whitespace, and field order in the original file
 * aren't preserved - this always emits fields in a fixed, canonical
 * order): it serializes the PARSED representation, which is exactly
 * what "minify" and "prettify" both actually need - a canonical,
 * regenerated rendering of the same data, not a text-level reflow of
 * the original file.
 */
public final class PbdSerializer {

    private final boolean pretty;

    public PbdSerializer(boolean pretty) {
        this.pretty = pretty;
    }

    public String serialize(PbdScene scene) {
        StringBuilder sb = new StringBuilder();
        String nl = pretty ? "\n" : " ";
        String sp = pretty ? " " : "";

        sb.append("pbd_version").append(' ').append('1').append(nl);
        if (scene.name != null) sb.append("name").append(sp).append('=').append(sp).append(quoteMetadataValue(scene.name)).append(nl);
        if (scene.kind != null) sb.append("kind").append(sp).append('=').append(sp).append(quoteMetadataValue(scene.kind)).append(nl);
        for (String author : scene.authors) {
            sb.append("author").append(sp).append('=').append(sp).append(quoteMetadataValue(author)).append(nl);
        }
        if (scene.origin != null) sb.append("origin").append(sp).append('=').append(sp).append(quoteMetadataValue(scene.origin)).append(nl);
        if (scene.description != null) sb.append("description").append(sp).append('=').append(sp).append(quoteMetadataValue(scene.description)).append(nl);
        // include_material uses "key value" (space-separated, no "="),
        // matching how it's actually written - see PbdParser's
        // parseIncludeMaterial, which reads the path via readRawValue()
        // directly after the keyword with no expect('=') in between.
        // Round-tripping this was the actual .pbdbin materials bug: a
        // re-serialized scene silently dropped the reference entirely,
        // since nothing here ever wrote it back out even though
        // PbdScene had it available - materialOverrides itself (the
        // ALREADY-PARSED field data) isn't re-emitted at all, on
        // purpose: it's a resolved read-only cache for consumers like
        // PbdRenderer, not this format's source of truth for material
        // data, which stays the referenced .pbdmat file itself.
        // EVERY include, in file order (a scene may list several - see
        // PbdScene.includeMaterialPaths), and quoted when the path has a
        // space: the parser reads this value as ONE token, so an unquoted
        // "my assets/a.pbdmat" came back as "my" and the file no longer
        // opened.
        List<String> includes = !scene.includeMaterialPaths.isEmpty()
            ? scene.includeMaterialPaths
            : (scene.includeMaterialPath != null ? List.of(scene.includeMaterialPath) : List.<String>of());
        for (String include : includes) {
            sb.append("include_material").append(' ').append(valueToken(include)).append(nl);
        }

        // Curves were never written, so every format that goes through
        // this serializer (.pbdbin, minify, the Blender add-on's
        // conversions) silently dropped them - and a curve modifier then
        // pointed at nothing.
        for (PbdCurve curve : scene.curves.values()) {
            serializeCurve(sb, curve, nl, sp);
        }

        for (PbdInstance inst : scene.instances) {
            serializeInstance(sb, inst, nl, sp);
        }
        return sb.toString();
    }

    private void serializeCurve(StringBuilder sb, PbdCurve curve, String nl, String sp) {
        String indent = pretty ? "  " : "";
        sb.append("curve ").append(curve.id).append(sp).append('{').append(nl);
        sb.append(indent).append("kind").append(sp).append('=').append(sp).append(valueToken(curve.kind)).append(nl);
        sb.append(indent).append("points").append(sp).append('=').append(sp).append('[');
        for (int i = 0; i < curve.points.size(); i++) {
            if (i > 0) sb.append(',').append(sp);
            sb.append(vec3(curve.points.get(i)));
        }
        sb.append(']').append(nl);
        sb.append('}').append(nl).append(nl);
    }

    private void serializeInstance(StringBuilder sb, PbdInstance inst, String nl, String sp) {
        String indent = pretty ? "  " : "";
        sb.append(inst.type).append(' ').append(inst.id).append(sp).append('{').append(nl);

        if (inst.type.equals("ref") || inst.type.equals("group")) {
            String source = inst.params.get("source");
            if (source != null) sb.append(indent).append("source").append(sp).append('=').append(sp).append(valueToken(source)).append(nl);
        }
        if (inst.parentId != null) sb.append(indent).append("parent").append(sp).append('=').append(sp).append(valueToken(inst.parentId)).append(nl);
        if (inst.lod != null) sb.append(indent).append("lod").append(sp).append('=').append(sp).append(inst.lod).append(nl);
        if (inst.category != null) sb.append(indent).append("category").append(sp).append('=').append(sp).append(valueToken(inst.category)).append(nl);
        if (inst.indestructible) sb.append(indent).append("indestructible").append(sp).append('=').append(sp).append("true").append(nl);
        if (inst.hardness != null) sb.append(indent).append("hardness").append(sp).append('=').append(sp).append(inst.hardness).append(nl);
        if (inst.resistance != null) sb.append(indent).append("resistance").append(sp).append('=').append(sp).append(inst.resistance).append(nl);
        if (inst.lightMode != null) {
            sb.append(indent).append("lightMode").append(sp).append('=').append(sp).append(inst.lightMode).append(nl);
            if (!inst.lightEnabled) sb.append(indent).append("lightEnabled").append(sp).append('=').append(sp).append("false").append(nl);
            if (inst.lightColor != null) sb.append(indent).append("lightColor").append(sp).append('=').append(sp).append(vec3(inst.lightColor)).append(nl);
            if (inst.lightIntensity != null) sb.append(indent).append("lightIntensity").append(sp).append('=').append(sp).append(inst.lightIntensity).append(nl);
            if (inst.lightRange != null) sb.append(indent).append("lightRange").append(sp).append('=').append(sp).append(inst.lightRange).append(nl);
            if (inst.lightSpotAngleDeg != null) sb.append(indent).append("lightSpotAngle").append(sp).append('=').append(sp).append(inst.lightSpotAngleDeg).append(nl);
        }

        sb.append(indent).append("pos").append(sp).append('=').append(sp).append(vec3(inst.position)).append(nl);
        if (inst.rotationDeg.x != 0 || inst.rotationDeg.y != 0 || inst.rotationDeg.z != 0) {
            sb.append(indent).append("rot").append(sp).append('=').append(sp).append(vec3(inst.rotationDeg)).append(nl);
        }
        sb.append(indent).append("scale").append(sp).append('=').append(sp).append(vec3(inst.scale)).append(nl);
        if (inst.material != null) sb.append(indent).append("mat").append(sp).append('=').append(sp).append(valueToken(inst.material)).append(nl);
        if (inst.meshData != null) {
            sb.append(indent).append("vertexData").append(sp).append('=').append(sp)
                .append('"').append("base64:").append(inst.meshData.toBase64()).append('"').append(nl);
        }
        for (var lodEntry : inst.meshDataByLod.entrySet()) {
            sb.append(indent).append("vertexDataLod").append(lodEntry.getKey()).append(sp).append('=').append(sp)
                .append('"').append("base64:").append(lodEntry.getValue().toBase64()).append('"').append(nl);
        }

        for (var e : inst.params.entrySet()) {
            if (e.getKey().equals("source")) continue; // already emitted above, ref/group only
            sb.append(indent).append(e.getKey()).append(sp).append('=').append(sp).append(valueToken(e.getValue())).append(nl);
        }
        for (String trigger : inst.containerTriggers) {
            sb.append(indent).append("containerTrigger").append(sp).append('=').append(sp).append(valueToken(trigger)).append(nl);
        }

        for (PbdModifier mod : inst.modifiers) {
            sb.append(indent).append("modifier").append(' ').append(mod.type).append(sp).append('{').append(sp);
            for (var e : mod.params.entrySet()) {
                sb.append(e.getKey()).append('=').append(valueToken(e.getValue())).append(' ');
            }
            sb.append('}').append(nl);
        }

        for (PbdInstance.Keyframe kf : inst.keyframes) {
            sb.append(indent).append("keyframe").append(sp).append('{').append(sp);
            sb.append("time").append('=').append(formatNumber(kf.time)).append(' ');
            if (kf.channel != null) sb.append("channel").append('=').append(valueToken(kf.channel)).append(' ');
            if (kf.pos != null) sb.append("pos").append('=').append(vec3(kf.pos)).append(' ');
            if (kf.rotDeg != null) sb.append("rot").append('=').append(vec3(kf.rotDeg)).append(' ');
            if (kf.scale != null) sb.append("scale").append('=').append(vec3(kf.scale)).append(' ');
            if (kf.sound != null) sb.append("sound").append('=').append(quoteToken(kf.sound)).append(' ');
            sb.append('}').append(nl);
        }

        if (inst.leverArm != null) {
            PbdInstance.LeverArm arm = inst.leverArm;
            sb.append(indent).append("leverArm").append(sp).append('{').append(sp);
            if (arm.openPos != null) sb.append("openPos").append('=').append(vec3(arm.openPos)).append(' ');
            if (arm.openRotDeg != null) sb.append("openRot").append('=').append(vec3(arm.openRotDeg)).append(' ');
            sb.append("speed").append('=').append(formatNumber(arm.speed)).append(' ');
            // Only what differs from the defaults is written, so a file
            // that never used these fields round-trips byte-for-byte the
            // way it did before they existed.
            if (arm.release != PbdInstance.LeverArm.Release.SNAP) sb.append("release").append('=').append(arm.release.keyword()).append(' ');
            if (arm.locked) sb.append("locked").append('=').append("true").append(' ');
            if (arm.lockAt != 0f) sb.append("lockAt").append('=').append(formatNumber(arm.lockAt)).append(' ');
            if (arm.openSound != null) sb.append("openSound").append('=').append(quoteToken(arm.openSound)).append(' ');
            if (arm.closeSound != null) sb.append("closeSound").append('=').append(quoteToken(arm.closeSound)).append(' ');
            sb.append('}').append(nl);
        }

        sb.append('}').append(nl).append(nl);
    }

    private String vec3(org.joml.Vector3f v) {
        return "(" + formatNumber(v.x) + "," + (pretty ? " " : "") + formatNumber(v.y) + "," + (pretty ? " " : "") + formatNumber(v.z) + ")";
    }

    private String formatNumber(float f) {
        if (f == Math.round(f)) return String.valueOf(Math.round(f));
        return String.format(Locale.ROOT, "%.6g", f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String quoteIfNeeded(String s) {
        // '#' too: an unquoted metadata value is read up to the next '#'
        // (a comment), so "Wardrobe #2" came back as "Wardrobe".
        return s.contains(" ") || s.contains("\t") || s.contains("#") || s.isEmpty() ? "\"" + s + "\"" : s;
    }

    /** For a single-token value inside a "key=value key=value" block (a
     * keyframe's or a leverArm's sound file): bare when the parser's
     * readRawValue would read it back as one token, quoted when it would
     * not - whitespace, a structural character, a comment marker or
     * nothing at all. Without this a file name with a space (door
     * creak.wav) was written bare and read back as "door" followed by a
     * stray word that broke the block. */
    private static String quoteToken(String s) {
        boolean bare = !s.isEmpty();
        for (int i = 0; i < s.length() && bare; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '#' || c == '"' || "{}()[]=,".indexOf(c) >= 0) bare = false;
        }
        return bare ? s : "\"" + s + "\"";
    }

    /**
     * A free-form value (mat, parent, category, source, any extension
     * field, a modifier parameter, a trigger name...) written so the
     * parser's readRawValue hands back EXACTLY the string we hold: bare
     * when it reads back as one token, a balanced "(...)" or "[...]" group
     * left as it is (that is how a vector or a list is written - the parser
     * reads those to the matching bracket, spaces and all), and quoted
     * otherwise - whitespace, '=', ',', braces, '#'. Before this only a few
     * fields were quoted, so linkGroup = "front doors" came back from a
     * .pbdbin as `linkGroup = front doors` and the file no longer parsed.
     * A value that itself contains a double quote cannot be written at all
     * (the format has no escape): see the format spec.
     */
    static String valueToken(String s) {
        return isBalancedGroup(s) ? s : quoteToken(s);
    }

    /** "(a, b)" or "[(0,0,0), (1,1,1)]": starts with ( or [, and its FIRST bracket closes at the very last character. */
    private static boolean isBalancedGroup(String s) {
        if (s.length() < 2) return false;
        char open = s.charAt(0);
        char close = open == '(' ? ')' : open == '[' ? ']' : 0;
        if (close == 0 || s.charAt(s.length() - 1) != close) return false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') return false;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0 && i != s.length() - 1) return false; // closes early: the rest would be read as another token
            }
        }
        return depth == 0;
    }

    /** quoteIfNeeded's own "only if it contains a space" rule is only
     * safe in PRETTY mode, where each field ends at a real newline
     * regardless of whether it's quoted. Minified mode's own "nl" is
     * just a single space (see this method's own field above) - there
     * is no newline ANYWHERE in a fully minified file, so an unquoted
     * metadata value (read back via PbdParser's own readMetadataValue,
     * which reads an unquoted value to the next \n or end of input)
     * would swallow not just the rest of its own line but the ENTIRE
     * REST OF THE FILE, every instance in it included. Caught by a
     * real reproduction: a pbd_ref pointing at a .pbdbin - always
     * minified - lost every one of its sub-scene's instances, because
     * author=A (one letter, no space, so quoteIfNeeded alone saw
     * nothing needing quotes) had consumed the entire remainder of the
     * file as its own value. So in minified mode this ALWAYS quotes,
     * regardless of content - the one thing pretty mode's own rule
     * gets to skip precisely because a real newline is always there to
     * fall back on instead. */
    private String quoteMetadataValue(String s) {
        return pretty ? quoteIfNeeded(s) : "\"" + s + "\"";
    }
}
