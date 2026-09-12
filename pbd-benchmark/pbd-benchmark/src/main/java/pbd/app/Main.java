package pbd.app;

import org.lwjgl.system.MemoryStack;
import pbd.classicmesh.ClassicMeshRenderer;
import pbd.classicmesh.ObjMesh;
import pbd.classicmesh.ObjParser;
import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdParser;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;
import pbd.render.FlyCamera;
import pbd.render.GlWindow;
import pbd.render.PbdRenderer;
import pbd.render.SkydomeRenderer;
import pbd.render.TextRenderer;
import pbd.sky.SolarCalculator;

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
    private static final double HOUR_RATE_PER_SEC = 2.0;
    private static final double DAY_RATE_PER_SEC = 30.0;

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
        PrimitiveRegistry primitiveRegistry = new PrimitiveRegistry();
        ModifierRegistry modifierRegistry = new ModifierRegistry();
        MaterialRegistry materialRegistry = new MaterialRegistry();
        PbdScene scene = new PbdParser(primitiveRegistry, modifierRegistry).parseFile(scenePath);

        final int width = 1280;
        final int height = 800;

        try (GlWindow window = new GlWindow(width, height, "PBD viewer - " + scenePath.getFileName());
             PbdRenderer renderer = new PbdRenderer(Path.of("src/main/resources/shaders/pbd"));
             SkydomeRenderer sky = new SkydomeRenderer(Path.of("src/main/resources/shaders/sky"),
                 Path.of("src/main/resources/textures/night_sky.jpg"));
             TextRenderer text = new TextRenderer(Path.of("src/main/resources/shaders/text"))) {

            renderer.upload(scene, primitiveRegistry, modifierRegistry, materialRegistry);
            applyLodPreset(renderer);

            // Bin-packed container contents (see pbd.pz.ContainerContents)
            // - keyed by instance index of a metadata storage-space cube,
            // computed lazily the first frame that container is seen
            // fully open, and cached from then on (this project's own
            // rule: no computation or display before the container
            // opens). Each container's items get their own
            // ClassicMeshRenderer (created once, reused every frame),
            // since rendering an FBX mesh reuses that existing pipeline
            // rather than a new one.
            java.util.Map<Integer, pbd.pz.ContainerContents> containerContents = new java.util.HashMap<>();
            java.util.Map<pbd.pz.ContainerContents.PlacedItem, ClassicMeshRenderer> itemRenderers = new java.util.HashMap<>();
            java.util.List<Integer> metadataInstances = new java.util.ArrayList<>();
            for (int i = 0; i < scene.instances.size(); i++) {
                if ("true".equals(scene.instances.get(i).params.get("metadata"))) {
                    metadataInstances.add(i);
                }
            }

            FlyCamera camera = new FlyCamera();
            InputState input = new InputState(window.handle());

            glClearColor(0.05f, 0.05f, 0.08f, 1f);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            float smoothedFps = 60f;
            Path nextScenePath = null;

            while (!window.shouldClose()) {
                float dt = input.beginFrame(window);
                input.applyLookAndMove(camera, dt);

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
                }
                if (justPressed(GLFW_KEY_MINUS) && lodIndex > 0) {
                    lodIndex--;
                    applyLodPreset(renderer);
                }
                if (justPressed(GLFW_KEY_C)) {
                    renderer.useCache = !renderer.useCache;
                }
                // Time of day / season. Held, not tapped, for continuous
                // scrubbing - same feel as WASD.
                if (keysDown[GLFW_KEY_UP])    hourOfDay += HOUR_RATE_PER_SEC * dt;
                if (keysDown[GLFW_KEY_DOWN])  hourOfDay -= HOUR_RATE_PER_SEC * dt;
                if (keysDown[GLFW_KEY_RIGHT]) dayOfYear += DAY_RATE_PER_SEC * dt;
                if (keysDown[GLFW_KEY_LEFT])  dayOfYear -= DAY_RATE_PER_SEC * dt;
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

                // Bin-packed container contents (see pbd.pz.ContainerContents
                // and pbd.pz.BinPacker) - lazily computed and cached per
                // container, per this project's own rule: no computation
                // or display before the container is fully open.
                for (int metaIdx : metadataInstances) {
                    if (isContainerOpen(scene, renderer, metaIdx) && !containerContents.containsKey(metaIdx)) {
                        Vector3f size = renderer.instanceWorldSize(metaIdx);
                        pbd.pz.ContainerContents contents = pbd.pz.ContainerContents.loadRandom(
                            pbd.pz.PbdPaths.FBX_DIR, 5, size.x, size.y, size.z);
                        containerContents.put(metaIdx, contents);
                        System.out.println("[Container] Loaded " + contents.items.size()
                            + " item(s) into metadata instance " + metaIdx);
                    }
                }

                for (var entry : containerContents.entrySet()) {
                    int metaIdx = entry.getKey();
                    pbd.pz.ContainerContents contents = entry.getValue();
                    contents.update(dt);

                    Vector3f containerCenter = renderer.instanceWorldCenter(metaIdx);
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
                        Matrix4f itemWorld = new Matrix4f()
                            .translate(containerCenter.x + item.currentX, containerCenter.y + item.currentY, containerCenter.z + item.currentZ)
                            .translate(-item.localCenterX, -item.localCenterY, -item.localCenterZ);
                        itemRenderer.render(camera, (float) width / height, itemWorld);
                    }
                }

                // E: remove the container item nearest the crosshair ray -
                // same forward-from-camera ray as the door click, but a
                // separate key since left-click is already the door
                // open/close action. Marks whatever was resting above the
                // removed item as falling (see ContainerContents.update).
                if (justPressed(GLFW_KEY_E)) {
                    for (var entry : containerContents.entrySet()) {
                        Vector3f containerCenter = renderer.instanceWorldCenter(entry.getKey());
                        String removed = entry.getValue().removeNearestToRay(containerCenter, camera.position, camera.forward());
                        if (removed != null) {
                            System.out.println("[Container] Removed " + removed);
                            break; // one item per press, from whichever open container's ray hit first
                        }
                    }
                }

                smoothedFps = smoothedFps * 0.9f + (dt > 0f ? 1f / dt : 0f) * 0.1f;

                String[] stats = {
                    String.format("FPS: %.1f  FRAME: %.2fMS", smoothedFps, dt * 1000f),
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
                    "N/B: NEXT/PREV MODEL IN FOLDER",
                };
                text.drawLines(stats, 10, 10, width, height);

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
            siblings = stream.filter(p -> p.toString().toLowerCase().endsWith(".pbd")).sorted().toList();
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
     * contents). A metadata cube has no keyframes of its own - the door
     * that opens it does - so this needs to find that door.
     *
     * Uses the dedicated containerTrigger field (set in Blender via an
     * actual object picker - see properties.py's pbd_container_trigger),
     * naming the door instance by its own id. This used to reuse
     * linkGroup instead, which was a mistake: linkGroup already has its
     * own, different meaning (several doors that open together from one
     * click), and forcing a second, unrelated meaning onto the same
     * field meant a wardrobe with two linked doors couldn't also give
     * its storage cube a distinct group without the two concepts
     * colliding. containerTrigger is a single, clearly-named field for
     * exactly one purpose.
     *
     * Falls back to "is ANY keyframed instance in the whole scene open"
     * only when no trigger is set at all - correct for a scene with
     * just one container, wrong for several independent ones, and
     * exactly why the Blender panel now warns when the field is empty.
     */
    private static boolean isContainerOpen(PbdScene scene, PbdRenderer renderer, int metaIdx) {
        String triggerName = scene.instances.get(metaIdx).params.get("containerTrigger");
        if (triggerName != null) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (triggerName.equals(scene.instances.get(i).id)) {
                    return renderer.isInstanceFullyOpen(i);
                }
            }
            return false; // named trigger doesn't exist in this scene - not open, not a scene-wide fallback either, since the intent was explicit
        }
        for (int i = 0; i < scene.instances.size(); i++) {
            if (renderer.isInstanceFullyOpen(i)) return true;
        }
        return false;
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
            camera.move(
                keysDown[GLFW_KEY_W], keysDown[GLFW_KEY_S],
                keysDown[GLFW_KEY_A], keysDown[GLFW_KEY_D],
                keysDown[GLFW_KEY_SPACE], keysDown[GLFW_KEY_LEFT_SHIFT],
                dt);
        }

        /** Call once per frame after all justPressed() checks for that frame have been made. */
        void endFrame() {
            System.arraycopy(keysDown, 0, keysDownPrev, 0, keysDown.length);
        }
    }
}
