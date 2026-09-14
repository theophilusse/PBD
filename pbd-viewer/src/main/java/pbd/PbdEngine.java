package pbd;

import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdAssetFormat;
import pbd.format.PbdBinFormat;
import pbd.format.PbdInstance;
import pbd.format.PbdParser;
import pbd.format.PbdScene;
import pbd.format.PbdSerializer;
import pbd.format.PrimitiveRegistry;
import pbd.pz.PbdPaths;
import pbd.pz.TileGeometryConverter;
import pbd.pz.TileGeometryParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A single, friendly entry point into the PBD format/data layer, for
 * anyone on the team who wants to load, inspect, modify, or save PBD
 * scenes without learning PbdParser/PrimitiveRegistry/ModifierRegistry/
 * MaterialRegistry/PbdSerializer as separate pieces first.
 *
 * Deliberately does NOT cover rendering (PbdRenderer, GlWindow, the GPU
 * pipeline) - that needs a live OpenGL context and a running window
 * loop, which isn't something a facade can meaningfully simplify away;
 * see pbd.app.Main for the reference integration of the rendering side
 * instead. This facade is for the data: reading, building, and writing
 * .pbd files, plus the tileGeometry -> PBD conversion tool.
 *
 * Example - load a scene, print its instances, save a minified copy:
 * <pre>{@code
 * PbdEngine engine = new PbdEngine();
 * PbdEngine.SceneHandle scene = engine.load(Path.of("wardrobe.pbd"));
 * for (String name : scene.instanceNames()) {
 *     float[] p = scene.position(name);
 *     System.out.println(name + " at (" + p[0] + "," + p[1] + "," + p[2] + ")");
 * }
 * scene.saveMinified(Path.of("wardrobe.min.pbd"));
 * }</pre>
 */
public final class PbdEngine {

    private final PrimitiveRegistry primitiveRegistry = new PrimitiveRegistry();
    private final ModifierRegistry modifierRegistry = new ModifierRegistry();
    private final MaterialRegistry materialRegistry = new MaterialRegistry();

    /** The same registries every scene loaded through this engine
     * instance was parsed against - needed alongside a SceneHandle's
     * raw() PbdScene by anything that renders it (PbdRenderer.upload
     * takes all three), so that setup logic lives in one place instead
     * of every caller re-constructing its own fresh registries and
     * risking a mismatch with what actually parsed the scene. */
    public PrimitiveRegistry primitiveRegistry() { return primitiveRegistry; }
    public ModifierRegistry modifierRegistry() { return modifierRegistry; }
    public MaterialRegistry materialRegistry() { return materialRegistry; }

