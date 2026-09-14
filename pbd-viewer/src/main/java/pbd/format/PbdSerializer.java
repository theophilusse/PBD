package pbd.format;

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
        if (scene.name != null) sb.append("name").append(sp).append('=').append(sp).append(quoteIfNeeded(scene.name)).append(nl);
        if (scene.kind != null) sb.append("kind").append(sp).append('=').append(sp).append(scene.kind).append(nl);
        for (String author : scene.authors) {
            sb.append("author").append(sp).append('=').append(sp).append(quoteIfNeeded(author)).append(nl);
        }
        if (scene.origin != null) sb.append("origin").append(sp).append('=').append(sp).append(quoteIfNeeded(scene.origin)).append(nl);
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
        if (scene.includeMaterialPath != null) {
            sb.append("include_material").append(' ').append(scene.includeMaterialPath).append(nl);
        }

        for (PbdInstance inst : scene.instances) {
            serializeInstance(sb, inst, nl, sp);
        }
        return sb.toString();
    }

    private void serializeInstance(StringBuilder sb, PbdInstance inst, String nl, String sp) {
        String indent = pretty ? "  " : "";
        sb.append(inst.type).append(' ').append(inst.id).append(sp).append('{').append(nl);

        if (inst.type.equals("ref") || inst.type.equals("group")) {
            String source = inst.params.get("source");
            if (source != null) sb.append(indent).append("source").append(sp).append('=').append(sp).append(source).append(nl);
        }
        if (inst.parentId != null) sb.append(indent).append("parent").append(sp).append('=').append(sp).append(inst.parentId).append(nl);
        if (inst.lod != null) sb.append(indent).append("lod").append(sp).append('=').append(sp).append(inst.lod).append(nl);
        if (inst.category != null) sb.append(indent).append("category").append(sp).append('=').append(sp).append(quoteIfNeeded(inst.category)).append(nl);

        sb.append(indent).append("pos").append(sp).append('=').append(sp).append(vec3(inst.position)).append(nl);
        if (inst.rotationDeg.x != 0 || inst.rotationDeg.y != 0 || inst.rotationDeg.z != 0) {
            sb.append(indent).append("rot").append(sp).append('=').append(sp).append(vec3(inst.rotationDeg)).append(nl);
        }
        sb.append(indent).append("scale").append(sp).append('=').append(sp).append(vec3(inst.scale)).append(nl);
        if (inst.material != null) sb.append(indent).append("mat").append(sp).append('=').append(sp).append(inst.material).append(nl);
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
            sb.append(indent).append(e.getKey()).append(sp).append('=').append(sp).append(e.getValue()).append(nl);
        }
        for (String trigger : inst.containerTriggers) {
            sb.append(indent).append("containerTrigger").append(sp).append('=').append(sp).append(trigger).append(nl);
        }

        for (PbdModifier mod : inst.modifiers) {
            sb.append(indent).append("modifier").append(' ').append(mod.type).append(sp).append('{').append(sp);
            for (var e : mod.params.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
            }
            sb.append('}').append(nl);
        }

        for (PbdInstance.Keyframe kf : inst.keyframes) {
            sb.append(indent).append("keyframe").append(sp).append('{').append(sp);
            sb.append("time").append('=').append(formatNumber(kf.time)).append(' ');
            if (kf.channel != null) sb.append("channel").append('=').append(kf.channel).append(' ');
            if (kf.pos != null) sb.append("pos").append('=').append(vec3(kf.pos)).append(' ');
            if (kf.rotDeg != null) sb.append("rot").append('=').append(vec3(kf.rotDeg)).append(' ');
            if (kf.scale != null) sb.append("scale").append('=').append(vec3(kf.scale)).append(' ');
            if (kf.sound != null) sb.append("sound").append('=').append(kf.sound).append(' ');
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
        return s.contains(" ") || s.isEmpty() ? "\"" + s + "\"" : s;
    }
}
