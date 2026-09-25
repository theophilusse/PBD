package pbd.app;

import org.lwjgl.system.MemoryStack;
import pbd.classicmesh.ClassicMeshRenderer;
import pbd.classicmesh.ObjMesh;
import pbd.classicmesh.ObjParser;
import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;
import pbd.render.FlyCamera;
import pbd.render.GlWindow;
import pbd.render.PbdRenderer;
import pbd.render.SkydomeRenderer;
import pbd.render.TextRenderer;
import pbd.sky.SolarCalculator;

import java.io.IOException;
import java.nio.DoubleBuffer;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * Viewer with a free-flying camera and an on-screen stats overlay.
 * Loads a .pbd scene by default, or a classic Wavefront .obj mesh if the
 * given file has that extension - the two paths exist side by side
 * specifically so their stats (upload size, vertex/patch counts) can be
 * compared directly for the benchmark.
 *
 * Controls:
 *   WASD          horizontal movement (independent of look direction)
 *   Space         move up
 *   Left Shift    move down
 *   Mouse         look around (yaw/pitch, no roll)
 *   - / =         step LOD down / up (PBD path only; discrete steps, not continuous)
 *   C             toggle the Transform Feedback mesh cache on/off (PBD path only)
 *   Arrow keys    scrub time of day (up/down) and day of year (left/right) - drives the skydome/sun (PBD path only)
 *   Left click    toggle the nearest keyframed instance under the crosshair open/closed (PBD path only)
 *   Escape        quit
 *
 * Usage: ./gradlew run --args="src/main/resources/scenes/zomboid/rain_barrel.pbd"
 *        ./gradlew run --args="path/to/reference_mesh.obj"
 * On macOS, -XstartOnFirstThread is already wired into the `run` task.
 */
public final class Main {

    // Discrete LOD steps - one FIXED tessellation level per tier, applied
    // uniformly across the whole scene regardless of camera distance.
    //
    // pbd.tesc computes level = mix(lodMaxLevel, lodMinLevel, t) where t
    // depends on distance - but mix(X, X, t) == X for any t. So setting
    // lodMinLevel = lodMaxLevel = the same value makes distance fall out
    // of the formula entirely, with no shader change needed. This is
    // deliberate for now: a clean, single-variable-controlled level per
    // tier is what a benchmark comparison against a fixed-triangle-count
    // classic mesh needs. Real distance-based LOD (near = high level, far
    // = low level) is planned as a later step, once this simpler
    // comparison is validated - at that point these presets go back to
    // genuinely different {min, max} pairs, and nothing else changes.
    // Index 0 is AUTO: a genuine {min,max} range, restoring real distance-
    // based interpolation (pbd.tesc's mix() was never changed to remove
    // this capability - only Main.java stopped exercising it, by always
    // setting min==max for a clean, single-variable benchmark comparison).
    // The 5 named tiers after it keep that flat, distance-independent
    // behavior (min==max) for controlled comparisons; AUTO is for
    // realistic multi-instance scenes where different objects sit at
    // different distances, ahead of the stress test.
    private static final float[][] LOD_PRESETS = {
        {2f, 64f},   // AUTO
        {4f, 4f},    // VERY LOW
        {12f, 12f},  // LOW
        {24f, 24f},  // MEDIUM
        {48f, 48f},  // HIGH
        {64f, 64f},  // ULTRA
    };
    private static final String[] LOD_NAMES = {"AUTO", "VERY LOW", "LOW", "MEDIUM", "HIGH", "ULTRA"};
    private static int lodIndex = 3; // MEDIUM by default, unchanged from before
    // F3-toggled (see justPressed(GLFW_KEY_F3) below) - on by default,
    // since the whole POINT of this overlay is showing PBD's actual
    // numbers (file size vs. an equivalent traditional mesh especially)
    // rather than a debug aid someone has to know to turn on.
    private static boolean showBenchmarkOverlay = true;
    // Hide-in-container state (see handleHideKey/updateHideTransition
    // below). hidingMetaIndex is the instance index of the metadata
    // cube currently hidden in, or -1 when not hiding at all - also
    // doubles as "is a hide/exit transition currently animating"
    // together with hideTransitionT.
    private static int hidingMetaIndex = -1;
    // Voxel-destruction state (see handleDestroyKey below and
    // pbd.voxel.{VoxelOctree,PrimitiveVoxelizer,VoxelMeshBuilder}) -
    // maps an ORIGINAL instance index to the ClassicMeshRenderer
    // drawing its voxelized replacement, once destroyed. The original
    // itself gets hidden (see PbdRenderer.hideInstance) rather than
    // removed from the scene's own instance list - simpler than
    // renumbering every OTHER index that would shift if an entry were
    // actually deleted.
    private static final java.util.Map<Integer, ClassicMeshRenderer> destroyedRenderers = new java.util.HashMap<>();
    // gridOriginLocal per destroyed instance (see PrimitiveVoxelizer.
    // Result's own doc) - needed every frame to place that instance's
    // voxel mesh correctly, alongside its ORIGINAL position/rotation
    // (which destroyedRenderers' own key, the original instance index,
    // still gives access to via scene.instances).
    /** gridOrigin: the voxel grid's own local offset within the
     * instance's local space - fixed at destruction time, doesn't
     * change afterward. No longer stores a captured world transform
     * (an earlier version of this fix did - see
     * PbdRenderer.resolveLiveTransforms's own doc for why that broke
     * "the debris should keep following its door's animation"): the
     * draw loop below calls resolveLiveTransforms() fresh every frame
     * instead, so this only needs to remember which grid this
     * placement belongs to. survivingEntries/voxelWorldSize/gridSize:
     * kept (not discarded once the mesh is built) so a SECOND hit on
     * the same debris can carve a NEW crater out of what's actually
     * left, rather than either doing nothing (the previous, reported
     * behavior) or re-voxelizing the ORIGINAL primitive from scratch
     * (which would undo the first hit's own damage). */
    private static final class DestroyedPlacement {
        final org.joml.Vector3f gridOrigin;
        java.util.List<int[]> survivingEntries;
        final float voxelWorldSize;
        final int gridSize;
        DestroyedPlacement(org.joml.Vector3f gridOrigin, java.util.List<int[]> survivingEntries, float voxelWorldSize, int gridSize) {
            this.gridOrigin = gridOrigin;
            this.survivingEntries = survivingEntries;
            this.voxelWorldSize = voxelWorldSize;
            this.gridSize = gridSize;
        }
    }
    private static final java.util.Map<Integer, DestroyedPlacement> destroyedGridOrigins = new java.util.HashMap<>();
    private static float hideTransitionT = 1f; // 0=just started, 1=transition complete (camera fully at rest at its current target)
    private static final float HIDE_TRANSITION_SECONDS = 0.6f;
    private static Vector3f hideStartPos, hideTargetPos;
    private static float hideStartYaw, hideTargetYaw, hideStartPitch, hideTargetPitch;
    // Blender scale=(0.2,0.2,0.2) on a native cube, measured by hand
    // against what actually feels big enough to crouch into - see the
    // roadmap item this came from. That Blender scale exports to 0.4
    // world units per axis (verified against the real export pipeline,
    // not assumed - Blender's own obj.dimensions after that scale is
    // 0.4, and 'cube' writes dimensions directly as scale=, no further
    // factor applied), which is what this compares against directly
    // since instanceWorldSize() already returns world-space extent.
    private static final float MIN_HIDE_DIMENSION = 0.4f;

    private static final boolean[] keysDown = new boolean[GLFW_KEY_LAST + 1];
    private static final boolean[] keysJustPressed = new boolean[GLFW_KEY_LAST + 1];

    /** Promoted from a runPbdViewer-local variable to a static field
     * specifically so the new getContainerItems/removeContainerItem
     * facade methods below can reach it - a local variable has no way
     * to be queried from outside the exact stack frame it's declared
     * in, which a facade meant to be called independently of the game
     * loop's own call stack fundamentally can't work with. Cleared (not
     * re-declared) at the top of each runPbdViewer call, since N/B
     * scene-cycling re-enters that method for a new scene, and a
     * PREVIOUS scene's own opened containers have no meaning once
     * everything they refer to (scene.instances, worldTransforms, ...)
     * has been replaced. */
    private static final java.util.Map<java.util.List<Integer>, pbd.pz.ContainerContents> containerContents = new java.util.HashMap<>();

    /** Every currently-loaded item across every open container, right
     * now - the "getter" half of the requested container facade.
     * Empty if nothing is open. Each entry: the item's own source file
     * basename (what it actually is - see PlacedItem.sourceFile) and
     * its current world position (itemWorldPosition, the same
     * computation E's own raycast and the render loop both already use
     * - see removeNearestToRay's own doc for why this can't be a
     * cached/stale value: a container's own box can move). */
    public static java.util.List<ContainerItemInfo> getContainerItems() {
        java.util.List<ContainerItemInfo> result = new java.util.ArrayList<>();
        for (pbd.pz.ContainerContents contents : containerContents.values()) {
            for (pbd.pz.ContainerContents.PlacedItem item : contents.items) {
                result.add(new ContainerItemInfo(item.sourceFile, item.currentX, item.currentY, item.currentZ,
                    item.bounds.width, item.bounds.height, item.bounds.depth, contents));
            }
        }
        return result;
    }

    /** One entry from getContainerItems() above - sourceFile identifies
     * WHICH item (matches PlacedItem.sourceFile, e.g. "Bucket.FBX"),
     * x/y/z is its own CURRENT local position within its container
     * (before the container's own world transform - matching what
     * PlacedItem itself stores, not a fully-resolved world position,
     * since a container can move and this value should stay meaningful
     * relative to it). width/height/depth: this item's own full extent
     * on each axis (PlacedItem.bounds' own fields) - added specifically
     * so availableVolume below can sum real item volumes through this
     * same facade method rather than reaching into PlacedItem directly
     * (which stays otherwise unexposed - its mesh/other internals are
     * still implementation detail this facade has no reason to
     * expose). */
    public record ContainerItemInfo(String sourceFile, float x, float y, float z,
                                     float width, float height, float depth, pbd.pz.ContainerContents owner) {}

    /** The container's own bounding-box volume (scene.SceneHandle's own
     * volume() would give the same number for this instance - recomputed
     * directly here instead of reaching back into that facade class, to
     * keep this runtime-state method self-contained) MINUS the summed
     * volume of every item CURRENTLY placed in it (getContainerItems()
     * above) - the actual available free space, not just the raw
     * container size. Requested specifically to drive canHide below:
     * whether there's room to hide depends on what's ALREADY in there,
     * not just the container's own total capacity. Returns the
     * container's own full volume (nothing subtracted) if it isn't
     * currently open/tracked at all (containerContents has no entry for
     * it) - not 0, since an unopened container isn't "full", it's just
     * not known to have anything in it yet. */
    public static float availableVolume(String containerInstanceName) {
        pbd.format.PbdInstance container = null;
        for (pbd.format.PbdInstance i : currentSceneInstances()) {
            if (i.id.equals(containerInstanceName)) { container = i; break; }
        }
        if (container == null) return 0f;
        float containerVolume = container.scale.x * container.scale.y * container.scale.z;
        float usedVolume = 0f;
        for (ContainerItemInfo item : getContainerItems()) {
            usedVolume += item.width() * item.height() * item.depth();
        }
        return Math.max(0f, containerVolume - usedVolume);
    }

    /** Whether there's plausibly enough free room in containerInstanceName
     * to hide in it - availableVolume (above) compared against a rough
     * player-hitbox volume. playerHitboxVolume is a parameter, not a
     * hardcoded constant, deliberately: this project's own player
     * capsule/hitbox dimensions live in the actual gameplay/physics
     * code this facade method has no reach into (and shouldn't - a
     * facade method computing container capacity has no business also
     * hardcoding player size, which could change independently) - a
     * caller (the actual hide-trigger logic) passes its own real
     * figure in. */
    public static boolean canHide(String containerInstanceName, float playerHitboxVolume) {
        return availableVolume(containerInstanceName) >= playerHitboxVolume;
    }

    /** Small helper so availableVolume above doesn't need its own
     * separate way to reach "the currently loaded scene's own
     * instances" - reuses whatever runPbdViewer's own local `scene`
     * variable last set here. Static, matching every other piece of
     * this runtime facade (getContainerItems, removeContainerItem,
     * containerContents itself). */
    private static java.util.List<pbd.format.PbdInstance> currentSceneInstances() {
        return currentScene != null ? currentScene.instances : java.util.List.of();
    }

