package pbd.format;

import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .pbd -> PbdScene parser, hand-written. The grammar is regular enough
 * (blocks of { key=value }, tuples, lists of tuples) that ANTLR/lark
 * wouldn't be justified, and a hand-rolled parser stays the fastest to
 * evolve at the pace the format changes.
 *
 * Grammar covered:
 *   pbd_version <value>
 *   <type> <id> { key=value ... [modifier <type> { key=value ... }]* }
 *   curve <id> { kind=<value> points=[(x,y,z), ...] }
 *   pbd_ref <id> { source=<path> [pos=][rot=][scale=][parent=] }
 *   include_material <path>
 *
 * pbd_ref and include_material resolve <path> relative to the including
 * file's own directory, so they only work through parseFile (which knows
 * where the file lives) - parse(String) has no base directory to resolve
 * against and raises a clear error if either is encountered.
 */
public final class PbdParser {

    private final PrimitiveRegistry primitiveRegistry;
    private final ModifierRegistry modifierRegistry;

    private String src;
    private int pos;
    private int line;
    private Path baseDir;           // null when parsing via parse(String) directly
    private Deque<Path> includeStack; // shared across the whole recursive include chain, for cycle detection

    public PbdParser(PrimitiveRegistry primitiveRegistry, ModifierRegistry modifierRegistry) {
        this.primitiveRegistry = primitiveRegistry;
        this.modifierRegistry = modifierRegistry;
    }

    public PbdScene parseFile(Path path) throws IOException {
        Path resolved = path.toAbsolutePath().normalize();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(resolved);
        return parseInternal(Files.readString(path), resolved.getParent(), stack);
    }

    public PbdScene parse(String text) {
        return parseInternal(text, null, new ArrayDeque<>());
    }

    private PbdScene parseInternal(String text, Path baseDir, Deque<Path> includeStack) {
        this.src = text;
        this.pos = 0;
        this.line = 1;
        this.baseDir = baseDir;
        this.includeStack = includeStack;

        PbdScene scene = new PbdScene();

        skipWhitespaceAndComments();
        while (!atEnd()) {
            String keyword = readIdentifier();
            switch (keyword) {
                case "pbd_version" -> readRawValue(); // value ignored for now, just consumed
                case "curve" -> scene.addCurve(parseCurve());
                case "pbd_ref" -> parsePbdRef(scene);
                case "include_material" -> parseIncludeMaterial(scene);
                default -> {
                    if (!primitiveRegistry.isKnown(keyword)) {
                        throw error("Unknown primitive type or keyword: '" + keyword + "'");
                    }
                    scene.addInstance(parseInstance(keyword));
                }
            }
            skipWhitespaceAndComments();
        }

        scene.resolveHierarchy();
        return scene;
    }

    // ---- blocks -----------------------------------------------------------