    /** Loads a .pbd (plain text), .pbdbin (gzip-compressed text - see
     * PbdBinFormat), or .pbdasset (zip container with the scene plus its
     * materials/textures/icon - see PbdAssetFormat) file, picking the
     * right reader by extension. A .pbdasset is extracted to a temp
     * directory first (its own materials/textures land alongside its
     * scene.pbd there, so include_material resolution inside it keeps
     * working unmodified) - that directory is intentionally NOT cleaned
     * up automatically, since the caller may still need those extracted
     * textures for rendering after this call returns.
     */
    public SceneHandle loadAny(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pbdbin")) {
            return new SceneHandle(PbdBinFormat.read(path, primitiveRegistry, modifierRegistry));
        }
        if (name.endsWith(".pbdasset")) {
            Path tempDir = Files.createTempDirectory("pbdasset-");
            PbdAssetFormat.UnpackResult unpacked = PbdAssetFormat.unpack(path, tempDir);
            if (unpacked.scenePbd == null) {
                throw new IOException(".pbdasset has no scene.pbd entry: " + path);
            }
            return load(unpacked.scenePbd);
        }
        return load(path); // .pbd, or anything else - load() itself will surface a clear parse error if it isn't actually PBD text
    }

    /** Loads a .pbd file. IOException for a missing/unreadable file;
     * IllegalStateException (unchecked - a malformed .pbd is a data
     * problem, not something every caller should be forced to declare a
     * checked exception for) for a syntax error, with a message naming
     * the line and nearby text. */
    public SceneHandle load(Path pbdFile) throws IOException {
        PbdScene scene = new PbdParser(primitiveRegistry, modifierRegistry).parseFile(pbdFile);
        return new SceneHandle(scene);
    }

    public SceneHandle load(String pbdText) {
        PbdScene scene = new PbdParser(primitiveRegistry, modifierRegistry).parse(pbdText);
        return new SceneHandle(scene);
    }

    /** An empty scene, ready to have instances added via PbdScene.addInstance
     * (see SceneHandle.raw()) and then saved. */
    public SceneHandle newScene() {
        return new SceneHandle(new PbdScene());
    }

    /**
     * Converts a Project Zomboid tileGeometry.txt into one .pbd file per
     * non-empty tile in outputDir - the same conversion
     * pbd.pz.TileGeometryConverterMain does from the command line,
     * exposed here for calling from other Java code directly.
     */
    public TileGeometryConverter.Stats convertTileGeometry(Path tileGeometryTxt, Path outputDir) throws IOException {
        TileGeometryParser.TileGeometryFile file = new TileGeometryParser().parseFile(tileGeometryTxt);
        return new TileGeometryConverter().convertToDirectory(file, outputDir);
    }

    /**
     * A loaded (or newly created) scene, with simplified accessors over
     * the underlying PbdScene - the facade's actual day-to-day surface.
     */
    public final class SceneHandle {
        private final PbdScene scene;

        private SceneHandle(PbdScene scene) {
            this.scene = scene;
        }

        /** Escape hatch to the full underlying data model, for anything
         * this facade doesn't wrap yet - PbdScene/PbdInstance are public
         * types, not hidden implementation details, so reaching past the
         * facade is always available, never a dead end. */
        public PbdScene raw() {
            return scene;
        }

        public List<String> instanceNames() {
            List<String> names = new ArrayList<>();
            for (PbdInstance inst : scene.instances) names.add(inst.id);
            return names;
        }

        public float[] position(String instanceName) {
            PbdInstance inst = find(instanceName);
            return new float[]{inst.position.x, inst.position.y, inst.position.z};
        }

        public void setPosition(String instanceName, float x, float y, float z) {
            find(instanceName).position.set(x, y, z);
        }

        public String type(String instanceName) {
            return find(instanceName).type;
        }

        /** Scene-level metadata - name/kind/authors/origin (see
         * PbdScene's own fields), all null/empty when the file never
         * set them. */
        public String name() { return scene.name; }
        public String kind() { return scene.kind; }
        public List<String> authors() { return scene.authors; }
        public String origin() { return scene.origin; }

        public void setName(String name) { scene.name = name; }
        public void setKind(String kind) { scene.kind = kind; }
        public void setOrigin(String origin) { scene.origin = origin; }
        public void addAuthor(String author) { scene.authors.add(author); }

        private PbdInstance find(String instanceName) {
            for (PbdInstance inst : scene.instances) {
                if (inst.id.equals(instanceName)) return inst;
            }
            throw new IllegalArgumentException("No instance named '" + instanceName + "' in this scene - "
                + "known instances: " + instanceNames());
        }

        /** Human-readable, indented .pbd text - easy to read and diff. */
        public String toPrettyText() {
            return new PbdSerializer(true).serialize(scene);
        }

        /** Compact .pbd text - same data, minimum separators. */
        public String toMinifiedText() {
            return new PbdSerializer(false).serialize(scene);
        }

        public void savePretty(Path outputPath) throws IOException {
            Files.writeString(outputPath, toPrettyText(), StandardCharsets.UTF_8);
        }

        public void saveMinified(Path outputPath) throws IOException {
            Files.writeString(outputPath, toMinifiedText(), StandardCharsets.UTF_8);
        }

        /** Saves as .pbdbin (gzip-compressed minified text - see
         * PbdBinFormat) rather than plain .pbd text - smaller, faster to
         * load, not meant for hand-editing. */
        public void saveBin(Path outputPath) throws IOException {
            PbdBinFormat.write(scene, outputPath);
        }
    }
}
