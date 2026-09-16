package pbd;

import org.joml.Vector3f;
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

        /** Degrees, matching rot= in the text format - NOT radians. */
        public float[] rotation(String instanceName) {
            Vector3f r = find(instanceName).rotationDeg;
            return new float[]{r.x, r.y, r.z};
        }

        public void setRotation(String instanceName, float xDeg, float yDeg, float zDeg) {
            find(instanceName).rotationDeg.set(xDeg, yDeg, zDeg);
        }

        public float[] scale(String instanceName) {
            Vector3f s = find(instanceName).scale;
            return new float[]{s.x, s.y, s.z};
        }

        public void setScale(String instanceName, float x, float y, float z) {
            find(instanceName).scale.set(x, y, z);
        }

        /** Null if this instance never had mat= set (falls back to
         * whatever MaterialRegistry treats as its default at render
         * time, not resolved here). */
        public String material(String instanceName) {
            return find(instanceName).material;
        }

        public void setMaterial(String instanceName, String materialName) {
            find(instanceName).material = materialName;
        }

        public String category(String instanceName) {
            return find(instanceName).category;
        }

        public void setCategory(String instanceName, String category) {
            find(instanceName).category = category;
        }

        /** Name of this instance's PBD parent (see parent= in the text
         * format), or null if it's a root instance. Resolved from
         * parentIndex through instanceNames() rather than exposing the
         * index itself, which is only meaningful post-
         * resolveHierarchy() and not something a modder should need to
         * think about. */
        public String parent(String instanceName) {
            PbdInstance inst = find(instanceName);
            if (inst.parentIndex < 0 || inst.parentIndex >= scene.instances.size()) return null;
            return scene.instances.get(inst.parentIndex).id;
        }

        /** Pass null to make this instance a root (no parent). Throws
         * the same IllegalArgumentException as find() if parentName
         * doesn't exist - NOT if it would create a cycle (this facade
         * doesn't walk the chain to check; PbdScene.resolveHierarchy
         * remains the actual authority the engine relies on, this is
         * just convenient wiring, not a substitute validator). */
        public void setParent(String instanceName, String parentName) {
            PbdInstance inst = find(instanceName);
            inst.parentIndex = (parentName == null) ? -1 : scene.instances.indexOf(find(parentName));
        }

        /** false (never null) for an instance that never set
         * indestructible=true - matches PbdInstance's own default,
         * not a tri-state. */
        public boolean indestructible(String instanceName) {
            return find(instanceName).indestructible;
        }

        public void setIndestructible(String instanceName, boolean indestructible) {
            find(instanceName).indestructible = indestructible;
        }

        /** Null when never set (see PbdInstance's own doc on why
         * "never given" and "explicitly zero" are kept distinct) -
         * only meaningful when indestructible(instanceName) is true. */
        public Float hardness(String instanceName) { return find(instanceName).hardness; }
        public Float resistance(String instanceName) { return find(instanceName).resistance; }
        public void setHardness(String instanceName, Float hardness) { find(instanceName).hardness = hardness; }
        public void setResistance(String instanceName, Float resistance) { find(instanceName).resistance = resistance; }

        /** "point", "spot", or null if this instance isn't a light at
         * all (see PrimitiveRegistry - a light is its own primitive
         * type, so this and isLight() below usually agree with
         * type(instanceName).equals("light"), but a scene built by
         * hand rather than through addInstance/setType could in
         * principle set light fields on some other type too - this
         * facade doesn't forbid that, same as the text format itself
         * doesn't). */
        public String lightMode(String instanceName) { return find(instanceName).lightMode; }
        public void setLightMode(String instanceName, String mode) {
            if (!"point".equals(mode) && !"spot".equals(mode)) {
                throw new IllegalArgumentException("lightMode must be \"point\" or \"spot\", got \"" + mode + "\"");
            }
            find(instanceName).lightMode = mode;
        }

        public boolean isLight(String instanceName) { return "light".equals(type(instanceName)); }

        public boolean isLightEnabled(String instanceName) { return find(instanceName).lightEnabled; }
        public void setLightEnabled(String instanceName, boolean enabled) { find(instanceName).lightEnabled = enabled; }
        /** Flips lightEnabled and returns the NEW state, so a caller
         * wiring this to a switch/interaction doesn't need a separate
         * isLightEnabled call first to know what just happened. */
        public boolean toggleLight(String instanceName) {
            PbdInstance inst = find(instanceName);
            inst.lightEnabled = !inst.lightEnabled;
            return inst.lightEnabled;
        }

        /** {r, g, b}, 0..1 each, or null if never set. */
        public float[] lightColor(String instanceName) {
            Vector3f c = find(instanceName).lightColor;
            return c == null ? null : new float[]{c.x, c.y, c.z};
        }

        public void setLightColor(String instanceName, float r, float g, float b) {
            PbdInstance inst = find(instanceName);
            if (inst.lightColor == null) inst.lightColor = new Vector3f();
            inst.lightColor.set(r, g, b);
        }

        public Float lightIntensity(String instanceName) { return find(instanceName).lightIntensity; }
        public void setLightIntensity(String instanceName, float intensity) { find(instanceName).lightIntensity = intensity; }
        public Float lightRange(String instanceName) { return find(instanceName).lightRange; }
        public void setLightRange(String instanceName, float range) { find(instanceName).lightRange = range; }
        /** Degrees (half-angle of the cone) - only meaningful when lightMode is "spot". */
        public Float lightSpotAngle(String instanceName) { return find(instanceName).lightSpotAngleDeg; }
        public void setLightSpotAngle(String instanceName, float degrees) { find(instanceName).lightSpotAngleDeg = degrees; }

        public boolean hasInstance(String instanceName) {
            for (PbdInstance inst : scene.instances) if (inst.id.equals(instanceName)) return true;
            return false;
        }

        /** Creates and appends a new root instance of the given
         * primitive type (must already be known to this engine's own
         * primitiveRegistry() - "cube", "light", etc.; see
         * PrimitiveRegistry.isKnown if a caller wants to check first
         * rather than catch), at the origin with default rotation/
         * scale, and returns its name for immediate chaining (e.g.
         * scene.setPosition(scene.addInstance("cube", "myBox"), 1, 2, 3)).
         * Throws IllegalArgumentException, not a silent no-op, for a
         * duplicate name - two instances sharing an id is a real
         * correctness problem downstream (parent=/pbd_ref resolution
         * both look instances up BY that id), not a cosmetic one. */
        public String addInstance(String type, String instanceName) {
            if (!primitiveRegistry.isKnown(type)) {
                throw new IllegalArgumentException("Unknown primitive type '" + type + "' - known types: "
                    + "see PrimitiveRegistry, or call an engine-level register() first for a custom one");
            }
            if (hasInstance(instanceName)) {
                throw new IllegalArgumentException("An instance named '" + instanceName + "' already exists in this scene");
            }
            PbdInstance inst = new PbdInstance(instanceName, type);
            scene.instances.add(inst);
            return inst.id;
        }

        /** True if an instance with this name existed and was removed.
         * Does NOT reparent or otherwise fix up any instance that had
         * this one as its parent= - those instances' parentIndex would
         * now point at whatever shifted into this slot, so re-running
         * PbdScene.resolveHierarchy (or reassigning setParent
         * explicitly on any former children first) is the caller's own
         * responsibility, same as hand-editing the instances list via
         * raw() would require. */
        public boolean removeInstance(String instanceName) {
            return scene.instances.removeIf(inst -> inst.id.equals(instanceName));
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