    private PbdInstance parseInstance(String type) {
        String id = readIdentifier();
        expect('{');

        PbdInstance instance = new PbdInstance(id, type);

        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            if (key.equals("modifier")) {
                instance.modifiers.add(parseModifier());
            } else if (key.equals("keyframe")) {
                instance.keyframes.add(parseKeyframe());
            } else {
                expect('=');
                applyInstanceField(instance, key, readRawValue());
            }
            skipWhitespaceAndComments();
        }
        expect('}');
        return instance;
    }

    private void applyInstanceField(PbdInstance instance, String key, String rawValue) {
        switch (key) {
            case "pos" -> instance.position.set(parseVec3(rawValue));
            case "scale" -> instance.scale.set(parseVec3(rawValue));
            case "rot" -> {
                Vector3f degrees = parseVec3(rawValue);
                // Order matters once more than one axis is non-zero -
                // Blender's Euler 'XYZ' is intrinsic (X applied first, in
                // the object's own frame; then Y in that once-rotated
                // frame; then Z in the twice-rotated frame). Composing
                // rotateX().rotateY().rotateZ() in that reading order
                // gives the OPPOSITE composition (Z effectively applied
                // to the vector first) - harmless whenever only one axis
                // is non-zero (which is why simple single-axis rotations
                // never surfaced this), wrong as soon as two or more
                // combine. Checked by hand against a real 3-leg table
                // base's exact rot values before this was trusted: the
                // old order gave leg 0 tilting one way and legs 1/2
                // tilting the opposite way for the same nominal 15 degree
                // pitch; this order gives all three consistent.
                instance.rotation.identity()
                    .rotateZ((float) Math.toRadians(degrees.z))
                    .rotateY((float) Math.toRadians(degrees.y))
                    .rotateX((float) Math.toRadians(degrees.x));
                instance.rotationDeg.set(degrees);
            }
            case "mat" -> instance.material = rawValue;
            case "parent" -> instance.parentId = rawValue;
            default -> instance.params.put(key, rawValue); // free-form extension, interpreted later
        }
    }

    private PbdInstance.Keyframe parseKeyframe() {
        expect('{');
        float time = 0f;
        Vector3f pos = null;
        Vector3f rotDeg = null;
        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            String rawValue = readRawValue();
            switch (key) {
                case "time" -> time = Float.parseFloat(rawValue);
                case "pos" -> pos = parseVec3(rawValue);
                case "rot" -> rotDeg = parseVec3(rawValue); // degrees, same convention as an instance's own rot=
                default -> throw error("Unknown keyframe field: '" + key + "'");
            }
            skipWhitespaceAndComments();
        }
        expect('}');
        return new PbdInstance.Keyframe(time, pos, rotDeg);
    }

    private PbdModifier parseModifier() {
        String type = readIdentifier();
        if (!modifierRegistry.isKnown(type)) {
            throw error("Unknown modifier type: '" + type + "'");
        }
        expect('{');
        Map<String, String> params = new LinkedHashMap<>();
        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            params.put(key, readRawValue());
            skipWhitespaceAndComments();
        }
        expect('}');
        return new PbdModifier(type, params);
    }

    private PbdCurve parseCurve() {
        String id = readIdentifier();
        expect('{');

        String kind = "bezier";
        List<Vector3f> points = new ArrayList<>();

        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            String raw = readRawValue();
            if (key.equals("kind")) {
                kind = raw;
            } else if (key.equals("points")) {
                points = parseVec3List(raw);
            }
            skipWhitespaceAndComments();
        }
        expect('}');
        return new PbdCurve(id, kind, points);
    }

    /**
     * Parses a pbd_ref block and inlines the referenced file's whole scene
     * into the current one: the ref itself becomes a zero-geometry "group"
     * instance carrying its pos/rot/scale (PatchExpander emits no patches
     * for type "group", so it never renders and never tessellates - purely
     * a transform for HierarchyResolver, whose existing parent-composition
     * logic then does the actual placement work, unmodified). Every
     * instance from the referenced file is copied in with its id prefixed
     * `<refId>.` (so including the same file twice under different ref ids
     * can't collide), and any of ITS root instances get re-parented onto
     * the group instance instead of staying root - everything deeper in
     * that file's own hierarchy keeps pointing at its own (also prefixed)
     * parent, unchanged.
     *
     * Known limitation: curves from the referenced file are merged in
     * as-is, not prefixed - two different refs to files defining a
     * same-named curve would collide. Not fixed yet since the `curve`
     * modifier isn't wired up on the GPU side at all yet either (see the
     * README), so this has no visible effect currently.
     */
    private void parsePbdRef(PbdScene scene) {
        String refId = readIdentifier();
        expect('{');

        String source = null;
        Vector3f pos3 = new Vector3f();
        Vector3f scale3 = new Vector3f(1, 1, 1);
        Vector3f rotDeg = new Vector3f();
        String parentId = null;

        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            String raw = readRawValue();
            switch (key) {
                case "source" -> source = raw;
                case "pos" -> pos3.set(parseVec3(raw));
                case "scale" -> scale3.set(parseVec3(raw));
                case "rot" -> rotDeg.set(parseVec3(raw));
                case "parent" -> parentId = raw;
                default -> throw error("Unknown pbd_ref field: '" + key + "'");
            }
            skipWhitespaceAndComments();
        }
        expect('}');

        if (source == null) throw error("pbd_ref '" + refId + "' is missing source=");
        if (baseDir == null) {
            throw error("pbd_ref '" + refId + "' needs a file on disk to resolve '" + source
                + "' against - parse(String) has no base directory; use parseFile instead");
        }

        Path resolved = baseDir.resolve(source).toAbsolutePath().normalize();
        if (includeStack.contains(resolved)) {
            throw error("pbd_ref cycle detected: '" + resolved + "' is already being parsed ("
                + includeStack.stream().map(Path::toString).reduce((a, b) -> a + " <- " + b).orElse("") + ")");
        }

        PbdInstance group = new PbdInstance(refId, "group");
        group.position.set(pos3);
        group.scale.set(scale3);
        // Same composition-order fix as applyInstanceField's "rot" case -
        // see its comment.
        group.rotation.identity()
            .rotateZ((float) Math.toRadians(rotDeg.z))
            .rotateY((float) Math.toRadians(rotDeg.y))
            .rotateX((float) Math.toRadians(rotDeg.x));
        group.rotationDeg.set(rotDeg);
        group.parentId = parentId;
        scene.addInstance(group);

        includeStack.push(resolved);
        PbdScene subScene;
        try {
            String subText;
            try {
                subText = Files.readString(resolved);
            } catch (IOException e) {
                throw error("pbd_ref '" + refId + "' could not read '" + resolved + "': " + e.getMessage());
            }
            subScene = new PbdParser(primitiveRegistry, modifierRegistry)
                .parseInternal(subText, resolved.getParent(), includeStack);
        } finally {
            includeStack.pop();
        }

        String prefix = refId + ".";
        for (PbdInstance sub : subScene.instances) {
            PbdInstance copy = new PbdInstance(prefix + sub.id, sub.type);
            copy.position.set(sub.position);
            copy.rotation.set(sub.rotation);
            copy.scale.set(sub.scale);
            copy.material = sub.material;
            copy.modifiers.addAll(sub.modifiers);
            copy.params.putAll(sub.params);
            copy.parentId = (sub.parentId == null) ? refId : prefix + sub.parentId;
            scene.addInstance(copy);
        }
        scene.curves.putAll(subScene.curves); // see the "known limitation" note above
        scene.materialOverrides.putAll(subScene.materialOverrides);
    }

    private void parseIncludeMaterial(PbdScene scene) {
        String rawPath = readRawValue();
        if (baseDir == null) {
            throw error("include_material needs a file on disk to resolve '" + rawPath
                + "' against - parse(String) has no base directory; use parseFile instead");
        }
        Path resolved = baseDir.resolve(rawPath).toAbsolutePath().normalize();
        try {
            Map<String, Map<String, String>> loaded = new PbdMatParser().parseFile(resolved);
            for (Map<String, String> fields : loaded.values()) {
                for (String key : new String[]{"texture", "normalMap", "roughnessMap", "displacementMap"}) {
                    String filename = fields.get(key);
                    if (filename != null) {
                        fields.put(key, resolveTexturePath(filename, resolved.getParent()).toString());
                    }
                }
            }
            scene.materialOverrides.putAll(loaded); // last include wins on a name collision
        } catch (IOException e) {
            throw error("include_material could not read '" + resolved + "': " + e.getMessage());
        }
    }

    /**
     * A material's `texture=` value is just a bare filename - resolved
     * here, once, at parse time (rather than left for PbdRenderer to
     * figure out later, which would need to track the .pbdmat's directory
     * itself) so materialOverrides always holds a ready-to-use absolute
     * path. Tries next to the .pbdmat file first, then falls back to the
     * shared textures/ folder (matching SkydomeRenderer's own convention
     * for night_sky.jpg) - a texture doesn't have to sit next to every
     * .pbdmat that references it. If neither exists, returns the first
     * candidate anyway; TextureLoader/MaterialTextureArray already log a
     * clear "not found" and degrade gracefully rather than crashing.
     */
    private Path resolveTexturePath(String filename, Path pbdmatDir) {
        Path nextToMatFile = pbdmatDir.resolve(filename).toAbsolutePath().normalize();
        if (Files.exists(nextToMatFile)) return nextToMatFile;

        Path sharedTextures = pbd.pz.PbdPaths.TEXTURES_DIR.resolve(filename).toAbsolutePath().normalize();
        if (Files.exists(sharedTextures)) return sharedTextures;

        // Neither candidate exists - MaterialTextureArray/TextureLoader
        // will also print a "not found" for whichever one gets returned
        // below, but only that ONE path, not the other candidate that
        // was equally tried - printing both here up front is the
        // difference between "it's not next to the .pbdmat" (fixable by
        // moving the file) and "it's not in the shared folder either"
        // (fixable a different way), rather than the user having to
        // guess which of the two this engine even checked.
        System.err.println("[PbdParser] Texture '" + filename + "' not found at either candidate:");
        System.err.println("  " + nextToMatFile);
        System.err.println("  " + sharedTextures);
        return nextToMatFile;
    }

    // ---- values -----------------------------------------------------------

    /** Reads a "raw" value as written: quoted string, tuple (a,b,c), list [...], or bare word. */
    private String readRawValue() {
        skipWhitespaceAndComments();
        char c = peek();
        if (c == '"') {
            pos++;
            int start = pos;
            while (!atEnd() && peek() != '"') pos++;
            String s = src.substring(start, pos);
            if (!atEnd()) pos++; // closing quote
            return s;
        }
        int start = pos;
        if (c == '(') {
            skipBalanced('(', ')');
        } else if (c == '[') {
            skipBalanced('[', ']');
        } else {
            while (!atEnd() && !isStructural(peek()) && !Character.isWhitespace(peek())) pos++;
        }
        return src.substring(start, pos).trim();
    }

    private Vector3f parseVec3(String raw) {
        String inner = raw.trim();
        if (inner.startsWith("(")) inner = inner.substring(1, inner.length() - 1);
        String[] parts = inner.split(",");
        if (parts.length == 1) {
            float v = Float.parseFloat(parts[0].trim());
            return new Vector3f(v, v, v); // a single number means uniform across all 3 axes
        }
        return new Vector3f(
            Float.parseFloat(parts[0].trim()),
            Float.parseFloat(parts[1].trim()),
            Float.parseFloat(parts[2].trim()));
    }

    private List<Vector3f> parseVec3List(String raw) {
        String inner = raw.trim();
        if (inner.startsWith("[")) inner = inner.substring(1, inner.length() - 1);
        inner = inner.trim();
        List<Vector3f> points = new ArrayList<>();
        if (inner.isEmpty()) return points;
        // Splits top-level "(..)" tuples apart; tolerates whitespace/newlines around commas.
        for (String tuple : inner.split("(?<=\\))\\s*,\\s*(?=\\()")) {
            points.add(parseVec3(tuple.trim()));
        }
        return points;
    }

    // ---- low-level cursor / lexing -----------------------------------------

    private boolean atEnd() { return pos >= src.length(); }
    private char peek() { return src.charAt(pos); }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (c == '\n') { line++; pos++; }
            else if (Character.isWhitespace(c)) { pos++; }
            else if (c == '#') { while (!atEnd() && peek() != '\n') pos++; }
            else break;
        }
    }

    private boolean isStructural(char c) {
        return c == '{' || c == '}' || c == '(' || c == ')' || c == '[' || c == ']' || c == '=' || c == ',';
    }

    private String readIdentifier() {
        skipWhitespaceAndComments();
        int start = pos;
        while (!atEnd() && !isStructural(peek()) && !Character.isWhitespace(peek())) pos++;
        if (pos == start) throw error("Expected an identifier");
        return src.substring(start, pos);
    }

    private void expect(char c) {
        skipWhitespaceAndComments();
        if (atEnd() || peek() != c) {
            throw error("Expected '" + c + "'" + (atEnd() ? " (end of file)" : ", found '" + peek() + "'"));
        }
        pos++;
    }

    private void skipBalanced(char open, char close) {
        int depth = 0;
        do {
            char c = src.charAt(pos++);
            if (c == open) depth++;
            else if (c == close) depth--;
        } while (depth > 0 && !atEnd());
    }

    private RuntimeException error(String message) {
        return new IllegalStateException("Parse error (line " + line + "): " + message);
    }
}
