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
    private static final java.util.Map<Integer, org.joml.Vector3f> destroyedGridOrigins = new java.util.HashMap<>();
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
    private static final boolean[] keysDownPrev = new boolean[GLFW_KEY_LAST + 1];
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
            java.util.Map<java.util.List<Integer>, pbd.pz.ContainerContents> containerContents = new java.util.HashMap<>();
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
                // if-block, the bug is specifically in justPressed's own
                // edge-detection (keysDownPrev handling).
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
                for (var entry : destroyedRenderers.entrySet()) {
                    pbd.format.PbdInstance origInst = scene.instances.get(entry.getKey());
                    org.joml.Vector3f gridOrigin = destroyedGridOrigins.get(entry.getKey());
                    Matrix4f world = new Matrix4f()
                        .translate(origInst.position)
                        .rotateZ((float) Math.toRadians(origInst.rotationDeg.z))
                        .rotateY((float) Math.toRadians(origInst.rotationDeg.y))
                        .rotateX((float) Math.toRadians(origInst.rotationDeg.x))
                        .translate(gridOrigin);
                    entry.getValue().render(camera, (float) width / height, world);
                }

                // Bin-packed container contents (see pbd.pz.ContainerContents
                // and pbd.pz.BinPacker) - lazily computed and cached per
                // GROUP of boxes sharing a door set, per this project's
                // own rule: no computation or display before at least one
                // linked door is open.
                for (java.util.List<Integer> group : containerGroups) {
                    boolean anyOpen = group.stream().anyMatch(idx -> isContainerOpen(scene, renderer, idx));
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
                               && group.stream().allMatch(idx -> areAllDoorsClosed(scene, renderer, idx))) {
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
                    if (target < 0) {
                        System.out.println("[Destroy] Nothing destructible under the crosshair");
                    } else if (destroyedRenderers.containsKey(target)) {
                        System.out.println("[Destroy] Already destroyed");
                    } else {
                        pbd.format.PbdInstance inst = scene.instances.get(target);
                        var voxResult = pbd.voxel.PrimitiveVoxelizer.voxelize(inst, 12);
                        if (voxResult == null) {
                            System.out.println("[Destroy] '" + inst.id + "' can't be voxelized (indestructible, or an unsupported/mesh type)");
                        } else {
                            var regions = voxResult.octree.collectFilledRegions();
                            pbd.format.PbdMeshData meshData = pbd.voxel.VoxelMeshBuilder.buildMesh(regions, voxResult.voxelWorldSize);
                            pbd.format.PbdInstance voxelInst = new pbd.format.PbdInstance(inst.id + "_voxels", "mesh");
                            voxelInst.meshData = meshData;
                            voxelInst.material = inst.material;
                            ClassicMeshRenderer voxelRenderer = buildMeshRenderer(voxelInst, scene, 0);
                            if (voxelRenderer != null) {
                                destroyedRenderers.put(target, voxelRenderer);
                                destroyedGridOrigins.put(target, voxResult.gridOriginLocal);
                                renderer.hideInstance(target);
                                System.out.println("[Destroy] '" + inst.id + "' -> " + regions.size() + " voxel region(s), "
                                    + meshData.vertexCount() + " vertices");
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

                // THE fix for the reported "E/G/H raw key state flips to
                // true, but the actual action never fires" bug -
                // keysDownPrev was declared but never actually written
                // to anywhere in this file (confirmed by grep: the only
                // OTHER reference to it was the read inside justPressed
                // itself), meaning it stayed at its default all-false
                // forever. !keysDownPrev[key] was therefore unconditionally
                // true, which doesn't itself explain "never fires" on
                // its own (if anything it should have made justPressed
                // fire on EVERY frame a key was held, not zero) - but it
                // is unambiguously wrong regardless of exactly how its
                // symptom actually presented, and correct edge-detection
                // semantics need this synced at the end of every frame,
                // after this frame's own justPressed(...) checks have
                // already run against the PREVIOUS frame's snapshot -
                // syncing any earlier would make a key's own true state
                // this frame instantly overwrite what THIS frame needed
                // to compare against, collapsing "was down last frame" to
                // "is down right now" before it was ever consulted.
                System.arraycopy(keysDown, 0, keysDownPrev, 0, keysDown.length);
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
        java.util.Arrays.fill(keysDownPrev, false);
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

    /**
     * Whether the container a metadata cube belongs to should be
     * considered "open" (the gate for computing/showing its bin-packed
     * contents) - true if ANY ONE of its linked doors (containerTriggers,
     * plural - see PbdInstance) is fully open. A storage cube can now
     * list several doors (a big wardrobe with two independent doors over
     * one shelf), and several storage cubes can share one door too (a
     * door with pockets on both sides) - both directions fall out
     * naturally from "each cube lists the doors that open it": many
     * cubes can list the same door, and one cube can list many doors.
     *
     * Falls back to "is ANY instance in the whole scene toggled open"
     * only when the cube lists no doors at all.
     *
     * Uses isInstanceOpen (toggled-open, regardless of animation
     * progress), NOT isInstanceFullyOpen - contents should appear the
     * moment a linked door is clicked open, not only once its whole
     * swing animation has finished playing out.
     */
    private static boolean isContainerOpen(PbdScene scene, PbdRenderer renderer, int metaIdx) {
        java.util.List<String> triggers = scene.instances.get(metaIdx).containerTriggers;
        if (!triggers.isEmpty()) {
            return anyTriggerMatches(scene, renderer, triggers, true);
        }
        for (int i = 0; i < scene.instances.size(); i++) {
            if (renderer.isInstanceOpen(i)) return true;
        }
        return false;
    }

    /**
     * True once every one of a container's linked doors is FULLY closed
     * - not just toggled shut, but actually finished swinging back to its
     * closed pose (see PbdRenderer.isInstanceFullyClosed). Deliberately
     * asymmetric with isContainerOpen/isInstanceOpen above: showing
     * contents the instant a door is clicked open feels responsive, but
     * clearing them the instant it's clicked shut - before it's actually
     * swung across the opening - would make items visibly vanish while
     * still exposed through the gap instead of disappearing behind a
     * closed door. Now actually wired to something: see the eviction
     * step next to where isContainerOpen is checked in the main loop,
     * which removes a group's cached contents once this goes true, so
     * reopening triggers a fresh (but deterministically-seeded, so
     * unchanged-looking within one session) load instead of whatever was
     * shown staying up forever.
     */
    private static boolean areAllDoorsClosed(PbdScene scene, PbdRenderer renderer, int metaIdx) {
        java.util.List<String> triggers = scene.instances.get(metaIdx).containerTriggers;
        if (triggers.isEmpty()) return true;
        for (String triggerName : triggers) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (triggerName.equals(scene.instances.get(i).id) && !renderer.isInstanceFullyClosed(i)) {
                    return false; // this linked door is either still open or still swinging shut
                }
            }
        }
        return true;
    }

    private static boolean anyTriggerMatches(PbdScene scene, PbdRenderer renderer, java.util.List<String> triggerNames, boolean requireOpen) {
        for (String triggerName : triggerNames) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (triggerName.equals(scene.instances.get(i).id) && renderer.isInstanceOpen(i) == requireOpen) {
                    return true;
                }
            }
        }
        return false;
    }

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
    private static ClassicMeshRenderer buildMeshRenderer(pbd.format.PbdInstance inst, pbd.format.PbdScene scene, int tier) {
        pbd.format.PbdMeshData data = tier == 0 ? inst.meshData : inst.meshDataByLod.get(tier);
        if (data == null) data = inst.meshData; // the requested tier vanished somehow (shouldn't happen) - fall back to base rather than render nothing
        try {
            // toPositionNormalUvInterleaved + hasUv=true: this data
            // already HAD real per-vertex UVs (see PbdMeshData's own
            // constructor/doc) - they just never reached the GPU before,
            // since this renderer had no UV attribute or texture sampler
            // at all. Reported as "UV mapping doesn't work in Java" -
            // accurate, in that nothing here was even trying.
            ObjMesh objMesh = new ObjMesh(data.toPositionNormalUvInterleaved(), data.indices, true);
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

    private static boolean justPressed(int key) {
        return keysDown[key] && !keysDownPrev[key];
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
                    if (action == GLFW_PRESS) keysDown[key] = true;
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

        /** Call once per frame after all justPressed() checks for that frame have been made. */
        void endFrame() {
            System.arraycopy(keysDown, 0, keysDownPrev, 0, keysDown.length);
        }
    }
}
