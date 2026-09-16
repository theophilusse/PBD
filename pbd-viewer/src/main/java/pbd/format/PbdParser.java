package pbd.format;

import org.joml.Vector3f;
import pbd.pz.PbdPaths;

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
    private Path baseDir;           // null when parsing via parse(String) directly, or when the CURRENT file was fetched from a URL (see baseUrl)
    private String baseUrl;         // non-null only while parsing content that was itself fetched from a URL - nested relative pbd_ref sources resolve against THIS (as a URL), not baseDir
    private Deque<Path> includeStack; // shared across the whole recursive include chain, for cycle detection

    public PbdParser(PrimitiveRegistry primitiveRegistry, ModifierRegistry modifierRegistry) {
        this.primitiveRegistry = primitiveRegistry;
        this.modifierRegistry = modifierRegistry;
    }

    public PbdScene parseFile(Path path) throws IOException {
        Path resolved = path.toAbsolutePath().normalize();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(resolved);
        return parseInternal(Files.readString(path), resolved.getParent(), null, stack);
    }

    public PbdScene parse(String text) {
        return parseInternal(text, null, null, new ArrayDeque<>());
    }

    /** Same as parse(String), but with a real baseDir for resolving a
     * relative include_material - for text that didn't come from an
     * actual file on disk (PbdBinFormat decompresses .pbdbin into a
     * String, for instance) but should still resolve relative
     * references as if it had, against wherever that content
     * conceptually "lives" (for .pbdbin, that's the .pbdbin file's own
     * directory - see PbdBinFormat.read). Passing null here is exactly
     * equivalent to parse(String); this isn't a separate code path,
     * just parse(String) with the one thing it couldn't have known
     * filled in. */
    public PbdScene parse(String text, Path baseDir) {
        return parseInternal(text, baseDir, null, new ArrayDeque<>());
    }

    private PbdScene parseInternal(String text, Path baseDir, String baseUrl, Deque<Path> includeStack) {
        this.src = text;
        this.pos = 0;
        this.line = 1;
        this.baseDir = baseDir;
        this.baseUrl = baseUrl;
        this.includeStack = includeStack;

        PbdScene scene = new PbdScene();

        skipWhitespaceAndComments();
        while (!atEnd()) {
            String keyword = readIdentifier();
            switch (keyword) {
                case "pbd_version" -> readRawValue(); // value ignored for now, just consumed
                case "name" -> { expect('='); scene.name = readMetadataValue(); }
                case "kind" -> { expect('='); scene.kind = readMetadataValue(); }
                case "author" -> { expect('='); scene.authors.add(readMetadataValue()); }
                case "origin" -> { expect('='); scene.origin = readMetadataValue(); }
                case "description" -> { expect('='); scene.description = readMetadataValue(); }
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

    private static final java.util.regex.Pattern VERTEX_DATA_LOD_KEY =
        java.util.regex.Pattern.compile("vertexDataLod(\\d+)");

    private void applyInstanceField(PbdInstance instance, String key, String rawValue) {
        // Checked before the switch below since Java's switch only
        // matches exact string literals, not a prefix/pattern - a
        // vertexDataLod<N> for N=1,2,3... needs its own numbered entry
        // in meshDataByLod, not a single fixed case the way plain
        // vertexData= (always LOD 0, i.e. PbdInstance.meshData itself)
        // gets one.
        java.util.regex.Matcher lodMatch = VERTEX_DATA_LOD_KEY.matcher(key);
        if (lodMatch.matches()) {
            int lod = Integer.parseInt(lodMatch.group(1));
            String encoded = rawValue.startsWith("base64:") ? rawValue.substring(7) : rawValue;
            instance.meshDataByLod.put(lod, pbd.format.PbdMeshData.fromBase64(encoded));
            return;
        }
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
            case "lod" -> {
                try {
                    instance.lod = Integer.parseInt(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("lod must be an integer, got '" + rawValue + "'");
                }
            }
            case "category" -> instance.category = rawValue;
            case "indestructible" -> instance.indestructible = Boolean.parseBoolean(rawValue.trim());
            case "hardness" -> {
                try {
                    instance.hardness = Float.parseFloat(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("hardness must be a number, got '" + rawValue + "'");
                }
            }
            case "resistance" -> {
                try {
                    instance.resistance = Float.parseFloat(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("resistance must be a number, got '" + rawValue + "'");
                }
            }
            case "lightMode" -> {
                if (!rawValue.equals("point") && !rawValue.equals("spot")) {
                    throw error("lightMode must be 'point' or 'spot', got '" + rawValue + "'");
                }
                instance.lightMode = rawValue;
            }
            case "lightEnabled" -> {
                if (!rawValue.equals("true") && !rawValue.equals("false")) {
                    throw error("lightEnabled must be 'true' or 'false', got '" + rawValue + "'");
                }
                instance.lightEnabled = Boolean.parseBoolean(rawValue);
            }
            case "lightColor" -> instance.lightColor = parseVec3(rawValue);
            case "lightIntensity" -> {
                try {
                    instance.lightIntensity = Float.parseFloat(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("lightIntensity must be a number, got '" + rawValue + "'");
                }
            }
            case "lightRange" -> {
                try {
                    instance.lightRange = Float.parseFloat(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("lightRange must be a number, got '" + rawValue + "'");
                }
            }
            case "lightSpotAngle" -> {
                try {
                    instance.lightSpotAngleDeg = Float.parseFloat(rawValue.trim());
                } catch (NumberFormatException e) {
                    throw error("lightSpotAngle must be a number, got '" + rawValue + "'");
                }
            }
            case "containerTrigger" -> instance.containerTriggers.add(rawValue);
            case "vertexData" -> {
                String encoded = rawValue.startsWith("base64:") ? rawValue.substring(7) : rawValue;
                instance.meshData = pbd.format.PbdMeshData.fromBase64(encoded);
            }
            default -> instance.params.put(key, rawValue); // free-form extension, interpreted later
        }
    }

    private PbdInstance.Keyframe parseKeyframe() {
        expect('{');
        float time = 0f;
        Vector3f pos = null;
        Vector3f rotDeg = null;
        Vector3f scale = null;
        String sound = null;
        String channel = null;
        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            String rawValue = readRawValue();
            switch (key) {
                case "time" -> time = Float.parseFloat(rawValue);
                case "pos" -> pos = parseVec3(rawValue);
                case "rot" -> rotDeg = parseVec3(rawValue); // degrees, same convention as an instance's own rot=
                case "scale" -> scale = parseVec3(rawValue);
                case "sound" -> sound = rawValue;
                case "channel" -> channel = rawValue;
                default -> throw error("Unknown keyframe field: '" + key + "'");
            }
            skipWhitespaceAndComments();
        }
        expect('}');
        return new PbdInstance.Keyframe(time, pos, rotDeg, sound, channel, scale);
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
    /** Deliberately narrow (http/https only, case-insensitive scheme) -
     * good enough to distinguish "this is a URL" from "this is a relative
     * or absolute file path" without misfiring on a Windows-style
     * absolute path (C:\...) or a path that happens to contain a colon
     * elsewhere. */
    private static boolean isUrl(String s) {
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private void parsePbdRef(PbdScene scene) {
        String refId = readIdentifier();
        expect('{');

        String source = null;
        String fallback = null;
        Vector3f pos3 = new Vector3f();
        Vector3f scale3 = new Vector3f(1, 1, 1);
        Vector3f rotDeg = new Vector3f();
        String parentId = null;
        Integer lod = null;
        String category = null;
        String matOverride = null;

        skipWhitespaceAndComments();
        while (peek() != '}') {
            String key = readIdentifier();
            expect('=');
            String raw = readRawValue();
            switch (key) {
                case "source" -> source = raw;
                // Optional fallback URL for a 404 on a remote source= -
                // ignored (with a warning) if source= isn't itself a URL,
                // since a fallback only makes sense for a fetch that can
                // actually fail that way.
                case "fallback" -> fallback = raw;
                case "pos" -> pos3.set(parseVec3(raw));
                case "scale" -> scale3.set(parseVec3(raw));
                case "rot" -> rotDeg.set(parseVec3(raw));
                case "parent" -> parentId = raw;
                case "lod" -> {
                    try {
                        lod = Integer.parseInt(raw.trim());
                    } catch (NumberFormatException e) {
                        throw error("lod must be an integer, got '" + raw + "'");
                    }
                }
                case "category" -> category = raw;
                // Overrides EVERY instance's own mat= inside the
                // referenced sub-scene, applied once below after that
                // sub-scene has been fully parsed and merged in -
                // recolor/re-skin a whole imported prop without editing
                // its own source file (or every one of its instances
                // individually) just to change which materials it uses
                // in THIS particular placement.
                case "mat" -> matOverride = raw;
                default -> throw error("Unknown pbd_ref field: '" + key + "'");
            }
            skipWhitespaceAndComments();
        }
        expect('}');

        if (source == null) throw error("pbd_ref '" + refId + "' is missing source=");

        // Three ways source= can resolve, checked in this order:
        //   1. source= is itself a full URL -> fetch it directly.
        //   2. We're currently INSIDE a file that was itself fetched from
        //      a URL (baseUrl != null) and source= is relative -> resolve
        //      as a URL against that parent URL (java.net.URI's own
        //      relative-resolution rules), then fetch - a composition
        //      fetched from a URL can reference sibling files by relative
        //      path exactly like a local one can, without the whole tree
        //      needing to be absolute URLs.
        //   3. Otherwise, the existing local-file behavior, unchanged.
        Path resolved;
        String resolvedUrl = null;
        boolean isRemote = isUrl(source);
        if (isRemote) {
            resolvedUrl = source;
        } else if (baseUrl != null) {
            resolvedUrl = java.net.URI.create(baseUrl).resolve(source).toString();
            isRemote = true;
        }

        if (isRemote) {
            try {
                resolved = pbd.net.PbdFetcher.fetch(resolvedUrl, PbdPaths.REMOTE_CACHE_DIR, fallback, true);
            } catch (IOException e) {
                throw error("pbd_ref '" + refId + "' could not fetch '" + resolvedUrl + "': " + e.getMessage());
            }
        } else {
            if (fallback != null) {
                System.err.println("[PbdParser] pbd_ref '" + refId + "': fallback= is set but source= isn't a URL - ignored");
            }
            if (baseDir == null) {
                throw error("pbd_ref '" + refId + "' needs a file on disk to resolve '" + source
                    + "' against - parse(String) has no base directory; use parseFile instead");
            }
            resolved = baseDir.resolve(source).toAbsolutePath().normalize();
        }

        // Cycle detection covers both local files and remote URLs alike -
        // includeStack holds the resolved LOCAL path either way (the
        // fetched cache file's path for a URL source), which is exactly
        // what's actually being re-entered if a cycle exists.
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
        group.lod = lod;
        group.category = category;
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
            // isRemote's subDir/subUrl split: a fetched file's own nested
            // relative refs resolve against ITS url, not the local cache
            // directory the fetch happened to land in (that cache path is
            // an implementation detail, never meant to be resolved
            // against as if it were the content's real home).
            Path subDir = isRemote ? null : resolved.getParent();
            String subUrl = isRemote ? resolvedUrl : null;
            subScene = new PbdParser(primitiveRegistry, modifierRegistry)
                .parseInternal(subText, subDir, subUrl, includeStack);
        } finally {
            includeStack.pop();
        }

        String prefix = refId + ".";
        for (PbdInstance sub : subScene.instances) {
            PbdInstance copy = new PbdInstance(prefix + sub.id, sub.type);
            copy.position.set(sub.position);
            copy.rotation.set(sub.rotation);
            copy.scale.set(sub.scale);
            copy.material = matOverride != null ? matOverride : sub.material;
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
        Path resolved;
        // Non-null exactly when the .pbdmat ITSELF was fetched from a
        // URL rather than found on local disk - a texture= filename
        // inside such a .pbdmat is relative to THIS, not to resolved's
        // local cache-file location (which is just an implementation
        // detail of where PbdFetcher happened to stash the download,
        // never meant to be resolved against as if it were the
        // content's real home - same reasoning as pbd_ref's own
        // isRemote/subUrl split elsewhere in this file).
        String matUrl = null;
        if (isUrl(rawPath)) {
            matUrl = rawPath;
            try {
                resolved = pbd.net.PbdFetcher.fetch(rawPath, PbdPaths.REMOTE_CACHE_DIR, null, true);
            } catch (IOException e) {
                throw error("include_material could not fetch '" + rawPath + "': " + e.getMessage());
            }
        } else if (baseUrl != null) {
            // We're parsing content that was ITSELF fetched from a URL
            // (baseDir is null in exactly this case - see parseInternal)
            // and rawPath is a local-looking relative reference, e.g.
            // include_material materials.pbdmat sitting next to a
            // scene.pbd that was itself fetched remotely. Resolve it as
            // a URL against baseUrl (ordinary relative-URL resolution),
            // the same way pbd_ref's own nested relative source= already
            // does - a remote scene can't have a LOCAL file sitting next
            // to it on disk, only a sibling resource at the same remote
            // location.
            matUrl = java.net.URI.create(baseUrl).resolve(rawPath).toString();
            try {
                resolved = pbd.net.PbdFetcher.fetch(matUrl, PbdPaths.REMOTE_CACHE_DIR, null, true);
            } catch (IOException e) {
                throw error("include_material could not fetch '" + matUrl + "': " + e.getMessage());
            }
        } else {
            if (baseDir == null) {
                throw error("include_material needs a file on disk to resolve '" + rawPath
                    + "' against - parse(String) has no base directory; use parseFile instead");
            }
            resolved = baseDir.resolve(rawPath).toAbsolutePath().normalize();
        }
        try {
            PbdMatParser matParser = new PbdMatParser();
            Map<String, Map<String, String>> loaded = matParser.parseFile(resolved);
            // Attribution from the .pbdmat itself, merged into the
            // including scene's own author list (not replacing it) -
            // a .pbdmat can be authored by someone other than whoever
            // wrote the .pbd including it, and the final combined work
            // should credit both, not just whichever one happened to
            // be read last. Order-preserving de-dup: scene.authors is
            // a List, and a name appearing on both sides (the common
            // case - the same person usually writes both) shouldn't
            // show up twice.
            for (String matAuthor : matParser.authors) {
                if (!scene.authors.contains(matAuthor)) scene.authors.add(matAuthor);
            }
            for (Map<String, String> fields : loaded.values()) {
                for (String key : new String[]{"texture", "normalMap", "roughnessMap", "displacementMap"}) {
                    String filename = fields.get(key);
                    if (filename == null) continue;
                    if (matUrl != null) {
                        // The .pbdmat naming this texture came from a
                        // URL, so the texture filename - same as any
                        // OTHER relative reference inside remotely-
                        // fetched content - is relative to THAT URL, not
                        // any local directory, and needs its own fetch,
                        // not a local Files.exists() check.
                        String textureUrl = java.net.URI.create(matUrl).resolve(filename).toString();
                        try {
                            Path texPath = pbd.net.PbdFetcher.fetch(textureUrl, PbdPaths.REMOTE_CACHE_DIR, null, true);
                            fields.put(key, texPath.toString());
                        } catch (IOException e) {
                            throw error("include_material's " + key + "='" + filename + "' (resolved to '"
                                + textureUrl + "') could not be fetched: " + e.getMessage());
                        }
                    } else {
                        fields.put(key, resolveTexturePath(filename, resolved.getParent()).toString());
                    }
                }
            }
            scene.materialOverrides.putAll(loaded); // last include wins on a name collision
            scene.includeMaterialPath = rawPath; // as-written, still relative - see the field's own doc for why this isn't resolved further here
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

    /** Same quoted-string handling as readRawValue (unchanged - a
     * quoted "interior electric decoration" already worked correctly
     * before this existed), but an UNQUOTED value reads to the end of
     * the line rather than stopping at the first whitespace. Only used
     * for the free-form scene-metadata fields (name/kind/author/
     * origin/description) - every other field readRawValue alone
     * handles (mat=, a numeric field, ...) is a single TOKEN by design,
     * where stopping at whitespace is exactly the correct behavior,
     * not a bug to fix the same way.
     *
     * Added after a real, reported crash: `kind = interior electric
     * decoration` (no quotes, three words - a completely natural way
     * to write a free-text field) parsed "interior" as the whole value
     * via readRawValue, leaving "electric" sitting at the top level of
     * the file where the parser then tried - and failed - to read it
     * as a primitive type or keyword. Requiring quotes for any multi-
     * word kind/name would have "fixed" the crash too, but would still
     * reject exactly the input a person naturally types; reading the
     * rest of the line instead means unquoted multi-word text just
     * works, and a quoted value still works exactly as it did. */
    private String readMetadataValue() {
        skipWhitespaceAndComments();
        if (peek() == '"') return readRawValue();
        int start = pos;
        while (!atEnd() && peek() != '\n' && peek() != '#') pos++;
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