    /** Set once per scene load (see runPbdViewer's own upload() call
     * site) so currentSceneInstances() above - and any future facade
     * method needing "the scene right now" without an explicit
     * parameter - has somewhere to read it from. */
    private static pbd.format.PbdScene currentScene;

    /** The "setter" half - removes ONE item (the first one whose own
     * sourceFile basename matches, case-sensitively) from whichever
     * open container currently holds it. Returns true if something was
     * actually removed. The programmatic equivalent of E's own
     * raycast-nearest removal, minus the raycast and the "nearest to
     * the camera" requirement - useful on its own (a mod that wants to
     * take a SPECIFIC named item, not whatever's closest to the
     * player), and useful for the same isolation purpose setOpen() on
     * PbdRenderer serves: calling this directly tests whether
     * CONTAINER REMOVAL ITSELF works, independent of whether the
     * raycast that's SUPPOSED to trigger it does. */
    public static boolean removeContainerItem(String sourceFile) {
        for (pbd.pz.ContainerContents contents : containerContents.values()) {
            for (var it = contents.items.iterator(); it.hasNext(); ) {
                pbd.pz.ContainerContents.PlacedItem item = it.next();
                if (item.sourceFile.equals(sourceFile)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mouseClicked = false;   // edge-detected in the main loop, same idea as justPressed for keys
    private static boolean leftMouseDown = false;

    // In-scene time, controlled by the arrow keys (see the loop below).
    // Day 190 = July 9, Project Zomboid's default start date; hour starts
    // mid-morning so the scene isn't dark on launch. Rates are per second
    // while the key is held, not per press, matching WASD's feel - a full
    // day cycles in ~12s (UP/DOWN), a full year in ~12s (LEFT/RIGHT).
    private static double hourOfDay = 9.0;
    private static double dayOfYear = 190.0;
    private static double totalDaysElapsed = 0.0; // cumulative, never wraps - available for any future channel/mechanism that wants total elapsed time rather than a condition-gated one like humidity; dayOfYear wraps every 365 days and can't represent this on its own
    private static final double HOUR_RATE_PER_SEC = 2.0;
    private static final double DAY_RATE_PER_SEC = 30.0;
    private static final double TIME_LAPSE_DAYS_PER_SEC = 20.0; // T key - faster than holding RIGHT, specifically so days-long growth is watchable in a reasonable amount of real time

    // Keyframe playback state now lives per-instance in PbdRenderer (see
    // instanceAnimTime/instanceOpen there) - toggled by a mouse click,
    // not a shared clock, so independent containers open/close on their
    // own schedule.

    public static void main(String[] args) throws Exception {
        Path scenePath = args.length > 0
            ? Path.of(args[0])
            : pbd.pz.PbdPaths.DEFAULT_TILE_GEOMETRY_INPUT;

        boolean isObj = scenePath.toString().toLowerCase().endsWith(".obj");
        boolean isFbx = scenePath.toString().toLowerCase().endsWith(".fbx");
        boolean isTxt = scenePath.toString().toLowerCase().endsWith(".txt");

        if (isTxt) {
            pbd.pz.TileGeometryParser parser = new pbd.pz.TileGeometryParser();
            pbd.pz.TileGeometryConverter converter = new pbd.pz.TileGeometryConverter();
            Path outputDir = pbd.pz.PbdPaths.DEFAULT_TILE_GEOMETRY_OUTPUT;

            converter.convertToDirectory(parser.parseFile(scenePath), outputDir);
        } else if (isFbx) {
            runFbxViewer(scenePath);
        } else {
            if (isObj) {
                runObjViewer(scenePath);
            } else {
                // runPbdViewer returns the next scene to load (N/B keys
                // cycle sibling .pbd files in the same directory) or
                // null on a normal quit - looping here reuses the
                // existing try-with-resources startup/teardown for each
                // scene rather than attempting to hot-swap GPU
                // resources (texture arrays, buffers) for a totally
                // different scene mid-run, which would be a much
                // riskier change to get right without being able to
                // test it interactively.
                Path next = scenePath;
                while (next != null) {
                    next = runPbdViewer(next);
                }
            }
        }
    }

    // ---- PBD path -----------------------------------------------------------

    /** Returns the next scene to switch to (N/B cycling - see the
     * comment at this method's call site), or null if the window was
     * closed normally (Escape). */
    private static Path runPbdViewer(Path scenePath) throws Exception {
        pbd.PbdEngine engine = new pbd.PbdEngine();
        pbd.PbdEngine.SceneHandle sceneHandle = engine.loadAny(scenePath);
        PbdScene scene = sceneHandle.raw();
        PrimitiveRegistry primitiveRegistry = engine.primitiveRegistry();
        ModifierRegistry modifierRegistry = engine.modifierRegistry();
        MaterialRegistry materialRegistry = engine.materialRegistry();
        // The actual, on-disk size of whatever file this loaded - shown
        // on the benchmark overlay below because "how heavy is a real
        // scene" is the single most concrete number this format's own
        // pitch rests on, and the overlay used to have no size figure
        // in it at all despite that being the headline claim.
        long sourceFileBytes;
        try {
            sourceFileBytes = java.nio.file.Files.size(scenePath);
        } catch (IOException e) {
            sourceFileBytes = -1; // shouldn't happen (we just loaded this same file), but the overlay itself shouldn't crash the viewer if it somehow does
        }

        final int width = 1280;
        final int height = 800;
        Path nextScenePath = null;

        try (GlWindow window = new GlWindow(width, height, "PBD viewer - " + scenePath.getFileName());
             PbdRenderer renderer = new PbdRenderer(Path.of("src/main/resources/shaders/pbd"));
             SkydomeRenderer sky = new SkydomeRenderer(Path.of("src/main/resources/shaders/sky"),
                 Path.of("src/main/resources/textures/night_sky.jpg"));
             TextRenderer text = new TextRenderer(Path.of("src/main/resources/shaders/text"))) {

            renderer.upload(scene, primitiveRegistry, modifierRegistry, materialRegistry);
            currentScene = scene; // see this field's own doc - availableVolume/canHide (the runtime facade) need somewhere to read "the scene right now" from
            // Prints ONCE per scene load, unconditionally - the single
            // most useful line if E/G/H logging is reported as never
            // appearing at all: if this ALSO doesn't show up, the
            // running jar predates this source entirely (a stale
            // build, not a bug in the key-handling code itself) - a
            // question worth settling in one obvious line rather than
            // continuing to reason about justPressed()/key-check
            // placement against code that might not even be what's
            // actually running.
            System.out.println("[Build] pbd-benchmark E/G/H diagnostic build - if you don't see per-frame [KeyState] lines below within a few seconds, rebuild before testing further");
            applyLodPreset(renderer);
            pbd.audio.SoundPlayer soundPlayer = new pbd.audio.SoundPlayer(Path.of("src/main/resources/sounds"));
            renderer.soundPlayer = soundPlayer;
            // Every distinct sound any keyframe references, decoded once
            // right here rather than lazily on whatever click first
            // needs each one - see SoundPlayer.preload's own doc for
            // why the lazy version caused a real, measured mistiming
            // ("not quite locked to the first frame").
            java.util.Set<String> soundsToPreload = new java.util.LinkedHashSet<>();
            for (pbd.format.PbdInstance inst : scene.instances) {
                for (pbd.format.PbdInstance.Keyframe kf : inst.keyframes) {
                    if (kf.sound != null) soundsToPreload.add(kf.sound);
                }
            }
            for (String soundFile : soundsToPreload) soundPlayer.preload(soundFile);

            // Generic named-channel system (see pbd.render.ChannelTracker)
            // - "humidity" is a CONCRETE example wired up here, not
            // something the tracker itself knows about: it advances by
            // however many days dayOfYear moves forward THIS frame,
            // but only while isRaining() says today is wet - "2 days
            // under rain = +2 humidity-days" holds regardless of how
            // fast those days were fast-forwarded through (holding
            // RIGHT vs the T time-lapse key). isRaining() is an honest
            // placeholder (a deterministic pattern over dayOfYear, not
            // real weather simulation - this project has no weather
            // system yet); swap its implementation out once one exists,
            // nothing else here needs to change when that happens.
            pbd.render.ChannelTracker channelTracker = new pbd.render.ChannelTracker();

            // "mesh" primitive instances (traditional, non-procedural
            // triangulated geometry - see pbd.format.PbdMeshData) render
            // via the classic-mesh path, same as FBX/container items,
            // rather than PbdRenderer's own tessellation pipeline, which
            // is built for PROCEDURAL surfaces this data isn't.
            //
            // meshInstanceCurrentTier tracks which LOD tier (0 = base
            // meshData, or a key from meshDataByLod) is CURRENTLY built
            // into meshInstanceRenderers for each instance index, so the
            // -/+ handling further down only rebuilds a renderer when
            // the tier it should be showing actually changed, not every
            // single frame.
            java.util.Map<Integer, ClassicMeshRenderer> meshInstanceRenderers = new java.util.HashMap<>();
            java.util.Map<Integer, Integer> meshInstanceCurrentTier = new java.util.HashMap<>();
            for (int i = 0; i < scene.instances.size(); i++) {
                pbd.format.PbdInstance inst = scene.instances.get(i);
                if (inst.type.equals("mesh") && inst.meshData != null) {
                    int tier = desiredMeshLodTier(inst, lodIndex);
                    ClassicMeshRenderer meshRenderer = buildMeshRenderer(inst, scene, tier);
                    if (meshRenderer != null) {
                        meshInstanceRenderers.put(i, meshRenderer);
                        meshInstanceCurrentTier.put(i, tier);
                    }
                }
            }

            // Bin-packed container contents (see pbd.pz.ContainerContents)
            // - grouped by shared door set, not by individual metadata
            // cube: several storage cubes controlled by the exact same
            // door(s) become ONE group with ONE ContainerContents (one
            // door opening several compartments, several doors opening
            // one shared compartment, or both at once). This groups by
            // EXACT trigger-set equality, which is a simplification of
            // full connected-components graph analysis (it wouldn't
            // merge two boxes that share only SOME of their doors) - not
            // yet needed by any scene actually built, but worth flagging
            // as the corner this cuts. Computed lazily, cached from then
            // on (this project's own rule: no computation or display
            // before the container opens). Each item gets its own
            // ClassicMeshRenderer (created once, reused every frame),
            // reusing the existing classic-mesh pipeline for FBX meshes.
            java.util.List<java.util.List<Integer>> containerGroups = new java.util.ArrayList<>();
            {
                java.util.Map<String, java.util.List<Integer>> groupByTriggerKey = new java.util.LinkedHashMap<>();
                for (int i = 0; i < scene.instances.size(); i++) {
                    if (!"true".equals(scene.instances.get(i).params.get("metadata"))) continue;
                    java.util.List<String> triggers = new java.util.ArrayList<>(scene.instances.get(i).containerTriggers);
                    java.util.Collections.sort(triggers);
                    String key = String.join("|", triggers);
                    groupByTriggerKey.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(i);
                }
                containerGroups.addAll(groupByTriggerKey.values());
            }
            // Mixed into every container group's own seed below (see
            // "long seed = ...") so the exact SAME group of boxes picks a
            // DIFFERENT arrangement each time the engine is launched -
            // for testing variety - while staying perfectly stable
            // within a single run's open/close cycles (this value never
            // changes after this line, so re-closing and reopening the
            // same door during the same session still shows the same
            // items, satisfying the original "predictable, keeps its
            // position after cache rewarming" rule - that rule was
            // always about one session, not about every launch forever
            // producing bit-identical results, which would make manual
            // testing across runs impossible).
            long sessionSeed = new java.util.Random().nextLong();
            containerContents.clear(); // see this field's own doc: cleared, not re-declared, so N/B scene-cycling starts fresh rather than carrying a previous scene's open containers forward
            // Same reasoning, and a REAL confirmed bug this turn: neither
            // of these was ever cleared on a scene switch either - a
            // destroyed instance's own index from the PREVIOUS scene
            // (say, #5 out of 7 instances) stayed in these maps into the
            // NEXT scene, which could easily have fewer instances (or
            // none at all) - the very next frame's draw loop or
            // resolveLiveTransforms() call would then index into the
            // NEW scene's own, differently-sized arrays with a stale
            // index from the old one, an ArrayIndexOutOfBoundsException
            // waiting to happen. Each renderer properly .close()'d
            // first (same GL-resource-leak reasoning as every other
            // removal/replacement this session) rather than just
            // dropped.
            for (ClassicMeshRenderer r : destroyedRenderers.values()) r.close();
            destroyedRenderers.clear();
            destroyedGridOrigins.clear();
            java.util.Map<pbd.pz.ContainerContents.PlacedItem, ClassicMeshRenderer> itemRenderers = new java.util.HashMap<>();

            FlyCamera camera = new FlyCamera();
            InputState input = new InputState(window.handle());

            glClearColor(0.05f, 0.05f, 0.08f, 1f);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            float smoothedFps = 60f;
            float keyStateLogTimer = 0f;

            while (!window.shouldClose()) {
                float dt = input.beginFrame(window);
                input.applyLookAndMove(camera, dt);

                // Raw keysDown state (NOT justPressed's own edge-detected
                // "was it pressed THIS frame" logic) for E/G/H
                // specifically, printed every ~2 seconds regardless of
                // whether anything was pressed - deliberately independent
                // of justPressed() so this can't be hiding the same bug
                // it would be checking for. If E/G/H logging is reported
                // as silent, this line confirms two separate things at
                // once: the loop is alive at all (it prints on its own,
                // unprompted), and whether physically holding one of
                // these keys down ever flips its OWN raw boolean to true
                // - if it doesn't, the problem is upstream of this whole
                // file, in GLFW's own key callback registration; if it
                // does, but justPressed(...) still never fires an
                // if-block, the bug is downstream of the raw callback -
                // was, definitively, InputState.endFrame()'s own former
                // call site (see that method's own doc): kept as a
                // useful independent cross-check even now that the
                // actual root cause is known and fixed.
                keyStateLogTimer += dt;
                if (keyStateLogTimer >= 2.0f) {
                    keyStateLogTimer = 0f;
                    System.out.println("[KeyState] raw E=" + keysDown[GLFW_KEY_E]
                        + " G=" + keysDown[GLFW_KEY_G] + " H=" + keysDown[GLFW_KEY_H]
                        + " (this line prints every ~2s regardless of input - if you never see it at all, the loop itself isn't running this build)");
                }

                if (justPressed(GLFW_KEY_N)) {
                    nextScenePath = siblingPbdFile(scenePath, +1);
                    if (nextScenePath != null) break;
                }
                if (justPressed(GLFW_KEY_B)) {
                    nextScenePath = siblingPbdFile(scenePath, -1);
                    if (nextScenePath != null) break;
                }
                if (justPressed(GLFW_KEY_EQUAL) && lodIndex < LOD_PRESETS.length - 1) {
                    lodIndex++;
                    applyLodPreset(renderer);
                    rebuildMeshRenderersForLod(scene, meshInstanceRenderers, meshInstanceCurrentTier, lodIndex);
                }
                if (justPressed(GLFW_KEY_MINUS) && lodIndex > 0) {
                    lodIndex--;
                    applyLodPreset(renderer);
                    rebuildMeshRenderersForLod(scene, meshInstanceRenderers, meshInstanceCurrentTier, lodIndex);
                }
                if (justPressed(GLFW_KEY_C)) {
                    renderer.useCache = !renderer.useCache;
                }
                if (justPressed(GLFW_KEY_F3) || justPressed(GLFW_KEY_F5)) {
                    showBenchmarkOverlay = !showBenchmarkOverlay;
                }
                // Time of day / season. Held, not tapped, for continuous
                // scrubbing - same feel as WASD.
                double dayOfYearBeforeThisFrame = dayOfYear;
                if (keysDown[GLFW_KEY_UP])    hourOfDay += HOUR_RATE_PER_SEC * dt;
                if (keysDown[GLFW_KEY_DOWN])  hourOfDay -= HOUR_RATE_PER_SEC * dt;
                if (keysDown[GLFW_KEY_RIGHT]) { dayOfYear += DAY_RATE_PER_SEC * dt; totalDaysElapsed += DAY_RATE_PER_SEC * dt; }
                if (keysDown[GLFW_KEY_LEFT])  dayOfYear -= DAY_RATE_PER_SEC * dt;
                // T: fast multi-day time-lapse, specifically for
                // watching growthDays-tagged vegetation (moss, mushrooms)
                // grow in - MUCH faster than holding LEFT/RIGHT, which
                // moves the season along too slowly to see days-long
                // growth happen in a reasonable amount of real time.
                // Advances the SAME dayOfYear the season depends on, not
                // a separate clock, so the two never drift apart.
                if (keysDown[GLFW_KEY_T]) {
                    double advance = TIME_LAPSE_DAYS_PER_SEC * dt;
                    dayOfYear += advance;
                    totalDaysElapsed += advance;
                }
                // humidity channel: however many days dayOfYear just
                // advanced (positive movement only - winding time
                // backward doesn't un-rain), added only while isRaining()
                // says today is wet.
                double daysAdvancedThisFrame = dayOfYear - dayOfYearBeforeThisFrame;
                if (daysAdvancedThisFrame > 0 && isRaining()) {
                    channelTracker.set("humidity", channelTracker.get("humidity") + daysAdvancedThisFrame);
                }
                // Hour wrapping carries into the day counter, same as a
                // real clock rolling into the next date at midnight -
                // this was missing before: UP/DOWN and LEFT/RIGHT were
                // fully independent, so cycling the hour past midnight
                // (very reachable at 2h of simulated time per real
                // second) never advanced the day at all.
                while (hourOfDay >= 24.0) { hourOfDay -= 24.0; dayOfYear += 1.0; }
                while (hourOfDay < 0.0)   { hourOfDay += 24.0; dayOfYear -= 1.0; }
                dayOfYear = ((dayOfYear % 365.0) + 365.0) % 365.0;

                // Cursor is locked to center (see InputState's
                // GLFW_CURSOR_DISABLED, needed for fly-camera look), so a
                // click naturally means "whatever's under the crosshair
                // at screen center" - a straight-forward ray from the
                // camera, not an arbitrary mouse position.
                if (consumeClick()) {
                    renderer.toggleKeyframedInstanceAlongRay(camera.position, camera.forward());
                }

                // Advances every keyframed instance toward its own
                // current open/closed target (see toggleKeyframedInstanceAlongRay
                // above, triggered by a mouse click) - a no-op for a
                // scene with no animated instances at all.
                renderer.updateAnimation(dt);
                channelTracker.update(dt); // advances any channel with a registered Rule (real-time-rate based) - humidity itself is set directly above, not via a Rule, but this keeps the mechanism ready for a future channel that DOES want a fixed real-time rate
                if (renderer.hasChannelDrivenInstances()) renderer.updateChannelDrivenInstances(channelTracker);

                input.endFrame();

                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                SolarCalculator.SunPosition sunPos = SolarCalculator.compute(
                    (int) dayOfYear, hourOfDay, SolarCalculator.KNOX_COUNTY_LATITUDE_DEG);
                float[] sunDir = SolarCalculator.toDirection(sunPos);
                // Civil twilight band: full day above +6 deg, full night
                // below -6 deg, smoothed in between - GPU-verified during
                // development (see README).
                float dayFactor = (float) Math.max(0.0, Math.min(1.0, (sunPos.altitudeDeg() + 6.0) / 12.0));
                dayFactor = dayFactor * dayFactor * (3 - 2 * dayFactor); // smoothstep
                // Stars track the sun's hour-angle rotation (so the sky
                // visibly turns through the night) plus a slower drift
                // across the year (~1 turn per 365 days) approximating the
                // solar/sidereal day difference - not full sidereal
                // precision, just enough that the same hour looks
                // different across seasons.
                float starRotation = (float) Math.toRadians(15.0 * (hourOfDay - 12.0) + 360.0 / 365.0 * dayOfYear);
                sky.render(camera, (float) width / height, sunDir, dayFactor, starRotation);

                // The sun is also the scene's light source - was a fixed
                // direction before, completely disconnected from the
                // skydome's own sun position (so the sky would show
                // night while every object stayed lit as if at noon).
                // -sunDir since lightDir is "the direction light travels
                // toward the surface" (pbd.frag negates it back to get
                // "toward the light"), matching the convention the old
                // fixed value already used.
                renderer.lightDir = new float[]{-sunDir[0], -sunDir[1], -sunDir[2]};

                renderer.render(camera, (float) width / height);

                for (var entry : meshInstanceRenderers.entrySet()) {
                    Matrix4f world = renderer.instanceWorldMatrix(entry.getKey());
                    entry.getValue().render(camera, (float) width / height, world);
                }

                // Voxel-destroyed instances: the ORIGINAL is hidden (see
                // hideInstance, called when 'G' destroyed it), its voxel
                // mesh renders here instead. translate+rotateZYX+translate,
                // NOT instanceWorldMatrix's own full transform - the mesh
                // is already in world-SCALE units (see VoxelMeshBuilder),
                // so re-applying the instance's own scale here would
                // double it. Z,Y,X rotation order matches every other
                // rotation build in this codebase (see PbdRenderer's own
                // pose-interpolation code) - and this exact transform
                // (position + that rotation order + gridOriginLocal) was
                // verified against a real placement test before being
                // wired in here, not just derived on paper.
                // is already in world-SCALE units (see VoxelMeshBuilder),
                // so re-applying the live transform's own scale here
                // would double it - extractRotationRobust (below) gets
                // JUST the rotation, discarding scale, WITHOUT the bug
                // JOML's own getNormalizedRotation() turned out to have:
                // confirmed, via a direct numeric test against this
                // exact file's own cube.018 (scale 0.02/0.304/0.544 - a
                // 27:1 ratio between its smallest and largest axis),
                // that getNormalizedRotation() on a matrix with THIS
                // degree of non-uniform scale returns something that
                // isn't even a valid unit quaternion (x^2+y^2+z^2+w^2
                // came out to ~0.43, not 1) - producing a visibly wrong
                // rotation for every voxel-destroyed instance with
                // strongly non-uniform scale, which describes most of
                // this project's own thin door/panel assets. This is
                // the actual, confirmed cause of a real reported
                // "rotations and scales are wrong" bug - not a
                // hypothesis, a reproduced and fixed one.
                // resolveLiveTransforms() called ONCE per frame here (not
                // once per destroyed entry - see that method's own doc on
                // why it isn't cheap), only when there's actually
                // something destroyed to draw.
                if (!destroyedRenderers.isEmpty()) {
                    Matrix4f[] liveTransforms = renderer.resolveLiveTransforms();
                    for (var entry : destroyedRenderers.entrySet()) {
                        DestroyedPlacement placement = destroyedGridOrigins.get(entry.getKey());
                        Matrix4f liveWorld = liveTransforms[entry.getKey()];
                        Matrix4f world = new Matrix4f()
                            .translate(liveWorld.getTranslation(new Vector3f()))
                            .rotate(extractRotationRobust(liveWorld))
                            .translate(placement.gridOrigin);
                        entry.getValue().render(camera, (float) width / height, world);
                    }
                }

                // Bin-packed container contents (see pbd.pz.ContainerContents
                // and pbd.pz.BinPacker) - lazily computed and cached per
                // GROUP of boxes sharing a door set, per this project's
                // own rule: no computation or display before at least one
                // linked door is open.
                for (java.util.List<Integer> group : containerGroups) {
                    boolean anyOpen = group.stream().anyMatch(idx -> renderer.isContainerOpen(idx));
                    if (anyOpen && !containerContents.containsKey(group)) {
                        java.util.List<pbd.pz.BinPacker.ContainerVolume> volumes = new java.util.ArrayList<>();
                        for (int idx : group) {
                            Vector3f size = renderer.instanceWorldSize(idx);
                            volumes.add(new pbd.pz.BinPacker.ContainerVolume(size.x, size.y, size.z));
                        }
                        // Deterministic seed: a stable hash of the group's
                        // own instance ids (sorted, joined) - NOT
                        // Random()'s default time-based seed, which would
                        // re-roll a different selection on every single
                        // run and break "predictable, keeps its
                        // arrangement" (see ContainerContents.loadRandom's
                        // own doc comment for the full reasoning).
                        java.util.List<String> ids = group.stream().map(idx -> scene.instances.get(idx).id).sorted().toList();
                        long seed = sessionSeed ^ String.join("|", ids).hashCode();
                        pbd.pz.ContainerContents contents = pbd.pz.ContainerContents.loadRandom(
                            pbd.pz.PbdPaths.FBX_DIR, 50, group, volumes, seed);
                        containerContents.put(group, contents);
                        System.out.println("[Container] Loaded " + contents.items.size()
                            + " item(s) across " + group.size() + " box(es): " + ids);
                    } else if (!anyOpen && containerContents.containsKey(group)
                               && group.stream().allMatch(idx -> renderer.areAllDoorsClosed(idx))) {
                        // All linked doors now closed - evict so the next
                        // open recomputes fresh (same deterministic seed,
                        // same items reappear) instead of what was shown
                        // once staying rendered forever. Also releases the
                        // per-item GL renderers (VBO/VAO), or they'd leak
                        // across every future open/close cycle.
                        pbd.pz.ContainerContents evicted = containerContents.remove(group);
                        for (pbd.pz.ContainerContents.PlacedItem item : evicted.items) {
                            ClassicMeshRenderer r = itemRenderers.remove(item);
                            if (r != null) r.close();
                        }
                        System.out.println("[Container] Closed - cleared " + evicted.items.size()
                            + " item(s) across " + group.size() + " box(es)");
                    }
                }

                for (var entry : containerContents.entrySet()) {
                    pbd.pz.ContainerContents contents = entry.getValue();
                    contents.update(dt);

                    java.util.List<Vector3f> boxWorldCenters = new java.util.ArrayList<>();
                    java.util.List<org.joml.Quaternionf> boxWorldRotations = new java.util.ArrayList<>();
                    for (int idx : contents.metaIndices) {
                        boxWorldCenters.add(renderer.instanceWorldCenter(idx));
                        boxWorldRotations.add(renderer.instanceWorldRotation(idx));
                    }

                    for (pbd.pz.ContainerContents.PlacedItem item : contents.items) {
                        ClassicMeshRenderer itemRenderer = itemRenderers.get(item);
                        if (itemRenderer == null) {
                            try {
                                itemRenderer = new ClassicMeshRenderer(Path.of("src/main/resources/shaders/classic"), item.mesh);
                                itemRenderer.baseColor = new float[]{0.7f, 0.55f, 0.35f};
                                itemRenderers.put(item, itemRenderer);
                            } catch (java.io.IOException e) {
                                System.err.println("[Container] Failed to create renderer for " + item.sourceFile + ": " + e.getMessage());
                                continue;
                            }
                        }
                        // itemWorldPosition applies the container's own
                        // rotation to the item's local offset before
                        // adding it to the container's world center -
                        // rotate() below ALSO reorients the item itself
                        // to match, or it would land in the right SPOT
                        // but still visibly face the wrong way inside a
                        // tilted container. Both were missing before
                        // (just a straight translate by the container's
                        // center, correct only when unrotated) - reported
                        // as "objects sometimes stick out of the
                        // bounding box," which a rotated storage cube
                        // (the normal case for anything not wall-aligned)
                        // would produce exactly.
                        Vector3f worldPos = pbd.pz.ContainerContents.itemWorldPosition(item, boxWorldCenters, boxWorldRotations);
                        org.joml.Quaternionf containerRot = boxWorldRotations.get(item.containerIndex);
                        Matrix4f itemWorld = new Matrix4f()
                            .translate(worldPos)
                            .rotate(containerRot)
                            .scale(item.scaleFactor)
                            .translate(-item.localCenterX, -item.localCenterY, -item.localCenterZ);
                        itemRenderer.render(camera, (float) width / height, itemWorld);
                    }
                }

                // E: remove the container item nearest the crosshair ray -
                // same forward-from-camera ray as the door click, but a
                // separate key since left-click is already the door
                // open/close action. Marks whatever was resting above the
                // removed item (in the SAME box) as falling.
                if (justPressed(GLFW_KEY_E)) {
                    // Diagnostic print for the "how many containers are
                    // even open right now" half of the question -
                    // removeNearestToRay itself (see ContainerContents)
                    // logs the per-item ray-test details once it's
                    // actually called; this covers the OTHER way E could
                    // find nothing - zero open containers to even check.
                    System.out.println("[E-key] " + containerContents.size() + " open container group(s) to check"
                        + (containerContents.isEmpty() ? " - nothing is open right now" : ""));
                    for (var entry : containerContents.entrySet()) {
                        pbd.pz.ContainerContents contents = entry.getValue();
                        java.util.List<Vector3f> boxWorldCenters = new java.util.ArrayList<>();
                        java.util.List<org.joml.Quaternionf> boxWorldRotations = new java.util.ArrayList<>();
                        for (int idx : contents.metaIndices) {
                            Vector3f center = renderer.instanceWorldCenter(idx);
                            org.joml.Quaternionf rot = renderer.instanceWorldRotation(idx);
                            boxWorldCenters.add(center);
                            boxWorldRotations.add(rot);
                            // The container's OWN transform, fresh every
                            // press - if the box holding these items has
                            // moved/rotated since the items were placed
                            // (or if instanceWorldCenter/Rotation return
                            // something clearly wrong - NaN, a position
                            // nowhere near where this container visually
                            // is), that would surface right here, BEFORE
                            // ever reaching a single item's own distance
                            // check inside removeNearestToRay.
                            System.out.println("  [E-key] container instance #" + idx + " world center=" + center
                                + " rotation(xyzw)=" + rot.x + "," + rot.y + "," + rot.z + "," + rot.w);
                        }
                        System.out.println("  [E-key] group has " + contents.items.size() + " item(s) placed in it");
                        String removed = contents.removeNearestToRay(boxWorldCenters, boxWorldRotations, camera.position, camera.forward());
                        if (removed != null) {
                            System.out.println("[Container] Removed " + removed);
                            break; // one item per press, from whichever open container's ray hit first
                        }
                    }
                }

                // H: hide inside whichever metadata (container) bounding
                // box the crosshair is on - press again from inside to
                // come back out. A short (HIDE_TRANSITION_SECONDS)
                // camera move/turn plays either way rather than an
                // instant teleport, generated from the container's own
                // transform and (going in) its linked doors' average
                // position - not authored per-scene, since every
                // qualifying container gets this for free.
                if (justPressed(GLFW_KEY_H)) {
                    if (hidingMetaIndex < 0) {
                        int target = renderer.findMetadataCubeAlongRay(camera.position, camera.forward());
                        if (target < 0) {
                            System.out.println("[Hide] Not aiming at a container");
                        } else {
                            Vector3f size = renderer.instanceWorldSize(target);
                            if (size.x < MIN_HIDE_DIMENSION || size.y < MIN_HIDE_DIMENSION || size.z < MIN_HIDE_DIMENSION) {
                                System.out.println("[Hide] Too small to hide in (" + String.format("%.2f x %.2f x %.2f", size.x, size.y, size.z)
                                    + ", need at least " + MIN_HIDE_DIMENSION + " on every axis)");
                            } else {
                                startHiding(target, scene, renderer, camera);
                            }
                        }
                    } else {
                        startExitingHide(camera);
                    }
                }
                updateHideTransition(camera, dt);

                // G: "shoot" whatever's under the crosshair - voxelizes
                // it (see pbd.voxel) and swaps its rendering from the
                // main tessellation pass to the resulting voxel mesh,
                // testing the primitive->voxel transform end to end
                // rather than only running it offline. One-shot per
                // instance - a second G on an already-destroyed one just
                // finds nothing there anymore (findDestructibleAlongRay
                // has no geometry left to hit once hideInstance has
                // zeroed its transform).
                if (justPressed(GLFW_KEY_G)) {
                    int target = renderer.findDestructibleAlongRay(camera.position, camera.forward());
                    // findDestructibleAlongRay only tests scene.instances'
                    // own (possibly zeroed-by-hideInstance) worldTransforms
                    // - an ALREADY-destroyed instance's original hitbox
                    // effectively vanished along with its zeroed scale, so
                    // aiming at its OWN debris afterward would otherwise
                    // always report target<0 ("nothing destructible"),
                    // exactly the reported "can't hit a voxel a second
                    // time" bug. Checked here, separately, ONLY when the
                    // primary raycast found nothing new: a simple ray-
                    // sphere test against each already-destroyed
                    // instance's own CURRENT live center (not its zeroed
                    // worldTransforms - resolveLiveTransforms(), same
                    // source the draw loop itself now uses, so a second
                    // hit lands on wherever the debris ACTUALLY is this
                    // frame, mid-swing included), radius approximated from
                    // that instance's own original scale.
                    int retargetIndex = -1;
                    if (target < 0 && !destroyedRenderers.isEmpty()) {
                        Matrix4f[] live = renderer.resolveLiveTransforms();
                        float closestRetargetDist = Float.MAX_VALUE;
                        for (int idx : destroyedRenderers.keySet()) {
                            Vector3f center = live[idx].getTranslation(new Vector3f());
                            pbd.format.PbdInstance destroyedInst = scene.instances.get(idx);
                            float radius = 0.5f * (float) Math.sqrt(
                                destroyedInst.scale.x * destroyedInst.scale.x
                                + destroyedInst.scale.y * destroyedInst.scale.y
                                + destroyedInst.scale.z * destroyedInst.scale.z);
                            Vector3f toCenter = center.sub(camera.position, new Vector3f());
                            float alongRay = toCenter.dot(camera.forward());
                            if (alongRay < 0) continue; // behind the camera
                            Vector3f closestPointOnRay = new Vector3f(camera.forward()).mul(alongRay).add(camera.position);
                            float distToRay = closestPointOnRay.distance(center);
                            if (distToRay <= radius && alongRay < closestRetargetDist) {
                                closestRetargetDist = alongRay;
                                retargetIndex = idx;
                            }
                        }
                    }

                    if (retargetIndex >= 0) {
                        DestroyedPlacement placement = destroyedGridOrigins.get(retargetIndex);
                        pbd.format.PbdInstance inst = scene.instances.get(retargetIndex);
                        Matrix4f liveWorld = renderer.resolveLiveTransforms()[retargetIndex];
                        // Same local-space impact-point derivation
                        // findDestructibleAlongRay's own first-hit path
                        // uses, just built by hand here since this ray
                        // never went through that method at all (it tested
                        // scene.instances' own zeroed transform and found
                        // nothing, which is exactly why this branch exists).
                        Matrix4f invLive = new Matrix4f(liveWorld).invert();
                        Vector3f localOrigin = invLive.transformPosition(new Vector3f(camera.position));
                        Vector3f localDir = invLive.transformDirection(new Vector3f(camera.forward()));
                        float t = renderer.rayBoxIntersectionPublic(localOrigin, localDir, -0.5f, 0.5f);
                        // THE fix for a real, reported "shot registers
                        // (SFX plays) but doesn't alter the voxel, about
                        // half the time" bug: the fallback here used to be
                        // localOrigin itself (the camera's OWN position,
                        // transformed into local space) whenever this
                        // finer box test missed - which happens often
                        // once debris has ALREADY been partially carved
                        // by an earlier hit (its real, now-smaller visible
                        // extent no longer fills the full canonical
                        // -0.5..0.5 box this test checks against, even
                        // though the sphere test above - generous, sized
                        // off the ORIGINAL uncarved scale - still
                        // correctly found this as the closest target).
                        // Falling back to the camera's own position
                        // placed the "impact" nowhere near the actual
                        // object, so the crater computed from it removed
                        // nothing from what's really left - exactly a
                        // shot that plays its sound but visibly does
                        // nothing. Falls back to the ray's own closest
                        // approach to the sphere-test's center instead
                        // now - not exact, but a real point near the
                        // object's own actual position, not the
                        // player's.
                        Vector3f localHit;
                        if (t >= 0) {
                            localHit = new Vector3f(localDir).mul(t).add(localOrigin);
                        } else {
                            float alongRay = new Vector3f(0, 0, 0).sub(localOrigin).dot(new Vector3f(localDir).normalize());
                            localHit = new Vector3f(localDir).normalize().mul(Math.max(0, alongRay)).add(localOrigin);
                        }
                        Vector3f impactWorldScaledLocal = new Vector3f(localHit.x * inst.scale.x, localHit.y * inst.scale.y, localHit.z * inst.scale.z);
                        Vector3f impactGrid = impactWorldScaledLocal.sub(placement.gridOrigin, new Vector3f()).div(placement.voxelWorldSize);

                        float craterRadiusVoxels = 14f; // raised from 6 ("bigger alterations" requested) - ~7cm radius at this resolution, not ~3cm
                        java.util.Set<Long> removedSet = computeCraterRemovedSet(impactGrid, craterRadiusVoxels);
                        // outerTestRadius: a plain distance bound (not
                        // ray-based) used ONLY to decide whether a still-
                        // merged region is anywhere NEAR enough to the
                        // blast to be worth expanding for the real,
                        // per-voxel ray-cast test above - rays can reach
                        // a bit further than craterRadiusVoxels on their
                        // own best day (up to *1.4), so this margin
                        // covers that without needing to actually re-run
                        // ray-casting just to decide what to expand.
                        float outerTestRadius = craterRadiusVoxels * 1.4f;
                        java.util.List<int[]> newSurvivors = new java.util.ArrayList<>();
                        int[] removedThisHitBox = {0};
                        for (int[] entry : placement.survivingEntries) {
                            int ex = entry[0], ey = entry[1], ez = entry[2], esize = entry.length > 3 ? entry[3] : 1;
                            applyCraterAdaptive(ex, ey, ez, esize, removedSet, impactGrid, outerTestRadius, newSurvivors, removedThisHitBox);
                        }
                        int removedThisHit = removedThisHitBox[0];
                        placement.survivingEntries = newSurvivors;
                        if (newSurvivors.isEmpty()) {
                            ClassicMeshRenderer consumed = destroyedRenderers.remove(retargetIndex);
                            if (consumed != null) consumed.close(); // same leak as the replacement case just above - freeing the map entry alone doesn't free its GPU resources
                            System.out.println("[Destroy] '" + inst.id + "' -> fully consumed by a follow-up hit (" + removedThisHit + " voxel(s) removed)");
                            soundPlayer.play("doomshotgun.wav");
                        } else {
                            var matFields = scene.materialOverrides.get(inst.material);
                            float[] fallback = {0.62f, 0.63f, 0.66f};
                            if (matFields != null && matFields.get("color") != null) {
                                float[] parsed = parseColorTriple(matFields.get("color"));
                                if (parsed != null) fallback = parsed;
                            }
                            final float[] fallbackColor2 = fallback;
                            java.util.function.Function<int[], float[]> colorFn2 = buildVoxelColorFn(inst, matFields, fallbackColor2, placement.gridSize, placement.voxelWorldSize, renderer);
                            var colored = pbd.voxel.VoxelMeshBuilder.buildMeshWithColor(newSurvivors, placement.voxelWorldSize, colorFn2);
                            pbd.format.PbdInstance voxelInst = new pbd.format.PbdInstance(inst.id + "_voxels", "mesh");
                            voxelInst.meshData = colored.meshData;
                            voxelInst.material = inst.material;
                            ClassicMeshRenderer newRenderer = buildMeshRenderer(voxelInst, scene, 0, colored.colors);
                            if (newRenderer != null) {
                                // Confirmed real GL resource leak, and the
                                // actual cause of a reported "crashes if I
                                // press G too much": destroyedRenderers.put
                                // below REPLACES the map entry, but a
                                // ClassicMeshRenderer owns real GPU
                                // resources (VAO/VBO/IBO, a colorVbo for a
                                // voxel result specifically) that don't get
                                // freed just because the Java reference to
                                // them is dropped - every repeated hit on
                                // the same debris was leaking a full set of
                                // these, accumulating without bound.
                                // destroyedRenderers.put's own OTHER call
                                // site (first-time destruction, further
                                // below) doesn't have this problem since
                                // there's no PREVIOUS renderer to leak yet.
                                ClassicMeshRenderer previous = destroyedRenderers.get(retargetIndex);
                                if (previous != null) previous.close();
                                destroyedRenderers.put(retargetIndex, newRenderer);
                                System.out.println("[Destroy] '" + inst.id + "' -> follow-up hit removed " + removedThisHit + " more voxel(s), " + newSurvivors.size() + " geometry piece(s) remain");
                                soundPlayer.play("doomshotgun.wav");
                            }
                        }
                    } else if (target < 0) {
                        System.out.println("[Destroy] Nothing destructible under the crosshair");
                    } else if (destroyedRenderers.containsKey(target)) {
                        System.out.println("[Destroy] Already destroyed");
                    } else {
                        pbd.format.PbdInstance inst = scene.instances.get(target);
                        // 0.005 world units - this project's own real-
                        // world scale reference (1cm =~ Blender scale
                        // 0.005) used DIRECTLY as the target voxel size,
                        // not as a count - see PrimitiveVoxelizer.voxelize's
                        // own doc for why this alone is what produces
                        // high-definition surface / low-definition
                        // interior (rasterizeAdaptive's existing
                        // insertFilledBox early-return on a fully-
                        // interior region), not a separate mechanism.
                        var voxResult = pbd.voxel.PrimitiveVoxelizer.voxelize(inst, 0.005f);
                        if (voxResult == null) {
                            System.out.println("[Destroy] '" + inst.id + "' can't be voxelized (indestructible, or an unsupported/mesh type)");
                        } else {
                            // renderer.lastHitLocalPoint is in the
                            // instance's CANONICAL (-0.5..0.5, unscaled)
                            // local space - the same space
                            // findDestructibleAlongRay's own rayBoxIntersection
                            // test runs in. Converting to the octree's own
                            // grid-coordinate space needs two steps: scale
                            // up to WORLD-SCALED local units (multiply by
                            // inst.scale - PrimitiveVoxelizer's own sx/sy/sz
                            // ARE instance.scale.x/y/z, confirmed against
                            // its own source), then subtract gridOriginLocal
                            // and divide by voxelWorldSize to land in voxel
                            // units.
                            Vector3f impactWorldScaledLocal = new Vector3f(
                                renderer.lastHitLocalPoint.x * inst.scale.x,
                                renderer.lastHitLocalPoint.y * inst.scale.y,
                                renderer.lastHitLocalPoint.z * inst.scale.z);
                            Vector3f impactGrid = impactWorldScaledLocal.sub(voxResult.gridOriginLocal, new Vector3f())
                                .div(voxResult.voxelWorldSize);

                            // Crater radius in VOXEL units - history:
                            // started at 15 (~7.5cm - reported as too
                            // large, removing far more of a real prop
                            // than a single impact should), reduced to 6
                            // (~3cm - then explicitly requested to be
                            // BIGGER again: "plus grosses alterations"),
                            // now 14 (~7cm) - a fixed voxel-count radius
                            // (not a fixed world-space one) means the
                            // crater's own visual size in voxel-widths
                            // stays consistent regardless of
                            // voxelWorldSize.
                            float craterRadiusVoxels = 14f;
                            // Real ray-cast crater (see
                            // computeCraterRemovedSet's own doc) - reported
                            // TWICE as still looking like "pieces of
                            // spheres with an uncomfortable repetition
                            // pattern" under the previous distance-plus-
                            // noise-jitter approach, which - fairly, in
                            // hindsight - is still fundamentally a sphere
                            // at its core no matter how its edge is
                            // jittered. This is a structurally different
                            // shape, not a smoothed-out version of the
                            // same one.
                            java.util.Set<Long> removedSet = computeCraterRemovedSet(impactGrid, craterRadiusVoxels);
                            float outerTestRadius = craterRadiusVoxels * 1.4f; // rays can reach a bit past craterRadiusVoxels on their own best day - see this same margin's own doc at the other crater call site
                            // Hybrid region/voxel expansion: a region
                            // (collectFilledRegions - possibly large,
                            // merged) survives WHOLE, unexpanded, if its
                            // own AABB can't possibly be within the crater
                            // radius of the impact point - only a region
                            // that COULD overlap gets expanded down to its
                            // own individual unit voxels for precise
                            // per-voxel filtering. Tested against the naive
                            // "expand everything to unit voxels first"
                            // approach this replaced: a 1m cube at this
                            // resolution produced 8,000,000 individual
                            // voxel entries that way - correct, but far
                            // more allocation/iteration than a single
                            // impact crater actually needs, when the
                            // object's own interior almost never needs
                            // per-voxel resolution at all (see
                            // rasterizeAdaptive's own doc: an interior
                            // region is already one single large merged
                            // box, which this keeps merged rather than
                            // pointlessly expanding back out).
                            var allRegions = voxResult.octree.collectFilledRegions();
                            java.util.List<int[]> survivingEntries = new java.util.ArrayList<>(); // each entry is either an untouched region (size>1) or a surviving unit voxel (size=1) - VoxelMeshBuilder's addBox already handles either uniformly
                            int totalVoxelCountForLogging = 0;
                            int[] removedVoxelCountBox = {0};
                            for (int[] region : allRegions) {
                                totalVoxelCountForLogging += region[3] * region[3] * region[3];
                                applyCraterAdaptive(region[0], region[1], region[2], region[3], removedSet, impactGrid, outerTestRadius, survivingEntries, removedVoxelCountBox);
                            }
                            int removedVoxelCountForLogging = removedVoxelCountBox[0];

                            if (survivingEntries.isEmpty()) {
                                // The whole thing was within the crater
                                // radius (a small prop, or a point-blank
                                // hit) - nothing left to render as a voxel
                                // mesh at all; hiding the original is still
                                // correct (it WAS destroyed), just with no
                                // replacement geometry.
                                renderer.hideInstance(target);
                                System.out.println("[Destroy] '" + inst.id + "' -> fully destroyed (" + totalVoxelCountForLogging + " voxel(s), all within the impact radius)");
                                soundPlayer.play("doomshotgun.wav");
                            } else {
                                // Per-voxel color: this material's own flat
                                // color (scene.materialOverrides), the same
                                // one buildMeshRenderer's own fallback path
                                // already reads - NOT yet a real sample of
                                var matFields = scene.materialOverrides.get(inst.material);
                                float[] flatColor = {0.62f, 0.63f, 0.66f}; // ClassicMeshRenderer's own default, same neutral gray a mesh with no matching material override already falls back to
                                if (matFields != null && matFields.get("color") != null) {
                                    float[] parsed = parseColorTriple(matFields.get("color"));
                                    if (parsed != null) flatColor = parsed;
                                }
                                final float[] fallbackColor = flatColor;
                                java.util.function.Function<int[], float[]> colorFn = buildVoxelColorFn(inst, matFields, fallbackColor, voxResult.octree.gridSize(), voxResult.voxelWorldSize, renderer);

                                var colored = pbd.voxel.VoxelMeshBuilder.buildMeshWithColor(
                                    survivingEntries, voxResult.voxelWorldSize, colorFn);

                                pbd.format.PbdInstance voxelInst = new pbd.format.PbdInstance(inst.id + "_voxels", "mesh");
                                voxelInst.meshData = colored.meshData;
                                voxelInst.material = inst.material;
                                // position/rotation copied from the
                                // ORIGINAL inst for correctness (see this
                                // field's own history) - NOT actually
                                // consulted by the current draw loop below
                                // (which recomputes world transform fresh
                                // from origInst + gridOrigin every frame),
                                // kept anyway so voxelInst itself remains an
                                // accurate PbdInstance rather than one whose
                                // own transform fields silently lie about
                                // where it is.
                                voxelInst.position.set(inst.position);
                                voxelInst.rotation.set(inst.rotation);
                                ClassicMeshRenderer voxelRenderer = buildMeshRenderer(voxelInst, scene, 0, colored.colors);
                                if (voxelRenderer != null) {
                                    destroyedRenderers.put(target, voxelRenderer);
                                    destroyedGridOrigins.put(target, new DestroyedPlacement(voxResult.gridOriginLocal, survivingEntries, voxResult.voxelWorldSize, voxResult.octree.gridSize()));
                                    renderer.hideInstance(target);
                                    int remainingVoxelCount = totalVoxelCountForLogging - removedVoxelCountForLogging;
                                    System.out.println("[Destroy] '" + inst.id + "' -> " + remainingVoxelCount + "/" + totalVoxelCountForLogging
                                        + " voxel(s) remaining after a " + craterRadiusVoxels + "-voxel-radius impact crater ("
                                        + survivingEntries.size() + " geometry piece(s), most of the untouched interior still merged into large boxes), "
                                        + colored.meshData.vertexCount() + " vertices");
                                    soundPlayer.play("doomshotgun.wav");
                                }
                            }
                        }
                    }
                }

                smoothedFps = smoothedFps * 0.9f + (dt > 0f ? 1f / dt : 0f) * 0.1f;

                if (showBenchmarkOverlay) {
                String[] stats = {
                    String.format("FPS: %.1f  FRAME: %.2fMS", smoothedFps, dt * 1000f),
                    String.format("SOURCE FILE: %s (%s)", scenePath.getFileName(), formatBytes(sourceFileBytes)),
                    String.format("INSTANCES: %d  PATCHES: %d  TRIANGLES: %d",
                        scene.instances.size(), renderer.patchCount(), renderer.lastTriangleCount),
                    String.format("LOD: %s (%s)  [-/+ TO CHANGE]", LOD_NAMES[lodIndex],
                        renderer.lodMinLevel == renderer.lodMaxLevel
                            ? String.valueOf((int) renderer.lodMaxLevel)
                            : (int) renderer.lodMinLevel + "-" + (int) renderer.lodMaxLevel),
                    String.format("POS: %.1f %.1f %.1f", camera.position.x, camera.position.y, camera.position.z),
                    String.format("GPU UPLOAD: %d B (ONCE AT LOAD, 0 B ON LOD CHANGE)",
                        renderer.totalUploadedBytes),
                    renderer.useCache
                        ? String.format("CACHE: ON [C TO TOGGLE]  BAKED SHAPES: %d  DRAW CALLS: %d",
                            renderer.cacheBakeCount(), renderer.lastDrawCallCount)
                        : String.format("CACHE: OFF [C TO TOGGLE]  DRAW CALLS: %d (ALWAYS TESSELLATING)",
                            renderer.lastDrawCallCount),
                    String.format("DAY: %d  HOUR: %04.1f  SUN ALT: %.1f  [ARROWS TO CHANGE]",
                        (int) dayOfYear, hourOfDay, sunPos.altitudeDeg()),
                    "N/B: NEXT/PREV MODEL IN FOLDER   F3/F5: HIDE THIS OVERLAY",
                };
                if (renderer.hasChannelDrivenInstances()) {
                    stats = java.util.stream.Stream.concat(java.util.Arrays.stream(stats),
                        java.util.stream.Stream.of(String.format("HUMIDITY: %.1f  %s  [HOLD T FOR TIME-LAPSE]",
                            channelTracker.get("humidity"), isRaining() ? "(RAINING)" : "(dry)")))
                        .toArray(String[]::new);
                }
                text.drawLines(stats, 10, 10, width, height);
                }

                window.swapBuffers();
            }
        }
        // Resets the static, cross-window key state before returning to
        // switch scenes - without this, whichever key (N or B) was
        // physically down at the moment of the break above stays
        // recorded as "down" with no "was down last frame" to match it
        // (that sync only happens via input.endFrame(), never reached
        // this iteration), so the very first frame of the next window
        // sees justPressed() fire again immediately and cycles forever.
        java.util.Arrays.fill(keysDown, false);
        java.util.Arrays.fill(keysJustPressed, false);
        return nextScenePath;
    }

    /** Sibling .pbd files (same directory as scenePath), sorted by
     * filename, cycling with wraparound - direction +1 for next, -1 for
     * previous. Returns null if scenePath's directory can't be listed or
     * has no other .pbd files (nothing to cycle to, not an error). */
    private static Path siblingPbdFile(Path scenePath, int direction) {
        Path dir = scenePath.toAbsolutePath().getParent();
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null;
        java.util.List<Path> siblings;
        try (var stream = java.nio.file.Files.list(dir)) {
            siblings = stream.filter(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".pbd") || n.endsWith(".pbdbin") || n.endsWith(".pbdasset");
            }).sorted().toList();
        } catch (java.io.IOException e) {
            return null;
        }
        if (siblings.size() <= 1) return null;
        Path current = scenePath.toAbsolutePath();
        int index = siblings.indexOf(current);
        if (index < 0) return siblings.get(0); // scenePath itself wasn't in the listing (symlink/relative quirk) - just start from the first
        int nextIndex = ((index + direction) % siblings.size() + siblings.size()) % siblings.size();
        return siblings.get(nextIndex);
    }

    private static void applyLodPreset(PbdRenderer renderer) {
        renderer.lodMinLevel = LOD_PRESETS[lodIndex][0];
        renderer.lodMaxLevel = LOD_PRESETS[lodIndex][1];
    }

    // ---- classic OBJ path -----------------------------------------------------

    private static void runObjViewer(Path scenePath) throws Exception {
        ObjMesh mesh = ObjParser.parse(scenePath);
        runClassicMeshViewer(scenePath, mesh, "Classic mesh viewer", new String[]{"CLASSIC OBJ MESH (NO LOD, FIXED GEOMETRY)"});
    }

    private static void runFbxViewer(Path scenePath) throws Exception {
        java.util.List<pbd.fbx.FbxBinaryReader.Node> nodes = pbd.fbx.FbxBinaryReader.readFile(scenePath);
        pbd.fbx.FbxGeometryExtractor.ExtractionResult result = new pbd.fbx.FbxGeometryExtractor().extract(nodes);
        for (String w : result.warnings) {
            System.out.println("[FBX] WARNING: " + w);
        }
        float[] bbMin = result.boundingBoxMin, bbMax = result.boundingBoxMax;
        System.out.printf("[FBX] Hitbox (bounding box): min=(%.4f,%.4f,%.4f) max=(%.4f,%.4f,%.4f) size=(%.4f,%.4f,%.4f)%n",
            bbMin[0], bbMin[1], bbMin[2], bbMax[0], bbMax[1], bbMax[2],
            bbMax[0] - bbMin[0], bbMax[1] - bbMin[1], bbMax[2] - bbMin[2]);
        runClassicMeshViewer(scenePath, result.mesh, "FBX viewer", new String[]{
            "FBX MESH (GeometricRotation + LclScaling applied, see console)",
            String.format("HITBOX SIZE: %.3f x %.3f x %.3f", bbMax[0] - bbMin[0], bbMax[1] - bbMin[1], bbMax[2] - bbMin[2]),
        });
    }

    private static void runClassicMeshViewer(Path scenePath, ObjMesh mesh, String windowTitlePrefix, String[] extraStats) throws Exception {
        final int width = 1280;
        final int height = 800;

        try (GlWindow window = new GlWindow(width, height, windowTitlePrefix + " - " + scenePath.getFileName());
             ClassicMeshRenderer renderer = new ClassicMeshRenderer(Path.of("src/main/resources/shaders/classic"), mesh);
             TextRenderer text = new TextRenderer(Path.of("src/main/resources/shaders/text"))) {

            FlyCamera camera = new FlyCamera();
            InputState input = new InputState(window.handle());

            glClearColor(0.05f, 0.05f, 0.08f, 1f);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            float smoothedFps = 60f;

            while (!window.shouldClose()) {
                float dt = input.beginFrame(window);
                input.applyLookAndMove(camera, dt);
                input.endFrame();

                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                renderer.render(camera, (float) width / height);

                smoothedFps = smoothedFps * 0.9f + (dt > 0f ? 1f / dt : 0f) * 0.1f;

                java.util.List<String> stats = new java.util.ArrayList<>(java.util.List.of(
                    String.format("FPS: %.1f  FRAME: %.2fMS", smoothedFps, dt * 1000f),
                    String.format("VERTICES: %d  TRIANGLES: %d", mesh.vertexCount(), mesh.triangleCount())
                ));
                stats.addAll(java.util.List.of(extraStats));
                stats.add(String.format("POS: %.1f %.1f %.1f", camera.position.x, camera.position.y, camera.position.z));
                stats.add(String.format("GPU UPLOAD: %d B (FIXED - EVERY VERTEX IS REAL DATA)", renderer.uploadedBytes));
                text.drawLines(stats.toArray(new String[0]), 10, 10, width, height);

                window.swapBuffers();
            }
        }
    }

    /** Delegates entirely to PbdRenderer's own public
     * isContainerOpen/areAllDoorsClosed/anyTriggerMatches now - see
     * those methods' own doc for why this logic lives there instead of
     * here: it needs to be reachable through the same runtime facade
     * every other query (isInstanceOpen, getContainerItems, ...)
     * already is, not stuck as an application-private helper only this
     * file's own game loop could call. */

    /**
     * Whether "today" (the current dayOfYear) counts as rainy - an
     * honest placeholder, not real weather simulation: a simple
     * deterministic pattern (roughly 1 day in 3) so the humidity channel
     * has a concrete, working condition to demonstrate against. This is
     * exactly the seam a real weather system would plug into later -
     * everything else (ChannelTracker, the "humidity" keyframes, this
     * method's call sites) stays the same, only this function's body
     * would change.
     */
    private static boolean isRaining() {
        int day = (int) Math.floor(dayOfYear);
        return (day % 3) == 0;
    }

    /** Mirrors PbdRenderer's own private parseVec3 exactly (can't reuse
     * it directly - different class, private) - "(r, g, b)" -> a 3-float
     * color, or null if malformed rather than throwing, since a bad
     * color= in a hand-edited file shouldn't crash mesh rendering
     * entirely, just fall back to the renderer's own default gray. */
    private static float[] parseColorTriple(String raw) {
        try {
            String inner = raw.trim();
            if (inner.startsWith("(")) inner = inner.substring(1, inner.length() - 1);
            String[] parts = inner.split(",");
            return new float[]{
                Float.parseFloat(parts[0].trim()),
                Float.parseFloat(parts[1].trim()),
                Float.parseFloat(parts[2].trim())};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Which LOD tier a "mesh" instance should show right now: 0 (its
     * own base meshData) at the engine's own maximum global detail
     * setting, or a progressively higher-numbered (by this format's own
     * convention, lower-detail) key from meshDataByLod as the global
     * setting drops - a mesh with only one captured variant (the common
     * case for a first pass at this) simply switches between its base
     * shape and that one variant at the halfway point, which is exactly
     * right for that case; a mesh with several variants steps through
     * them one at a time as global detail keeps dropping, though a mesh
     * offering MORE tiers than the engine's own global LOD range has
     * steps for will never reach its very lowest ones - a real
     * limitation of this simple a mapping, not one this pass attempts
     * to solve more precisely. */
    private static int desiredMeshLodTier(pbd.format.PbdInstance inst, int lodIndex) {
        if (inst.meshDataByLod.isEmpty()) return 0;
        int stepsBelowMax = (LOD_PRESETS.length - 1) - lodIndex;
        if (stepsBelowMax <= 0) return 0;
        Integer[] tiers = inst.meshDataByLod.keySet().toArray(new Integer[0]); // ascending - meshDataByLod is a TreeMap
        int pick = Math.min(stepsBelowMax, tiers.length) - 1;
        return tiers[Math.max(0, pick)];
    }

    /** Builds (or rebuilds) the ClassicMeshRenderer for one "mesh"
     * instance at a specific LOD tier - null on failure (a bad/missing
     * mesh at that tier), logged rather than thrown, so one broken
     * variant doesn't take the whole scene load down. */
    /** A real ray-cast crater (the actual technique Minecraft's own TNT
     * explosion uses - not a hypothesis, the well-documented algorithm:
     * many rays from the blast center, each traveling outward with its
     * own randomly-decaying strength, removing every block it passes
     * through until it runs out), replacing an earlier distance-plus-
     * noise-jitter approach that was reported (twice) as still visibly
     * "spherical with an uncomfortable repetition pattern" - a jittered
     * sphere is fundamentally still a sphere at its core; this is a
     * genuinely different shape, not a smoother version of the same
     * one. Deterministic (seeded from the impact point's own grid
     * coordinate, not System-time or an unseeded Random) so hitting the
     * exact same spot twice carves the exact same crater - reproducible
     * for testing, not re-rolled into a DIFFERENT ugly shape on retry.
     * Returns the set of removed grid coordinates, encoded as a single
     * long (offsetting each axis by a large constant first so a
     * negative coordinate - entirely normal near a grid's own center -
     * never produces a colliding or sign-corrupted key) - a caller
     * checks membership per-voxel with encodeGridCoord(x,y,z), the same
     * encoding this method itself used to build the set. */
    private static java.util.Set<Long> computeCraterRemovedSet(Vector3f impactGrid, float blastRadius) {
        java.util.Set<Long> removed = new java.util.HashSet<>();
        long seed = ((long) Float.floatToIntBits(impactGrid.x) * 73856093L)
            ^ ((long) Float.floatToIntBits(impactGrid.y) * 19349663L)
            ^ ((long) Float.floatToIntBits(impactGrid.z) * 83492791L);
        java.util.Random rng = new java.util.Random(seed);
        int rayCount = 40;
        for (int i = 0; i < rayCount; i++) {
            float theta = rng.nextFloat() * (float) (Math.PI * 2);
            float phi = (float) Math.acos(2 * rng.nextFloat() - 1);
            float dx = (float) (Math.sin(phi) * Math.cos(theta));
            float dy = (float) (Math.sin(phi) * Math.sin(theta));
            float dz = (float) Math.cos(phi);
            float strength = blastRadius * (0.6f + rng.nextFloat() * 0.8f); // per-ray random reach - some rays punch further than others, the actual source of the irregular, non-spherical silhouette
            float step = 0.75f;
            float px = impactGrid.x, py = impactGrid.y, pz = impactGrid.z;
            float traveled = 0f;
            while (traveled < strength && strength > 0f) {
                removed.add(encodeGridCoord((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)));
                strength -= rng.nextFloat() * step * 1.4f; // random decay per step, same source of jaggedness Minecraft's own algorithm has
                px += dx * step; py += dy * step; pz += dz * step;
                traveled += step;
            }
        }
        return removed;
    }

    private static long encodeGridCoord(int x, int y, int z) {
        long ox = x + 200000L, oy = y + 200000L, oz = z + 200000L; // large fixed offset - always positive for any coordinate this project's own MAX_OCTREE_DEPTH cap could ever produce
        return ox * 1_000_000_000_000L + oy * 1_000_000L + oz;
    }

    /** Replaces an earlier "expand the WHOLE region to individual unit
     * voxels the instant its AABB comes anywhere near the crater"
     * approach - confirmed, via a real reported OutOfMemoryError, to
     * blow up catastrophically once the crater radius was raised (a
     * single large merged region - up to 128+ voxels on a side for a
     * sizeable prop - expands to size^3 individual int[] entries the
     * moment ANY part of it is within reach, which for a big region
     * near a wide-radius crater could be millions of entries from ONE
     * region alone, several such regions compounding further). This
     * recurses octree-style instead, the same adaptive principle
     * PrimitiveVoxelizer.rasterizeAdaptive itself already uses for the
     * original voxelization: a region entirely outside the outer test
     * radius survives WHOLE, unexamined further; a region small enough
     * (size 1) gets tested directly against the real ray-cast
     * removedSet; anything else - genuinely ambiguous, actually near
     * the crater's own boundary - splits into 8 octants and recurses,
     * so only the geometry ACTUALLY close to the impact ever gets
     * refined down to individual voxels, not everything merely inside
     * a broad bounding radius. removedCount[0] accumulates the removed
     * voxel tally (an int[1] "out parameter", since a private static
     * method can't return two things without a small record - not
     * worth one here for something called this hot). */
    private static void applyCraterAdaptive(int rx, int ry, int rz, int rsize,
            java.util.Set<Long> removedSet, Vector3f impactGrid, float outerTestRadius,
            java.util.List<int[]> survivingEntries, int[] removedCount) {
        float cx = Math.max(rx, Math.min(impactGrid.x, rx + rsize));
        float cy = Math.max(ry, Math.min(impactGrid.y, ry + rsize));
        float cz = Math.max(rz, Math.min(impactGrid.z, rz + rsize));
        float distSq = (cx - impactGrid.x) * (cx - impactGrid.x)
            + (cy - impactGrid.y) * (cy - impactGrid.y)
            + (cz - impactGrid.z) * (cz - impactGrid.z);
        if (distSq > outerTestRadius * outerTestRadius) {
            survivingEntries.add(new int[]{rx, ry, rz, rsize}); // nowhere near the impact - kept as one merged box, never expanded
            return;
        }
        if (rsize == 1) {
            if (removedSet.contains(encodeGridCoord(rx, ry, rz))) removedCount[0]++;
            else survivingEntries.add(new int[]{rx, ry, rz, 1});
            return;
        }
        int half = rsize / 2;
        for (int i = 0; i < 8; i++) {
            int ox = rx + ((i & 1) != 0 ? half : 0);
            int oy = ry + ((i & 2) != 0 ? half : 0);
            int oz = rz + ((i & 4) != 0 ? half : 0);
            applyCraterAdaptive(ox, oy, oz, half, removedSet, impactGrid, outerTestRadius, survivingEntries, removedCount);
        }
    }

    /** Extracts JUST the rotation from a world matrix that may carry
     * strongly non-uniform scale, WITHOUT JOML's own
     * Matrix4f.getNormalizedRotation()'s confirmed failure mode there:
     * a direct numeric test (this codebase's own history has the full
     * before/after) against a real instance with a 27:1 ratio between
     * its smallest and largest scale axis showed that method returning
     * something that isn't even a valid unit quaternion (x^2+y^2+z^2+w^2
     * far from 1), producing a visibly wrong rotation. The standard,
     * robust technique instead: take the matrix's own 3 basis columns
     * (its upper-left 3x3, each column already CARRYING that axis's own
     * scale baked in) and normalize each one INDEPENDENTLY - dividing
     * out exactly that column's own scale contribution, however
     * different it is from the other two - before building a rotation-
     * only 3x3 and reading its quaternion; unlike
     * getNormalizedRotation()'s own approach, this never has to assume
     * or approximate a single shared scale factor across all three axes
     * at once. */
    /** Shared between the initial-destruction and follow-up-hit (retarget)
     * code paths - extracted so a voxel hit a SECOND time keeps showing
     * real texture-sampled color instead of silently falling back to
     * flat color just because it's being rebuilt via the OTHER call
     * site. See this method's own inline doc (moved here from its
     * original, single-call-site home) for the full history of why this
     * was disabled and re-enabled. */
    /** Rewritten to use PbdRenderer's own pre-decoded voxelTexturePixels
     * cache instead of calling STBImage directly here - see that
     * cache's own doc (PbdRenderer.upload()) for why: every earlier
     * version of this method called stbi_load itself, live, from
     * inside the G-key handler's own call stack, mid-frame - and kept
     * correlating with a real, repeatedly-reported native crash despite
     * several rounds of defensive hardening and one unrelated-but-
     * plausible fix (a shared shader program) that turned out NOT to
     * be the actual cause either, confirmed by the crash recurring
     * again after that fix shipped. This version never touches
     * STBImage or any native image-decoding call at all - the texture
     * was already fully decoded, once, back at scene-load time, in the
     * exact same call context MaterialTextureArray's own (long-
     * confirmed-working) texture loading already uses. */
    private static java.util.function.Function<int[], float[]> buildVoxelColorFn(
            pbd.format.PbdInstance inst, java.util.Map<String, String> matFields, float[] fallbackColor,
            int gridSize, float voxelWorldSize, PbdRenderer renderer) {
        String texturePath = matFields != null ? matFields.get("texture") : null;
        if (texturePath == null) return voxel -> fallbackColor;

        PbdRenderer.VoxelTexturePixels tex = renderer.voxelTexturePixelsFor(texturePath);
        if (tex == null) {
            System.out.println("[Destroy] No pre-decoded pixel data for texture '" + texturePath + "' - using flat color instead");
            return voxel -> fallbackColor;
        }

        int texW = tex.width(), texH = tex.height();
        byte[] pixelBytes = tex.pixelBytes();
        int gvx = Math.max(1, Math.round(inst.scale.x / voxelWorldSize));
        int gvy = Math.max(1, Math.round(inst.scale.y / voxelWorldSize));
        int gvz = Math.max(1, Math.round(inst.scale.z / voxelWorldSize));
        int foX = (gridSize - gvx) / 2, foY = (gridSize - gvy) / 2, foZ = (gridSize - gvz) / 2;
        return voxel -> {
            float nx = (voxel[0] - foX) / (float) gvx;
            float ny = (voxel[1] - foY) / (float) gvy;
            float nz = (voxel[2] - foZ) / (float) gvz;
            float dx = Math.abs(nx - 0.5f), dy = Math.abs(ny - 0.5f), dz = Math.abs(nz - 0.5f);
            float u, v;
            if (dx >= dy && dx >= dz) { u = nz; v = ny; }
            else if (dy >= dx && dy >= dz) { u = nx; v = nz; }
            else { u = nx; v = ny; }
            int px = Math.floorMod((int) (u * texW), texW);
            int py = Math.floorMod((int) (v * texH), texH);
            int idx = (py * texW + px) * 4;
            return new float[]{
                (pixelBytes[idx] & 0xFF) / 255f,
                (pixelBytes[idx + 1] & 0xFF) / 255f,
                (pixelBytes[idx + 2] & 0xFF) / 255f
            };
        };
    }

    private static org.joml.Quaternionf extractRotationRobust(Matrix4f m) {
        Vector3f colX = new Vector3f(m.m00(), m.m01(), m.m02()).normalize();
        Vector3f colY = new Vector3f(m.m10(), m.m11(), m.m12()).normalize();
        Vector3f colZ = new Vector3f(m.m20(), m.m21(), m.m22()).normalize();
        Matrix4f rotOnly = new Matrix4f(
            colX.x, colX.y, colX.z, 0,
            colY.x, colY.y, colY.z, 0,
            colZ.x, colZ.y, colZ.z, 0,
            0, 0, 0, 1);
        return rotOnly.getNormalizedRotation(new org.joml.Quaternionf());
    }

    private static ClassicMeshRenderer buildMeshRenderer(pbd.format.PbdInstance inst, pbd.format.PbdScene scene, int tier) {
        return buildMeshRenderer(inst, scene, tier, null);
    }

    /** colors: parallel per-vertex RGB array (see VoxelMeshBuilder's own
     * ColoredMesh) - null for every existing caller (a regular mesh-
     * type instance, a container item), which keeps rendering exactly
     * as before via the texture/flat-baseColor path; non-null only for
     * a voxel-destruction result, which switches ClassicMeshRenderer
     * into its useVertexColor mode instead (see that class's own doc).
     * A 3-arg call (the overload above) is the normal case - this one
     * exists so the voxel-destruction call site is the only thing that
     * needs to know colors exists at all. */
    private static ClassicMeshRenderer buildMeshRenderer(pbd.format.PbdInstance inst, pbd.format.PbdScene scene, int tier, float[] colors) {
        pbd.format.PbdMeshData data = tier == 0 ? inst.meshData : inst.meshDataByLod.get(tier);
        if (data == null) data = inst.meshData; // the requested tier vanished somehow (shouldn't happen) - fall back to base rather than render nothing
        try {
            // toPositionNormalUvInterleaved + hasUv=true: this data
            // already HAD real per-vertex UVs (see PbdMeshData's own
            // constructor/doc) - they just never reached the GPU before,
            // since this renderer had no UV attribute or texture sampler
            // at all. Reported as "UV mapping doesn't work in Java" -
            // accurate, in that nothing here was even trying.
            ObjMesh objMesh = new ObjMesh(data.toPositionNormalUvInterleaved(), data.indices, true, colors);
            ClassicMeshRenderer meshRenderer = new ClassicMeshRenderer(Path.of("src/main/resources/shaders/classic"), objMesh);
            var matFields = scene.materialOverrides.get(inst.material);
            if (matFields != null) {
                if (matFields.get("color") != null) {
                    float[] c = parseColorTriple(matFields.get("color"));
                    if (c != null) meshRenderer.baseColor = c;
                }
                // uvScale/uvScaleU/uvScaleV: same precedence as the main
                // tessellation pipeline's own reading of these fields
                // (see PbdRenderer.uploadMaterials) - uvScale= alone sets
                // both axes, uvScaleU=/uvScaleV= override independently.
                float uScale = 1f, vScale = 1f;
                if (matFields.get("uvScale") != null) {
                    try { uScale = vScale = Float.parseFloat(matFields.get("uvScale")); } catch (NumberFormatException ignored) {}
                }
                if (matFields.get("uvScaleU") != null) {
                    try { uScale = Float.parseFloat(matFields.get("uvScaleU")); } catch (NumberFormatException ignored) {}
                }
                if (matFields.get("uvScaleV") != null) {
                    try { vScale = Float.parseFloat(matFields.get("uvScaleV")); } catch (NumberFormatException ignored) {}
                }
                meshRenderer.uvScale = new float[]{uScale, vScale};
                if (matFields.get("texture") != null) {
                    meshRenderer.setTexture(Path.of(matFields.get("texture")));
                }
            }
            return meshRenderer;
        } catch (java.io.IOException e) {
            System.err.println("[Mesh] Failed to create renderer for instance " + inst.id + " at LOD " + tier + ": " + e.getMessage());
            return null;
        }
    }

    /** Rebuilds any "mesh" instance's ClassicMeshRenderer whose DESIRED
     * LOD tier (see desiredMeshLodTier) just changed as a result of the
     * global -/+ adjustment - called right after applyLodPreset, which
     * handles the TESSELLATION side of the same keys; this is the
     * classic-mesh-pipeline side, a separate system entirely (see
     * Main.java's own "mesh" instance rendering comment for why). Closes
     * the outgoing renderer's GL resources (VBO/VAO) before replacing
     * it, or repeated +/- presses would leak one set per press. */
    private static void rebuildMeshRenderersForLod(pbd.format.PbdScene scene,
                                                     java.util.Map<Integer, ClassicMeshRenderer> meshInstanceRenderers,
                                                     java.util.Map<Integer, Integer> meshInstanceCurrentTier,
                                                     int lodIndex) {
        for (int i = 0; i < scene.instances.size(); i++) {
            pbd.format.PbdInstance inst = scene.instances.get(i);
            if (!inst.type.equals("mesh") || inst.meshData == null) continue;
            int desired = desiredMeshLodTier(inst, lodIndex);
            Integer current = meshInstanceCurrentTier.get(i);
            if (current != null && current == desired) continue; // already showing the right tier
            ClassicMeshRenderer rebuilt = buildMeshRenderer(inst, scene, desired);
            if (rebuilt == null) continue; // keep whatever was already showing rather than go blank
            ClassicMeshRenderer old = meshInstanceRenderers.put(i, rebuilt);
            meshInstanceCurrentTier.put(i, desired);
            if (old != null) old.close();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Starts the hide-in transition: camera eases from wherever it
     * currently is to metaIndex's own world center, turning to face the
     * average position of whichever doors are linked to it (the
     * existing containerTrigger list - see PbdParser/PbdInstance -
     * recycled here exactly as the roadmap asked, not a new field).
     * Facing the doors specifically (not just "forward") is the point:
     * that's the direction anything coming through them would approach
     * from, and where the player would want to be looking while
     * hidden. */
    private static void startHiding(int metaIndex, pbd.format.PbdScene scene, PbdRenderer renderer, FlyCamera camera) {
        Vector3f center = renderer.instanceWorldCenter(metaIndex);
        java.util.List<String> doorIds = scene.instances.get(metaIndex).containerTriggers;
        Vector3f doorSum = new Vector3f();
        int doorCount = 0;
        for (String doorId : doorIds) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (scene.instances.get(i).id.equals(doorId)) {
                    doorSum.add(renderer.instanceWorldCenter(i));
                    doorCount++;
                    break;
                }
            }
        }
        Vector3f faceDir;
        if (doorCount > 0) {
            Vector3f doorAvg = new Vector3f(doorSum).div(doorCount);
            faceDir = new Vector3f(doorAvg).sub(center);
            if (faceDir.lengthSquared() < 1e-8f) faceDir = new Vector3f(camera.forward()); // doors sit exactly at the container's own center (unusual, but not impossible) - nothing meaningful to point toward, keep facing whatever direction the player already was
            else faceDir.normalize();
        } else {
            faceDir = new Vector3f(camera.forward()); // no doors linked at all - still lets the player hide, just can't compute an "outward" direction, so keeps their current facing instead of guessing one
        }

        hidingMetaIndex = metaIndex;
        hideStartPos = new Vector3f(camera.position);
        hideTargetPos = center;
        hideStartYaw = camera.yaw;
        hideStartPitch = camera.pitch;
        // asin/atan2: exact inverse of FlyCamera.forward()'s own
        // fx=cos(pitch)sin(yaw), fy=sin(pitch), fz=-cos(pitch)cos(yaw) -
        // recovering the yaw/pitch that would make forward() return
        // faceDir, since FlyCamera has no "look at" of its own to call
        // instead, only look(dx,dy) driven by mouse delta.
        hideTargetPitch = (float) Math.asin(Math.max(-1, Math.min(1, faceDir.y)));
        hideTargetYaw = (float) Math.atan2(faceDir.x, -faceDir.z);
        hideTargetYaw = shortestAngleTarget(hideStartYaw, hideTargetYaw);
        hideTransitionT = 0f;
        System.out.println("[Hide] Hiding in container " + scene.instances.get(metaIndex).id
            + (doorCount > 0 ? " facing " + doorCount + " linked door(s)" : " (no linked doors to face)"));
    }

    /** Reuses the SAME start/target fields as startHiding, swapped - the
     * exit eases back to wherever the player was actually standing right
     * before they hid, rather than an instant unlock from deep inside a
     * closed container's own center, which would otherwise have the
     * player immediately clipping through its walls on their first WASD
     * press back out. */
    private static void startExitingHide(FlyCamera camera) {
        Vector3f exitTargetPos = hideStartPos;
        float exitTargetYaw = hideStartYaw, exitTargetPitch = hideStartPitch;
        hideStartPos = new Vector3f(camera.position);
        hideStartYaw = camera.yaw;
        hideStartPitch = camera.pitch;
        hideTargetPos = exitTargetPos;
        hideTargetYaw = shortestAngleTarget(hideStartYaw, exitTargetYaw);
        hideTargetPitch = exitTargetPitch;
        hideTransitionT = 0f;
        hidingMetaIndex = -1; // movement unlocks immediately - the transition below is purely visual, doesn't block WASD the way the hide-IN transition intentionally does
        System.out.println("[Hide] Exiting hide");
    }

    /** angle, adjusted by a multiple of 2*PI so it's within PI of from -
     * so interpolating from...adjusted takes the SHORT way around
     * (e.g. 170deg -> -170deg turns 20 degrees through 180, not 340
     * degrees the other way). */
    private static float shortestAngleTarget(float from, float angle) {
        float twoPi = (float) (Math.PI * 2);
        float diff = ((angle - from + (float) Math.PI) % twoPi + twoPi) % twoPi - (float) Math.PI;
        return from + diff;
    }

    /** Advances the current hide/exit transition (if any) toward its
     * target - a no-op once hideTransitionT reaches 1. Runs every frame
     * regardless of hidingMetaIndex's own value, since an exit
     * transition's whole POINT is to keep animating for
     * HIDE_TRANSITION_SECONDS after hidingMetaIndex has ALREADY gone
     * back to -1 (movement unlocked immediately on exit - see
     * startExitingHide - while this plays out purely visually
     * alongside it). */
    private static void updateHideTransition(FlyCamera camera, float dt) {
        if (hideTransitionT >= 1f || hideStartPos == null) return;
        hideTransitionT = Math.min(1f, hideTransitionT + dt / HIDE_TRANSITION_SECONDS);
        float s = hideTransitionT * hideTransitionT * (3 - 2 * hideTransitionT); // smoothstep - eases in/out rather than a linear, mechanical-feeling move
        camera.position.set(new Vector3f(hideStartPos).lerp(hideTargetPos, s));
        camera.yaw = hideStartYaw + (hideTargetYaw - hideStartYaw) * s;
        camera.pitch = hideStartPitch + (hideTargetPitch - hideStartPitch) * s;
    }

    /** Was key pressed since the last time THIS SPECIFIC key was
     * checked - consumed on read, exactly like consumeClick()/
     * mouseClicked below for the mouse. Deliberately NOT the
     * keysDown-vs-keysDownPrev-snapshot comparison this used to be:
     * that version was verified correct in isolation (a direct,
     * reflection-based test against this exact method passed every
     * case), yet a real run showed it never actually firing - E/G/H
     * all started working the moment they were changed to check
     * keysDown directly instead. Rather than chase exactly why the
     * per-frame-snapshot approach broke down in practice, this
     * switches to the same event-driven flag the mouse click handling
     * already used successfully: keysJustPressed[key] is set directly
     * by the GLFW_PRESS callback itself (see the callback above), the
     * ONE moment a press unambiguously happened, with no per-frame
     * timing/ordering for it to depend on - and reading it here clears
     * it, so it still only fires once per physical press, not every
     * frame the key stays held (unlike a raw keysDown[key] check,
     * which the callback still maintains for movement/held-key
     * purposes elsewhere, but which repeat-fires by design and would
     * be wrong for a single-shot action like removing one item or
     * destroying one instance per press). */
    private static boolean justPressed(int key) {
        if (keysJustPressed[key]) {
            keysJustPressed[key] = false;
            return true;
        }
        return false;
    }

    /** True once per left-click, consuming the flag so a held click doesn't repeat-fire. */
    private static boolean consumeClick() {
        if (mouseClicked) { mouseClicked = false; return true; }
        return false;
    }

    /**
     * Shared GLFW input plumbing (keyboard state, mouse delta, delta time)
     * used by both viewer loops above, so the camera controls behave
     * identically regardless of which mesh path is active.
     */
    private static final class InputState {
        private double lastTime;
        private double lastMouseX;
        private double lastMouseY;
        private boolean firstMouse = true;
        private float dx, dy;

        InputState(long handle) {
            glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
                if (key >= 0 && key <= GLFW_KEY_LAST) {
                    if (action == GLFW_PRESS) {
                        keysDown[key] = true;
                        keysJustPressed[key] = true; // set directly from the discrete PRESS event itself, exactly like mouseClicked below - not from comparing keysDown against a previous frame's snapshot, which is the part that turned out not to work reliably in practice (see justPressed's own doc)
                    }
                    else if (action == GLFW_RELEASE) keysDown[key] = false;
                }
            });
            glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    if (action == GLFW_PRESS && !leftMouseDown) mouseClicked = true; // edge only, not held-down repeat
                    leftMouseDown = (action == GLFW_PRESS);
                }
            });
            lastTime = glfwGetTime();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                DoubleBuffer xBuf = stack.mallocDouble(1);
                DoubleBuffer yBuf = stack.mallocDouble(1);
                glfwGetCursorPos(handle, xBuf, yBuf);
                lastMouseX = xBuf.get(0);
                lastMouseY = yBuf.get(0);
            }
        }

        /** Polls events, advances the clock, and returns this frame's delta time in seconds. */
        float beginFrame(GlWindow window) {
            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;
            if (dt > 0.25f) dt = 0.25f; // avoid a big jump after a freeze (resize, focus loss...)

            window.pollEvents();

            double mouseX, mouseY;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                DoubleBuffer xBuf = stack.mallocDouble(1);
                DoubleBuffer yBuf = stack.mallocDouble(1);
                glfwGetCursorPos(window.handle(), xBuf, yBuf);
                mouseX = xBuf.get(0);
                mouseY = yBuf.get(0);
            }
            if (firstMouse) { lastMouseX = mouseX; lastMouseY = mouseY; firstMouse = false; }
            dx = (float) (mouseX - lastMouseX);
            dy = (float) (mouseY - lastMouseY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;

            if (keysDown[GLFW_KEY_ESCAPE]) {
                glfwSetWindowShouldClose(window.handle(), true);
            }
            return dt;
        }

        void applyLookAndMove(FlyCamera camera, float dt) {
            camera.look(dx, dy);
            // Look stays free while hiding (see startHiding/
            // updateHideTransition) - only WASD+Space+Shift are the
            // "deplacements" the roadmap asked to block, not mouse-look,
            // and the ongoing hide/exit transition itself also drives
            // camera.position directly every frame regardless, which
            // uncontested WASD movement would otherwise fight against.
            if (hidingMetaIndex < 0) {
                camera.move(
                    keysDown[GLFW_KEY_W], keysDown[GLFW_KEY_S],
                    keysDown[GLFW_KEY_A], keysDown[GLFW_KEY_D],
                    keysDown[GLFW_KEY_SPACE], keysDown[GLFW_KEY_LEFT_SHIFT],
                    dt);
            }
        }

        /** No longer does anything - kept only so its two existing call
         * sites don't need to be removed. Used to sync keysDownPrev for
         * the old keysDown-vs-keysDownPrev justPressed() implementation;
         * that whole mechanism is gone now (see justPressed's own doc),
         * replaced by keysJustPressed, which the GLFW_PRESS callback
         * itself sets directly and justPressed() consumes on read - no
         * per-frame sync of any kind needed or wanted. This method's
         * OWN prior version is actually the confirmed root cause of the
         * reported "E/G/H never fire" bug: it was called mid-frame
         * (right after channel/animation updates, well before the E/G/H
         * checks later in the same frame's code), which synced
         * keysDownPrev to the CURRENT frame's keysDown before those
         * later checks ever got to compare against the PREVIOUS frame's
         * snapshot - so keysDownPrev[key] was already true by the time
         * E/G/H's own justPressed() ran, on literally the very first
         * frame the key went down, making justPressed() structurally
         * unable to ever return true for anything checked after this
         * call site. N/B/F3/C, all checked BEFORE this call in the
         * frame's own code, never hit this - which is exactly why they
         * worked while E/G/H didn't. */
        void endFrame() {
        }
    }
}
