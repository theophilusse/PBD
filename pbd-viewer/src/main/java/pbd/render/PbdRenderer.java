package pbd.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdInstance;
import pbd.format.PbdModifier;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;
import pbd.voxel.DestructionWorld;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

/**
 * Builds the GPU buffers from an already-resolved PbdScene (hierarchy +
 * patches) and renders it either live (tessellating every patch every
 * frame, the original path) or through PbdMeshCache (bake each distinct
 * shape once via Transform Feedback, then draw real vertex buffers with
 * no tessellation cost per frame) - see {@link #useCache}.
 *
 * std430 layouts (verified for real against a live OpenGL context before
 * this class was written - see the README):
 *   PBDInstance : uint type, modifierStart, modifierCount, materialID ;
 *                 vec4 params0, params1                       -> 48 bytes
 *   GpuPatch    : uint instanceIndex, part                    ->  8 bytes
 *   worldTransform : mat4 per instance (HierarchyResolver)    -> 64 bytes
 *   PBDModifier : uint type, curveRef ; 8 bytes of std430
 *                 padding before the vec4 ; vec4 params0      -> 32 bytes
 *   PBDMaterial : vec4 baseColor ; float shininess,
 *                 specularStrength, pad0, pad1                -> 32 bytes
 *
 * Uniform locations (global to the whole linked program, NOT per stage -
 * a collision here has already been a real source of bugs, more than
 * once). Live program:
 *   0=cameraPos(TCS+FS, shared) 1=lodMinDistance 2=lodMaxDistance
 *   3=lodMinLevel 4=lodMaxLevel 5=viewProj(TES) 6=lightDir(FS)
 * Cached program (a separate, much simpler program - its own location
 * numbering, see cached.vert/cached.frag):
 *   0=world(VS) 1=viewProj(VS) 2=cameraPos(FS) 3=lightDir(FS) 4=materialID(FS)
 */
public final class PbdRenderer implements AutoCloseable {

    private static final int LOC_CAMERA_POS   = 0;
    private static final int LOC_LOD_MIN_DIST = 1;
    private static final int LOC_LOD_MAX_DIST = 2;
    private static final int LOC_LOD_MIN_LVL  = 3;
    private static final int LOC_LOD_MAX_LVL  = 4;
    private static final int LOC_VIEW_PROJ    = 5;
    private static final int LOC_LIGHT_DIR    = 6;
    private static final int LOC_LIGHT_COUNT  = 7;

    private static final int CACHED_LOC_WORLD       = 0;
    private static final int CACHED_LOC_VIEW_PROJ   = 1;
    private static final int CACHED_LOC_CAMERA_POS  = 2;
    private static final int CACHED_LOC_LIGHT_DIR   = 3;
    private static final int CACHED_LOC_LIGHT_COUNT = 5;
    private static final int CACHED_LOC_MATERIAL_ID = 4;

    private static final int BINDING_INSTANCES        = 0;
    private static final int BINDING_PATCHES           = 1;
    private static final int BINDING_WORLD_TRANSFORMS  = 2;
    private static final int BINDING_MODIFIERS         = 3;
    private static final int BINDING_MATERIALS         = 4;
    private static final int BINDING_LIGHTS            = 5;

    private final ShaderProgram program;
    private final int vao;
    private final int instanceBuffer;
    private final int patchBuffer;
    private final int worldTransformBuffer;
    private final int modifierBuffer;
    private final int materialBuffer;
    private final int lightBuffer;

    private final ShaderProgram cachedProgram;
    private final int cachedVao;
    private final PbdMeshCache meshCache;
    private MaterialTextureArray textureArray;
    /** Pre-decoded pixel data for voxel-destruction color sampling -
     * see this class's own upload() for why this exists and why it's
     * populated there specifically, not lazily during actual
     * destruction. Keyed by the same resolved texture path string
     * materialOverrides' own "texture" field already holds. */
    public record VoxelTexturePixels(byte[] pixelBytes, int width, int height) {}
    private final Map<String, VoxelTexturePixels> voxelTexturePixels = new java.util.HashMap<>();

    /** Public read-only lookup for the destruction code path - returns
     * null if this exact texture path was never successfully decoded
     * (missing file, unsupported format, ...), which the caller treats
     * as "fall back to this material's own flat color" the same way it
     * already did for a material with no texture= at all. */
    public VoxelTexturePixels voxelTexturePixelsFor(String texturePath) {
        return voxelTexturePixels.get(texturePath);
    }
    private int textureArrayGlId;
    private MaterialTextureArray normalTextureArray;
    private int normalTextureArrayGlId;
    private MaterialTextureArray roughnessTextureArray;
    private int roughnessTextureArrayGlId;
    private MaterialTextureArray displacementTextureArray;
    private int displacementTextureArrayGlId;

    private int patchCount;
    public long totalUploadedBytes;

    // Kept from upload() specifically to compute lastTriangleCount every
    // frame on the CPU (see below) and to bake/render per instance when
    // useCache is on - not re-read from the GPU.
    private List<GpuPatch> patches;
    private Matrix4f[] worldTransforms;
    private PbdScene scene;
    private PrimitiveRegistry primitiveRegistry;
    private ModifierRegistry modifierRegistry;
    private MaterialRegistry materialRegistry;

    /**
     * Switch between the two rendering paths, changeable at any time
     * (Main.java binds a key to it) precisely because Transform Feedback
     * is new to this project and might not behave the same on every
     * driver - flip it off to fall back to the always-worked live path
     * without restarting. The Transform Feedback capture mechanism itself
     * was checked before this was wired up here: an isolated capture
     * matched the already-proven 2*level^2 triangle formula exactly and
     * every captured vertex landed exactly on the expected sphere radius,
     * and a full bake-then-render round trip for a smooth cube was
     * visually confirmed end to end (see the README).
     */
    public boolean useCache = true;

    /** Draw calls issued by the most recent render() - 1 when live (one call for the whole scene), N when cached (one per instance). */
    public int lastDrawCallCount;

    /**
     * Triangle count for the most recent frame. An exact GPU-backed count
     * when useCache is on (summed straight from each instance's baked
     * vertex count - not an estimate); the same CPU-computed estimate as
     * before when useCache is off (unchanged from before this cache existed).
     */
    public int lastTriangleCount;

    public float lodMinDistance = 2f;
    public float lodMaxDistance = 40f;
    public float lodMinLevel = 2f;
    public float lodMaxLevel = 48f;
    public float[] lightDir = {0.3f, -1.0f, -0.2f};

    public int patchCount() {
        return patchCount;
    }

    /** Number of distinct shapes baked so far this session - 0 means nothing has used the cache path yet. */
    public int cacheBakeCount() {
        return meshCache.bakeCount;
    }

    public PbdRenderer(Path shaderDir) throws java.io.IOException {
        program = new ShaderProgram(
            shaderDir.resolve("pbd.vert"),
            shaderDir.resolve("pbd.tesc"),
            shaderDir.resolve("pbd.tese"),
            shaderDir.resolve("pbd.frag"));

        vao = glGenVertexArrays();

        instanceBuffer = glGenBuffers();
        patchBuffer = glGenBuffers();
        worldTransformBuffer = glGenBuffers();
        modifierBuffer = glGenBuffers();
        materialBuffer = glGenBuffers();
        lightBuffer = glGenBuffers();

        glPatchParameteri(GL_PATCH_VERTICES, 1);

        Path cachedShaderDir = shaderDir.resolveSibling("cached");
        cachedProgram = new ShaderProgram(
            cachedShaderDir.resolve("cached.vert"),
            cachedShaderDir.resolve("cached.frag"));
        cachedVao = glGenVertexArrays();
        meshCache = new PbdMeshCache(shaderDir);
    }

    /** Rebuilds every GPU buffer from a scene (on load, or after editing). */
    public void upload(PbdScene scene, PrimitiveRegistry primitiveRegistry,
                        ModifierRegistry modifierRegistry, MaterialRegistry materialRegistry) {
        this.scene = scene;
        this.hasAnimatedInstances = scene.instances.stream().anyMatch(inst -> !inst.keyframes.isEmpty() || inst.leverArm != null);
        this.instanceAnimTime = new double[scene.instances.size()];
        this.instanceOpen = new boolean[scene.instances.size()];
        this.instanceLoop = new boolean[scene.instances.size()];
        this.instanceLoopForward = new boolean[scene.instances.size()];
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            // Keyframed instances start closed - sitting at the first
            // keyframe's time - a cabinet loads shut, not mid-animation.
            instanceAnimTime[i] = inst.keyframes.isEmpty() ? Double.NaN : inst.keyframes.get(0).time;
            instanceOpen[i] = false;
            instanceLoop[i] = "true".equals(inst.params.get("loop"));
            instanceLoopForward[i] = true;
        }
        // Lever arms live in their own pure class (see LeverArmSystem):
        // a fresh one per upload, so nothing of a previous scene's arm
        // state - values, locks, drags - can carry over into this one.
        this.arms = new LeverArmSystem(scene);
        this.primitiveRegistry = primitiveRegistry;
        this.modifierRegistry = modifierRegistry;
        this.materialRegistry = materialRegistry;
        patches = new PatchExpander().expand(scene);
        worldTransforms = new HierarchyResolver().resolve(scene);
        patchCount = patches.size();

        // uploadInstances() and uploadModifiers() both walk scene.instances
        // in the same natural order (a List, for-each) - that is what
        // guarantees modifierStart computed in the former correctly points
        // into the flattened buffer built by the latter.
        uploadInstances(scene, primitiveRegistry, materialRegistry);
        uploadPatches(patches);
        uploadWorldTransforms(worldTransforms);
        uploadModifiers(scene, modifierRegistry);
        uploadMaterials(materialRegistry);
        uploadLights(scene);

        totalUploadedBytes = (long) scene.instances.size() * 48
            + (long) patches.size() * 8
            + (long) worldTransforms.length * 64
            + 32   // modifier stub, see uploadModifiers
            + (long) Math.max(materialRegistry.count(), 1) * 32;
    }

    private static ByteBuffer nativeBuffer(int bytes) {
        // Java allocates ByteBuffers as BIG_ENDIAN by default, but the GPU
        // expects native order (little-endian on nearly everything) -
        // without this .order(), every uploaded float/uint would have its
        // bytes reversed.
        return MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
    }

    private void uploadInstances(PbdScene scene, PrimitiveRegistry primitiveRegistry,
                                  MaterialRegistry materialRegistry) {
        final int stride = 48;
        ByteBuffer buf = nativeBuffer(scene.instances.size() * stride);
        int modifierCursor = 0;
        for (PbdInstance inst : scene.instances) {
            buf.putInt(primitiveRegistry.idOf(inst.type));
            buf.putInt(modifierCursor);
            buf.putInt(inst.modifiers.size());
            buf.putInt(materialRegistry.idOf(inst.material != null ? inst.material : "default"));
            // params0.xyz = cube's per-axis rounding radius (0..0.5 each).
            // smoothX/Y/Z override the uniform `smooth=` on a per-axis
            // basis (e.g. smoothY=0 keeps a cushion's top/bottom flat while
            // smoothX/Z round its side edges) - any axis without its own
            // override falls back to `smooth=` (default 0), so existing
            // files using only `smooth=` keep applying it uniformly.
            float[] cubeSmooth = {0f, 0f, 0f};
            if ("cube".equals(inst.type)) {
                float base = Float.parseFloat(inst.params.getOrDefault("smooth", "0"));
                cubeSmooth[0] = Float.parseFloat(inst.params.getOrDefault("smoothX", String.valueOf(base)));
                cubeSmooth[1] = Float.parseFloat(inst.params.getOrDefault("smoothY", String.valueOf(base)));
                cubeSmooth[2] = Float.parseFloat(inst.params.getOrDefault("smoothZ", String.valueOf(base)));
            }
            buf.putFloat(cubeSmooth[0]).putFloat(cubeSmooth[1]).putFloat(cubeSmooth[2]).putFloat(0); // params0
            buf.putFloat(0).putFloat(0).putFloat(0).putFloat(0); // params1
            modifierCursor += inst.modifiers.size();
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, instanceBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_INSTANCES, instanceBuffer);
        MemoryUtil.memFree(buf);
    }

    private void uploadPatches(List<GpuPatch> patches) {
        ByteBuffer buf = nativeBuffer(patches.size() * 8);
        for (GpuPatch p : patches) {
            buf.putInt(p.instanceIndex);
            buf.putInt(p.part);
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, patchBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_PATCHES, patchBuffer);
        MemoryUtil.memFree(buf);
    }

    private boolean hasAnimatedInstances;
    private double[] instanceAnimTime;   // NaN for non-keyframed instances; otherwise this instance's own local time
    // Every lever arm's run-time state - its live 0..1 value (0 = closed,
    // the instance's own base pose; 1 = the authored open pose), who is
    // driving it (scripted easing, a mouse drag, a lock), what can be
    // grabbed through the primitives attached to it, and its open/close
    // sounds - lives in LeverArmSystem, a pure class with no GL in it
    // (tools/regression/RegressionLeverArmSystem exercises it without a
    // window). This class is its adapter: it feeds the values to
    // HierarchyResolver, plays the sounds and keeps the GPU buffers
    // current. Rebuilt by every upload(), never shared across scenes.
    private LeverArmSystem arms;
    // Where each enabled light was when the lights buffer was last built -
    // a light parented to a door or a lever moves with it, and the buffer
    // is rebuilt when LightTracker sees one has moved.
    private final LightTracker lightTracker = new LightTracker();
    private boolean[] instanceOpen;      // target state: true = animating toward the open end (last keyframe, or leverArm's open pose), false = toward the closed end (first keyframe, or this instance's own base pose) - shared by both mechanisms, since "which way is this door toggled" is the same question either way. Keyframed instances only: a lever arm keeps its own target and value inside LeverArmSystem, and the facade (isInstanceOpen/isInstanceFullyOpen/isInstanceFullyClosed) reads the arm's value rather than any toggle flag, since a drag-driven value has no discrete "toggled" moment at all.
    // Tracks hideInstance()'s own effect SEPARATELY from worldTransforms
    // itself - needed because updateAnimation's own HierarchyResolver
    // rebuild (see that method's own doc) replaces the ENTIRE
    // worldTransforms array from scratch every frame for any animated
    // scene, with no idea a given index was ever hidden; hideInstance's
    // own zero-scale write would otherwise be silently undone on the
    // very next frame for anything with hasAnimatedInstances=true - the
    // actual confirmed cause of a real reported "the original primitive
    // never disappears" bug. null until first used (most scenes never
    // hide anything).
    private boolean[] hiddenInstances;
    private boolean[] instanceLoop;      // true = ping-pongs continuously, ignoring instanceOpen/clicks entirely

    /** True once instance i's keyframe animation has fully reached its
     * last keyframe's time (the "open" pose) - the gate for computing
     * and showing container contents (bin-packed items), per this
     * project's own rule: no computation or display before the
     * container is actually open. False for a non-keyframed instance
     * (index out of the keyframed set entirely) or one still mid-animation. */
    public boolean isInstanceFullyOpen(int instanceIndex) {
        if (instanceIndex < 0 || instanceIndex >= scene.instances.size()) return false;
        PbdInstance inst = scene.instances.get(instanceIndex);
        if (!inst.keyframes.isEmpty()) {
            if (Double.isNaN(instanceAnimTime[instanceIndex])) return false;
            double lastTime = inst.keyframes.get(inst.keyframes.size() - 1).time;
            return instanceOpen[instanceIndex] && Math.abs(instanceAnimTime[instanceIndex] - lastTime) < 0.01;
        }
        // Lever-arm counterpart: "fully open" means the arm value has
        // actually reached 1.0 - purely that, NOT also instanceOpen's own
        // toggle-intent flag (unlike the keyframe branch above). A
        // keyframed door only ever moves via a discrete toggle, so asking
        // "was it toggled open AND has playback finished" is a meaningful
        // extra check there; a lever-arm's value can equally come from a
        // continuous drag with no toggle at all, so requiring instanceOpen
        // to also agree would make a hand-dragged door never register as
        // fully open. The arm value alone is already the authoritative
        // answer to "how open is it."
        return arms.isFullyOpen(instanceIndex);
    }

    /** For a keyframed instance: true the instant it's TOGGLED toward its
     * open pose, regardless of how far the swing animation has actually
     * played - unlike isInstanceFullyOpen, which additionally waits for
     * playback to reach the last keyframe. Container triggers use this
     * one: a player shouldn't have to wait out a door's whole swing
     * animation before its contents appear, only isInstanceFullyOpen's
     * stricter "animation has actually finished" sense is appropriate for
     * that.
     *
     * For a lever-arm instance: true once its arm value has moved open by
     * ANY visible amount, read directly off the arm's live value
     * (LeverArmSystem) rather than a toggle flag - a drag-driven arm has no discrete "toggled" moment
     * the way a click does, so the toggle-intent flag isn't a meaningful
     * question to ask here; the continuous value already is the real
     * answer, the same way isInstanceFullyOpen above now reads it. */
    public boolean isInstanceOpen(int instanceIndex) {
        if (instanceIndex < 0 || instanceIndex >= scene.instances.size()) return false;
        if (arms.has(instanceIndex) && Double.isNaN(instanceAnimTime[instanceIndex])) return arms.isOpen(instanceIndex); // keyframes win when both are authored, as in HierarchyResolver
        return instanceOpen[instanceIndex];
    }

    /** True once this instance is toggled CLOSED and its swing animation
     * has actually finished playing back to the first keyframe - the
     * closing-side mirror of isInstanceFullyOpen. Container eviction
     * uses this one deliberately (see areAllDoorsClosed in Main.java):
     * clearing a container's contents the instant a door is merely
     * clicked shut, before it's actually swung across the opening, would
     * make the items visibly vanish while still exposed through the
     * gap - waiting for the door to actually finish closing is what
     * makes them look like they disappeared BEHIND it instead. An
     * instance with no keyframes at all counts as always "closed" (true)
     * rather than never - there's no animation to wait out, and treating
     * it as permanently unclosed would block eviction forever for a
     * misconfigured or purely-decorative trigger reference. */
    public boolean isInstanceFullyClosed(int instanceIndex) {
        if (instanceIndex < 0 || instanceIndex >= scene.instances.size()) return true;
        PbdInstance inst = scene.instances.get(instanceIndex);
        if (!inst.keyframes.isEmpty()) {
            if (Double.isNaN(instanceAnimTime[instanceIndex])) return true;
            double firstTime = inst.keyframes.get(0).time;
            return !instanceOpen[instanceIndex] && Math.abs(instanceAnimTime[instanceIndex] - firstTime) < 0.01;
        }
        // Lever-arm counterpart, same shape as isInstanceFullyOpen above -
        // the arm value alone decides this, not instanceOpen, for the
        // same reason isInstanceFullyOpen no longer checks it either: a
        // drag-driven door can sit at 0.0 with no toggle having happened
        // at all, and that's still genuinely "fully closed."
        return arms.isFullyClosed(instanceIndex); // true as well for an instance with no arm: no animation mechanism at all - always "closed", same existing rule as before lever-arm existed
    }

    /** Whether the container metaIdx belongs to should be considered
     * "open" (the gate a caller - Main.java's own container-contents
     * display, a modder's own AI code - uses to decide whether this
     * container's contents should be visible at all) - true if ANY ONE
     * of its linked doors (containerTriggers, plural - see PbdInstance)
     * is toggled open. A storage cube can list several doors (a big
     * wardrobe with two independent doors over one shelf), and several
     * storage cubes can share one door too (a door with pockets on both
     * sides) - both directions fall out naturally from "each cube lists
     * the doors that open it". Falls back to "is ANY instance in the
     * whole scene toggled open" only when the cube lists no doors at
     * all. Uses isInstanceOpen (toggled-open, regardless of animation
     * progress), NOT isInstanceFullyClosed below - contents should
     * appear the moment a linked door is clicked open, not only once
     * its whole swing animation has finished playing out.
     *
     * Promoted here from a Main.java-private helper of the same name
     * and behavior - that version worked correctly, but lived only in
     * the application's own game-loop code, unreachable by a modder's
     * own script/AI logic the way every other runtime query
     * (isInstanceOpen, getContainerItems, setOpen, ...) already is;
     * this IS that same logic, just where the rest of the facade
     * already lives, not a reimplementation. */
    public boolean isContainerOpen(int metaIdx) {
        java.util.List<String> triggers = scene.instances.get(metaIdx).containerTriggers;
        if (!triggers.isEmpty()) {
            return anyTriggerMatches(triggers, true);
        }
        for (int i = 0; i < scene.instances.size(); i++) {
            if (isInstanceOpen(i)) return true;
        }
        return false;
    }

    /** True once every one of a container's linked doors is FULLY
     * closed - not just toggled shut, but actually finished swinging
     * back to its closed pose (isInstanceFullyClosed above).
     * Deliberately asymmetric with isContainerOpen: showing contents
     * the instant a door is clicked open feels responsive, but clearing
     * them the instant it's clicked shut - before it's actually swung
     * across the opening - would make items visibly vanish while still
     * exposed through the gap instead of disappearing behind a closed
     * door. This is also the accessor a zombie's own AI would check to
     * know whether it can still see a hiding player through a door
     * that's ajar rather than fully shut - for the finer-grained version
     * of that question (exactly HOW ajar, not just open-vs-closed), see
     * getArmValue, which now exists for a lever-arm instance. Promoted
     * here for the same reason isContainerOpen was - a modder's own code
     * couldn't reach the Main.java-private version this replaces. */
    public boolean areAllDoorsClosed(int metaIdx) {
        java.util.List<String> triggers = scene.instances.get(metaIdx).containerTriggers;
        if (triggers.isEmpty()) return true;
        for (String triggerName : triggers) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (triggerName.equals(scene.instances.get(i).id) && !isInstanceFullyClosed(i)) {
                    return false; // this linked door is either still open or still swinging shut
                }
            }
        }
        return true;
    }

    private boolean anyTriggerMatches(java.util.List<String> triggerNames, boolean requireOpen) {
        for (String triggerName : triggerNames) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (triggerName.equals(scene.instances.get(i).id) && isInstanceOpen(i) == requireOpen) {
                    return true;
                }
            }
        }
        return false;
    }

    /** World-space center and full (not half) extent of instance i, for
     * positioning container contents inside its metadata volume. */
    public Vector3f instanceWorldCenter(int instanceIndex) {
        return worldTransforms[instanceIndex].getTranslation(new Vector3f());
    }

    public Vector3f instanceWorldSize(int instanceIndex) {
        Vector3f scale = worldTransforms[instanceIndex].getScale(new Vector3f());
        return scale; // every canonical primitive here is a 1-unit-native shape at scale=1, so world scale IS the full extent directly
    }

    public Matrix4f instanceWorldMatrix(int instanceIndex) {
        return new Matrix4f(worldTransforms[instanceIndex]);
    }

    /** The CURRENT, live resolved transform for every instance -
     * recomputed fresh from instanceAnimTime, deliberately bypassing
     * hiddenInstances' own zeroing (worldTransforms itself would
     * return all-zero for anything hidden - see hideInstance's own
     * doc). Exists specifically so a voxel-destruction result can keep
     * tracking its original instance's own ongoing animation instead
     * of freezing at wherever it happened to be at the moment it broke
     * - a shot-up door should still swing, damage and all. An earlier
     * version of this fix captured a one-time snapshot at destruction
     * time instead, which was reported as visibly wrong (the debris
     * stopped following the door entirely) - this recomputes fresh
     * every time it's called instead, the same way worldTransforms
     * itself would if hiding didn't zero it. Not cheap to call per-
     * instance (a full scene resolve every time) - a caller that needs
     * several indices from the same frame should call this ONCE and
     * reuse the result, not call it once per index. */
    public Matrix4f[] resolveLiveTransforms() {
        return new HierarchyResolver().resolve(scene, instanceAnimTime, arms.values());
    }

    /** World-space ROTATION of instance i, separate from
     * instanceWorldCenter's translation-only extraction above - added
     * specifically because container content positioning (Main.java's
     * render loop and ContainerContents.removeNearestToRay both) used
     * to add an item's LOCAL offset straight onto the container's world
     * CENTER with no rotation applied at all, correct only for an
     * axis-aligned container. A rotated storage cube (the normal case
     * for anything not perfectly wall-aligned) made items render - and
     * hit-test - as if the box were still unrotated, visibly poking
     * outside its actual (rotated) bounds. Reproduced by inspection of
     * both call sites, not by observing it directly (no graphical
     * context here to render and look at), but the missing rotation
     * application is unambiguous either way. */
    public Quaternionf instanceWorldRotation(int instanceIndex) {
        return extractRotationRobust(worldTransforms[instanceIndex]);
    }

    /** Same fix, same reason, as Main.java's own extractRotationRobust
     * (that copy's own doc has the full story: a direct numeric test
     * confirmed JOML's Matrix4f.getNormalizedRotation() returns
     * something that ISN'T EVEN A VALID UNIT QUATERNION for a matrix
     * with strongly non-uniform scale - a 27:1 ratio between axes in
     * the real reported case). Duplicated rather than shared across
     * the two files for the same reason several other small helpers in
     * this codebase already are (see e.g. _shared_remote_cache_dir in
     * the Python addon's own history) - a two-line static helper isn't
     * worth a new shared utility class or a public-API change to pull
     * across a package boundary for. This copy specifically matters for
     * instanceWorldRotation just above: container-item placement/hit-
     * testing (ContainerContents.itemWorldPosition and
     * removeNearestToRay, both driven by this method) would have
     * inherited the exact same wrong-rotation bug for any container
     * whose own scale is non-uniform enough to trigger it - most of
     * them, to some degree, since a storage cube is rarely perfectly
     * cubic. */
    private static Quaternionf extractRotationRobust(Matrix4f m) {
        Vector3f colX = new Vector3f(m.m00(), m.m01(), m.m02()).normalize();
        Vector3f colY = new Vector3f(m.m10(), m.m11(), m.m12()).normalize();
        Vector3f colZ = new Vector3f(m.m20(), m.m21(), m.m22()).normalize();
        Matrix4f rotOnly = new Matrix4f(
            colX.x, colX.y, colX.z, 0,
            colY.x, colY.y, colY.z, 0,
            colZ.x, colZ.y, colZ.z, 0,
            0, 0, 0, 1);
        return rotOnly.getNormalizedRotation(new Quaternionf());
    }

    public PbdScene scene() {
        return scene;
    }
    private boolean[] instanceLoopForward; // current ping-pong direction, looping instances only
    private static final double KEYFRAME_PLAYBACK_RATE = 1.0; // seconds of animation time per real second - matches the time= units keyframes are authored in

    /** Whether instance i is driven by EITHER animation mechanism this
     * class supports (keyframes or lever-arm) - the shared gate used
     * everywhere a click or a setOpen() call needs to know "does
     * toggling instanceOpen[i] mean anything for this instance at all",
     * without caring which of the two it actually is. Not used inside
     * updateAnimation itself, which (unlike these callers) DOES need to
     * tell the two apart, to run the right easing math for each. */
    private boolean isAnimatable(int i) {
        return !Double.isNaN(instanceAnimTime[i]) || arms.has(i);
    }

    /**
     * Advances every keyframed or lever-arm instance's own progress
     * toward its current open/closed target, then re-resolves and
     * re-uploads world transforms - only does any work if the scene
     * actually has an animatable instance of either kind, so a static
     * scene pays nothing extra per frame. Call once per frame, before
     * render().
     */
    public pbd.audio.SoundPlayer soundPlayer; // null = no audio (default) - set from Main.java once a SoundPlayer exists; keyframe sound triggers below are a no-op until then

    public void updateAnimation(double dt) {
        if (!hasAnimatedInstances) return;
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            if (!Double.isNaN(instanceAnimTime[i])) {
                double timeBeforeUpdate = instanceAnimTime[i];
                double lastTime = inst.keyframes.get(inst.keyframes.size() - 1).time;
                double firstTime = inst.keyframes.get(0).time;
                double step = KEYFRAME_PLAYBACK_RATE * dt;

                if (instanceLoop[i]) {
                    // Continuous ping-pong, ignoring open/closed state
                    // and clicks entirely - a ceiling fan or decorative
                    // element, not a container someone opens and closes.
                    if (instanceLoopForward[i]) {
                        instanceAnimTime[i] += step;
                        if (instanceAnimTime[i] >= lastTime) {
                            instanceAnimTime[i] = lastTime;
                            instanceLoopForward[i] = false;
                        }
                    } else {
                        instanceAnimTime[i] -= step;
                        if (instanceAnimTime[i] <= firstTime) {
                            instanceAnimTime[i] = firstTime;
                            instanceLoopForward[i] = true;
                        }
                    }
                    continue;
                }

                double target = instanceOpen[i] ? lastTime : firstTime;
                if (instanceAnimTime[i] < target) {
                    instanceAnimTime[i] = Math.min(target, instanceAnimTime[i] + step);
                } else if (instanceAnimTime[i] > target) {
                    instanceAnimTime[i] = Math.max(target, instanceAnimTime[i] - step);
                }
                checkSoundTrigger(inst, timeBeforeUpdate, instanceAnimTime[i]);
            }
        }
        // Lever arms are stepped by LeverArmSystem (easing toward the
        // open/closed target at each arm's own `speed`, ping-pong for a
        // looping one, nothing at all for a locked or hand-held one), which
        // also reports the open/close sound each arm owes - played here,
        // since this is the class that owns the SoundPlayer.
        arms.update(dt, soundPlayer == null ? null : soundPlayer::play);
        worldTransforms = new HierarchyResolver().resolve(scene, instanceAnimTime, arms.values());
        // Re-apply every hideInstance() call THIS rebuild would
        // otherwise silently undo - see hiddenInstances' own doc. Must
        // run AFTER the HierarchyResolver line above, never before -
        // that call replaces the whole array wholesale, so anything
        // done to it earlier in this method would just be discarded.
        if (hiddenInstances != null) {
            for (int i = 0; i < hiddenInstances.length; i++) {
                if (hiddenInstances[i]) worldTransforms[i] = new Matrix4f().scale(0f);
            }
        }
        uploadWorldTransforms(worldTransforms);
        // A light hung on a swinging door or a lever moved with it just
        // now: its entry in the lights buffer is stale until rebuilt (the
        // buffer used to be filled once, at load, and again only on a
        // light switch - the lamp's mesh moved away and its light stayed
        // behind).
        if (lightTracker.moved(worldTransforms)) uploadLights(scene);
    }

    /** Plays any keyframe's sound field whose time was just crossed
     * during this frame's animation update - checks every keyframe
     * rather than assuming at most one can be crossed per frame, since a
     * very short keyframe gap combined with a slow frame could otherwise
     * skip one silently. Direction-agnostic (crossing forward while
     * opening or backward while closing both trigger it), since a real
     * creak generally happens both ways. */
    private void checkSoundTrigger(PbdInstance inst, double timeBefore, double timeAfter) {
        if (soundPlayer == null) return;
        double lo = Math.min(timeBefore, timeAfter);
        double hi = Math.max(timeBefore, timeAfter);
        for (PbdInstance.Keyframe kf : inst.keyframes) {
            if (kf.sound != null && kf.time > lo && kf.time <= hi) {
                soundPlayer.play(kf.sound);
            }
        }
    }

    /**
     * Drives every instance whose keyframes specify a `channel=` (see
     * PbdInstance.Keyframe) from that named channel's current value
     * (tracker.get(channelName)) instead of the fixed-rate animation
     * clock updateAnimation() uses - a generic mechanism (growth is just
     * one use of it) for anything that should progress at its own,
     * condition-dependent pace: moss/mushrooms via a "humidity" channel
     * that only accumulates while it's raining, say, rather than at a
     * fixed real-time rate.
     *
     * Deliberately a separate method/pass from updateAnimation and
     * HierarchyResolver.resolve, rather than folding channel support
     * into either: those are shared by every door/lid/container in this
     * project and already extensively exercised - duplicating the lerp
     * logic here (extended to scale, which HierarchyResolver's own
     * interpolate() doesn't support at all) is a deliberate, small
     * amount of repeated code in exchange for not touching that already-
     * proven path. Worth unifying later, not under the time pressure
     * this was written under.
     *
     * Only instances with NO parent are supported for now - a channel-
     * driven instance's world matrix is built directly here and doesn't
     * consult parentIndex, unlike HierarchyResolver's own resolve().
     * Fine for standalone decorative growth (moss, mushrooms), not yet
     * for a channel-driven child of another instance.
     */
    public void updateChannelDrivenInstances(ChannelTracker tracker) {
        boolean anyUpdated = false;
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            String channelName = firstChannelOf(inst);
            if (channelName == null) continue;
            if (inst.parentIndex >= 0) continue; // not yet supported - see method doc comment

            double channelValue = tracker.get(channelName);
            java.util.List<PbdInstance.Keyframe> onChannel = new java.util.ArrayList<>();
            for (PbdInstance.Keyframe kf : inst.keyframes) {
                if (channelName.equals(kf.channel)) onChannel.add(kf);
            }
            if (onChannel.isEmpty()) continue;

            ChannelPose pose = interpolateChannel(inst, onChannel, channelValue);
            Quaternionf rot = new Quaternionf().identity()
                .rotateZ((float) Math.toRadians(pose.rotDeg.z))
                .rotateY((float) Math.toRadians(pose.rotDeg.y))
                .rotateX((float) Math.toRadians(pose.rotDeg.x));
            worldTransforms[i] = new Matrix4f().translationRotateScale(pose.pos, rot, pose.scale);
            anyUpdated = true;
        }
        if (anyUpdated) uploadWorldTransforms(worldTransforms);
    }

    public boolean hasChannelDrivenInstances() {
        for (PbdInstance inst : scene.instances) {
            if (firstChannelOf(inst) != null) return true;
        }
        return false;
    }

    /** An instance is assumed to use at most one channel across all its
     * keyframes for this first pass - the first non-null channel found,
     * in keyframe order, wins. Mixing several channels on one instance
     * isn't rejected, just not meaningfully supported yet (only that
     * first channel's keyframes are considered). */
    private String firstChannelOf(PbdInstance inst) {
        for (PbdInstance.Keyframe kf : inst.keyframes) {
            if (kf.channel != null) return kf.channel;
        }
        return null;
    }

    private static final class ChannelPose {
        final Vector3f pos, rotDeg, scale;
        ChannelPose(Vector3f pos, Vector3f rotDeg, Vector3f scale) {
            this.pos = pos; this.rotDeg = rotDeg; this.scale = scale;
        }
    }

    /** Same lerp-between-bracketing-keyframes, clamp-at-the-ends logic
     * as HierarchyResolver's own interpolate(), extended to scale (that
     * method doesn't have it) and reading Euler degrees directly rather
     * than composing a Quaternionf partway through, since the caller
     * above needs the raw degrees to build its own quaternion the same
     * way every other rotation in this project does (rotateZ then Y
     * then X). */
    private ChannelPose interpolateChannel(PbdInstance inst, java.util.List<PbdInstance.Keyframe> kf, double value) {
        if (value <= kf.get(0).time || kf.size() == 1) {
            return resolveChannelKeyframe(inst, kf.get(0));
        }
        PbdInstance.Keyframe last = kf.get(kf.size() - 1);
        if (value >= last.time) {
            return resolveChannelKeyframe(inst, last);
        }
        for (int i = 0; i < kf.size() - 1; i++) {
            PbdInstance.Keyframe a = kf.get(i);
            PbdInstance.Keyframe b = kf.get(i + 1);
            if (value >= a.time && value <= b.time) {
                float f = (b.time - a.time) > 1e-6f ? (float) ((value - a.time) / (b.time - a.time)) : 0f;
                Vector3f posA = a.pos != null ? a.pos : inst.position;
                Vector3f posB = b.pos != null ? b.pos : inst.position;
                Vector3f rotA = a.rotDeg != null ? a.rotDeg : inst.rotationDeg;
                Vector3f rotB = b.rotDeg != null ? b.rotDeg : inst.rotationDeg;
                Vector3f scaleA = a.scale != null ? a.scale : inst.scale;
                Vector3f scaleB = b.scale != null ? b.scale : inst.scale;
                return new ChannelPose(
                    lerp(posA, posB, f),
                    lerp(rotA, rotB, f),
                    lerp(scaleA, scaleB, f));
            }
        }
        return resolveChannelKeyframe(inst, last); // unreachable given the bounds checks above, kept for safety
    }

    private ChannelPose resolveChannelKeyframe(PbdInstance inst, PbdInstance.Keyframe k) {
        Vector3f pos = k.pos != null ? k.pos : inst.position;
        Vector3f rotDeg = k.rotDeg != null ? k.rotDeg : inst.rotationDeg;
        Vector3f scale = k.scale != null ? k.scale : inst.scale;
        return new ChannelPose(pos, rotDeg, scale);
    }

    private Vector3f lerp(Vector3f a, Vector3f b, float f) {
        return new Vector3f(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }
    /**
     * Standard ray-vs-axis-aligned-box "slab" test, in whatever space
     * origin/dir are already expressed in (the caller transforms into
     * the box's own local space first, where the box itself is just
     * -halfExtent..halfExtent on every axis). Returns the ray parameter
     * t of the nearest intersection (>= 0), or -1 for no hit. If the
     * origin is already inside the box, returns 0 (an immediate hit)
     * rather than the exit point, since "the ray started inside" should
     * count as a hit at the camera itself, not somewhere past the box.
     */
    /** Public wrapper around the private canonical-local-space ray-box
     * test below - exists for a caller (Main.java's own G-key re-target
     * logic) that needs to test a ray against an arbitrary local-space
     * transform it already has in hand (an already-destroyed instance's
     * own LIVE transform, from resolveLiveTransforms - not something
     * findDestructibleAlongRay's own internal loop, which only ever
     * walks scene.instances itself, has any way to be handed instead). */
    public float rayBoxIntersectionPublic(Vector3f origin, Vector3f dir, float minExtent, float maxExtent) {
        return rayBoxIntersection(origin, dir, minExtent, maxExtent);
    }

    private float rayBoxIntersection(Vector3f origin, Vector3f dir, float minExtent, float maxExtent) {
        float tMin = Float.NEGATIVE_INFINITY;
        float tMax = Float.POSITIVE_INFINITY;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {dir.x, dir.y, dir.z};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-8f) {
                if (o[axis] < minExtent || o[axis] > maxExtent) return -1; // parallel to this axis's faces and outside the slab - never hits
                continue;
            }
            float t1 = (minExtent - o[axis]) / d[axis];
            float t2 = (maxExtent - o[axis]) / d[axis];
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        if (tMax < 0) return -1; // box is entirely behind the ray origin
        float result = Math.max(tMin, 0f);
        // Defensive second layer, on top of the caller-side hiddenInstances
        // skip above (the actual root cause this turn's fix addresses) -
        // NaN can only reach here if some OTHER path this method wasn't
        // specifically audited for feeds it a degenerate direction/matrix
        // (dir containing NaN itself, say) - Float.isNaN is the one check
        // that's actually true for NaN (NaN != NaN is true, NaN < x and
        // NaN > x are both false, which is exactly how a NaN t used to
        // slip through every comparison in this method silently rather
        // than being caught anywhere).
        return Float.isNaN(result) ? -1 : result;
    }

    /** The hit point from the most recent findDestructibleAlongRay / destroyAt call that found
     * something, in the hit instance's own LOCAL space (the canonical, UNSCALED -0.5..0.5 box
     * for a primitive; the mesh's own units for a mesh). Set together with the index that call
     * returned, so it always belongs to it - not meaningful (and not touched) after a miss. */
    public final Vector3f lastHitLocalPoint = new Vector3f();

    /** The broad phase of the G key: the index of the nearest destructible, never-destroyed
     * instance whose bounding box the ray crosses, or -1. The rule itself - what is skipped,
     * which box a primitive or a mesh is tested against, the per-instance console trace that
     * finally separated "the raycast never reaches a geometry test" from "the test missed" from
     * "it hit but nothing happened" - lives in DestructionWorld.findFresh, where it runs under
     * tools/regression without a window. */
    public int findDestructibleAlongRay(Vector3f rayOrigin, Vector3f rayDir) {
        DestructionWorld.Fresh fresh = findFresh(rayOrigin, rayDir);
        if (fresh == null) return -1;
        lastHitLocalPoint.set(fresh.localHit());
        return fresh.index();
    }

    private DestructionWorld.Fresh findFresh(Vector3f rayOrigin, Vector3f rayDir) {
        return destruction().findFresh(new Vector3d(rayOrigin.x, rayOrigin.y, rayOrigin.z),
            new Vector3d(rayDir.x, rayDir.y, rayDir.z), System.out::println);
    }

    /** Same per-instance verbosity as findDestructibleAlongRay just
     * above, for the same reason - H (Hide) has the identical class of
     * reported symptom (nothing happens, no crash), so it gets the
     * same "which instances even qualify, which were geometry-tested
     * and missed" breakdown. */
    public int findMetadataCubeAlongRay(Vector3f rayOrigin, Vector3f rayDir) {
        int closestIndex = -1;
        float closestDist = Float.MAX_VALUE;
        int metadataCount = 0;
        for (int i = 0; i < scene.instances.size(); i++) {
            if ("true".equals(scene.instances.get(i).params.get("metadata"))) metadataCount++;
        }
        System.out.println("[H-key] ray origin=" + rayOrigin + " dir=" + rayDir
            + " - " + metadataCount + " metadata (container) instance(s) in this scene");
        for (int i = 0; i < scene.instances.size(); i++) {
            if (!"true".equals(scene.instances.get(i).params.get("metadata"))) continue;

            Matrix4f invWorld = new Matrix4f(worldTransforms[i]).invert();
            Vector3f localOrigin = invWorld.transformPosition(new Vector3f(rayOrigin));
            Vector3f localDir = invWorld.transformDirection(new Vector3f(rayDir));

            float t = rayBoxIntersection(localOrigin, localDir, -0.5f, 0.5f); // exact canonical bounds, no margin - a container's actual volume, not a slightly-generous click target like a door
            if (t < 0) {
                System.out.println("  #" + i + " '" + scene.instances.get(i).id + "'"
                    + " world-pos=" + worldTransforms[i].getTranslation(new Vector3f())
                    + " TESTED-MISSED");
                continue;
            }

            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            Vector3f worldHit = worldTransforms[i].transformPosition(new Vector3f(localHit));
            float worldDist = worldHit.distance(rayOrigin);
            System.out.println("  #" + i + " '" + scene.instances.get(i).id + "' HIT at world-dist=" + worldDist);
            if (worldDist < closestDist) {
                closestDist = worldDist;
                closestIndex = i;
            }
        }
        System.out.println("[H-key] closest hit: " + (closestIndex < 0 ? "NONE" : "#" + closestIndex + " '" + scene.instances.get(closestIndex).id + "'"));
        return closestIndex;
    }

    /** Closest-hit ray search over every eligible instance (the keyframed
     * ones, for toggleKeyframedInstanceAlongRay): ray-vs-oriented-box,
     * with a hidden (voxel-destroyed) instance tested at its live
     * transform. eligible decides which instances even get geometry-tested
     * at all. (Grabbing a lever arm is a different search - it also looks
     * through every primitive attached to the arm and answers with the
     * arm to move: LeverArmSystem.pick.)
     * Returns -1 if nothing eligible is hit. */
    private int findClosestInstanceAlongRay(Vector3f rayOrigin, Vector3f rayDir,
            java.util.function.IntPredicate eligible) {
        if (!hasAnimatedInstances) return -1;
        int closestIndex = -1;
        float closestDist = Float.MAX_VALUE;
        // Computed once, ONLY if there's actually a hidden instance to
        // need it for (resolveLiveTransforms isn't cheap - see its own
        // doc) - null otherwise, and never touched below unless an
        // instance turns out to actually be hidden.
        Matrix4f[] live = null;
        for (int i = 0; i < scene.instances.size(); i++) {
            if (!eligible.test(i)) continue;
            if (instanceLoop[i]) continue; // ping-pongs continuously, clicks/grabs have no effect on it

            // Ray-vs-oriented-box, not a uniform bounding sphere: a
            // sphere sized off the LARGEST axis (radius = max(sx,sy,sz))
            // was wildly oversized for anything tall-and-thin or wide-
            // and-flat - a door panel with scale=(0.2,7,4) got a
            // radius-9.1 sphere reaching far past its actual 0.2-thick,
            // 4-wide extent, so a click nowhere near the visible door
            // still registered a hit. Transforming the ray into the
            // instance's own local space and testing against the
            // canonical -0.5..0.5 box there respects each axis's real
            // size instead of inflating every axis to match the
            // longest one.
            //
            // A HIDDEN (voxel-destroyed) instance's own worldTransforms
            // entry is zeroed-scale (see hideInstance's own doc) - a
            // degenerate box with no volume a click could ever land in,
            // regardless of the NaN-from-inverting-a-singular-matrix
            // issue rayBoxIntersection itself now guards against
            // separately. The actual fix for a real, reported
            // "clicking a voxel no longer triggers its animation" bug:
            // its LIVE transform (the same one the voxel debris is
            // actually drawn at, animation included - see
            // resolveLiveTransforms's own doc) is what a click should
            // be tested against instead, since debris is still
            // logically "the same door", just damaged - not something a
            // player should lose the ability to open/close by shooting
            // it.
            Matrix4f effectiveTransform = worldTransforms[i];
            if (hiddenInstances != null && i < hiddenInstances.length && hiddenInstances[i]) {
                if (live == null) live = resolveLiveTransforms();
                effectiveTransform = live[i];
            }
            Matrix4f invWorld = new Matrix4f(effectiveTransform).invert();
            Vector3f localOrigin = invWorld.transformPosition(new Vector3f(rayOrigin));
            Vector3f localDir = invWorld.transformDirection(new Vector3f(rayDir));

            float t = rayBoxIntersection(localOrigin, localDir, -0.55f, 0.55f); // 0.5 canonical half-extent + a small margin so an edge click still lands
            if (t < 0) continue;

            // Compare hit distance in WORLD space (t alone is in the
            // instance's own, differently-scaled local space and isn't
            // comparable across instances) - re-derive the world-space
            // hit point and measure from the real ray origin.
            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            Vector3f worldHit = effectiveTransform.transformPosition(new Vector3f(localHit));
            float worldDist = worldHit.distance(rayOrigin);
            if (worldDist < closestDist) {
                closestDist = worldDist;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    /** Despite the name (kept to avoid disturbing every existing caller -
     * see Main.java - over a rename with no behavior change of its
     * own beyond what's described here), this is now KEYFRAME-ONLY. A
     * lever-arm instance used to be toggled the same way (a click
     * scripted-easing it fully open/closed), which turned out to be the
     * wrong default: a lever-arm's point is that it does NOT have to
     * move on a fixed, scripted animation, so player input for it is now
     * exclusively the drag-and-drop pair below (findLeverArmGrabPoint /
     * dragArmValueTowardRay), never this click-toggle. setOpen is still
     * shared by both mechanisms, for a non-player caller (a script, an
     * AI, a mod) that legitimately wants to just snap a lever-arm door
     * open/closed programmatically, with no player drag involved at
     * all - only the PLAYER-CLICK path narrowed here. */
    public int toggleKeyframedInstanceAlongRay(Vector3f rayOrigin, Vector3f rayDir) {
        int closestIndex = findClosestInstanceAlongRay(rayOrigin, rayDir,
            i -> !Double.isNaN(instanceAnimTime[i]));
        if (closestIndex < 0) return -1;

        // linkGroup (a free-form field - see PbdParser's default case for
        // unrecognized instance fields, no dedicated syntax needed) makes
        // multiple keyframed instances move together: a wardrobe's two
        // doors, tagged with the same linkGroup name, open and close in
        // sync from clicking either one, rather than each needing its
        // own separate click. Instances with no linkGroup keep the
        // original one-object-at-a-time behavior exactly as before.
        String group = scene.instances.get(closestIndex).params.get("linkGroup");
        boolean newState = !instanceOpen[closestIndex];
        if (group == null) {
            instanceOpen[closestIndex] = newState;
            playSoundAtCurrentTime(closestIndex);
        } else {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (Double.isNaN(instanceAnimTime[i])) continue; // keyframe-only, same narrowing as the hit test above - a lever-arm door sharing this linkGroup is left alone, it's drag-only now
                if (group.equals(scene.instances.get(i).params.get("linkGroup"))) {
                    instanceOpen[i] = newState;
                    playSoundAtCurrentTime(i);
                }
            }
        }
        return closestIndex;
    }

    /** What a click on a lever arm grabbed: instanceIndex is the ARM that
     * will move (not necessarily the primitive the ray touched - see
     * findLeverArmGrabPoint), localPoint the grabbed point in that arm's
     * own local space. */
    public record LeverArmGrab(int instanceIndex, Vector3f localPoint) {}

    /** Read-only hit test for the first step of a drag (see
     * dragArmValueTowardRay and setArmValue's own doc for the rest of it):
     * the nearest primitive the ray hits among every lever arm and
     * everything attached to one, answered as the ARM that should move
     * plus the hit point in that arm's own LOCAL space - the fixed
     * reference point a caller should hold onto for the whole drag, since
     * its LOCAL position never changes while its WORLD position sweeps
     * through the door's whole swing.
     *
     * "Everything attached" is the point: a door is usually a group pivot
     * with a panel, a handle and a latch hanging on it (parent=), and the
     * player grabs whichever of them is under the crosshair - any
     * primitive type, at any depth - and it is the pivot's arm that moves
     * (LeverArmSystem.pick). A hidden (voxel-destroyed) instance is tested
     * at its live transform, the one its debris is drawn at.
     *
     * Returns null on a miss. A LOCKED arm is still returned (so the caller
     * can say "it's locked" - see isArmLocked); dragging it simply does
     * nothing. Never mutates any state - purely a query, safe to call on
     * every click to decide whether a drag should start at all. */
    public LeverArmGrab findLeverArmGrabPoint(Vector3f rayOrigin, Vector3f rayDir) {
        if (!hasAnimatedInstances) return null;
        Matrix4f[][] live = new Matrix4f[1][]; // resolved once, and only if something hidden is actually asked about
        LeverArmSystem.Grab grab = arms.pick(i -> {
            if (!isHiddenInstance(i)) return worldTransforms[i];
            if (live[0] == null) live[0] = resolveLiveTransforms();
            return live[0][i];
        }, rayOrigin, rayDir);
        return grab == null ? null : new LeverArmGrab(grab.owner(), grab.localPoint());
    }

    /** One frame's worth of drag update for a lever-arm instance already
     * grabbed (see findLeverArmGrabPoint): finds the armValue whose
     * resulting world transform would place localGrabPoint (captured once,
     * at grab time, in that instance's own local space) closest to THIS
     * frame's ray, and commits that value via setArmValue. Call once per
     * frame for as long as the player holds the grab, with that frame's
     * own current camera ray - the value is meant to track wherever the
     * player is aiming continuously, not just where they started.
     *
     * The search itself (coarse sampling of 0..1, then a refinement, so
     * the arm follows the aim smoothly rather than in 2.5 % steps, and
     * lands exactly on an end when the aim is past it) is
     * LeverArmSystem.dragToward; it works for any lever arm - pure
     * rotation, pure position or both - without assuming a hinge axis.
     * A hidden (voxel-destroyed) arm can still be dragged: the search uses
     * the arm's parent's live transform, which the arm's own motion does
     * not change. No physics behind it yet - the value is purely where the
     * aim puts it.
     *
     * Returns the armValue it committed, or Double.NaN (nothing
     * committed) if instanceId isn't a lever-arm instance or is locked. */
    public double dragArmValueTowardRay(String instanceId, Vector3f localGrabPoint, Vector3f rayOrigin, Vector3f rayDir) {
        int idx = indexOfInstance(instanceId);
        if (idx < 0 || !arms.has(idx)) return Double.NaN;
        int parent = scene.instances.get(idx).parentIndex;
        Matrix4f parentWorld = parent < 0 ? new Matrix4f() : liveWorldOf(parent);
        return arms.dragToward(idx, localGrabPoint, rayOrigin, rayDir, parentWorld);
    }

    /** Sets (not toggles) instance instanceId's own open/closed state
     * directly by name - the programmatic equivalent of clicking it via
     * toggleKeyframedInstanceAlongRay just above, minus the raycast:
     * same linkGroup handling (every instance sharing this one's own
     * linkGroup moves together), same sound-on-transition behavior.
     * Exists specifically so a caller (a facade, a test script, a mod)
     * can open/close a door WITHOUT needing a working camera raycast at
     * all - useful on its own, and specifically useful for isolating
     * whether a reported "nothing happens" bug is in the INPUT/raycast
     * layer or in whatever's supposed to happen once something IS
     * triggered: calling this directly skips the input layer entirely.
     * Returns false (does nothing else) if instanceId doesn't exist, isn't
     * animatable by either mechanism at all (!isAnimatable(idx)), or is a
     * LOCKED lever arm (unlock it first - setArmLocked) - setting "open"
     * on something with no animation to play, or that is bolted in place,
     * wouldn't mean anything. Works for a lever-arm instance exactly like a
     * keyframed one - the arm is handed a scripted target and
     * updateAnimation eases it there afterward at the arm's own `speed`,
     * playing its openSound/closeSound as it leaves/reaches the closed end. */
    public boolean setOpen(String instanceId, boolean open) {
        int idx = indexOfInstance(instanceId);
        if (idx < 0 || !isAnimatable(idx)) return false;

        boolean accepted = openOne(idx, open);
        String group = scene.instances.get(idx).params.get("linkGroup");
        if (group != null) {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (i == idx || !isAnimatable(i)) continue;
                if (group.equals(scene.instances.get(i).params.get("linkGroup"))) openOne(i, open);
            }
        }
        return accepted;
    }

    /** One instance's share of setOpen: a keyframed instance toggles and
     * plays the sound of the keyframe it is leaving; a lever arm is given
     * a scripted target (false if it is locked). Keyframes win for an
     * instance that somehow has both, same as in HierarchyResolver. */
    private boolean openOne(int i, boolean open) {
        if (!Double.isNaN(instanceAnimTime[i])) {
            instanceOpen[i] = open;
            playSoundAtCurrentTime(i);
            return true;
        }
        return arms.setTarget(i, open);
    }

    /** Directly sets instanceId's own lever-arm value to a specific point
     * in [0,1] (clamped), bypassing updateAnimation's speed-based easing
     * entirely from this call onward - the hook for anything that needs
     * to drive a lever-arm CONTINUOUSLY from outside this class, frame by
     * frame, rather than toggling it once and letting `speed` ease it to
     * an end on its own: the mouse drag (dragArmValueTowardRay calls it
     * every frame), or later a physics step. Once called, the value
     * belongs to the caller and easing leaves it alone, until setOpen or
     * releaseArm hands it back.
     *
     * Same linkGroup propagation as setOpen (every lever arm sharing this
     * one's own linkGroup is set to the same value; a locked one stays
     * put) - a dragged door with a synchronized twin drags both. A
     * transition across the closed end still plays the arm's openSound /
     * closeSound (updateAnimation notices it whoever moved the arm).
     *
     * Returns false (does nothing else) if instanceId doesn't exist, isn't
     * a lever-arm instance (unlike setOpen this is NOT valid for a
     * keyframed instance, which has no separate arm-value dimension) or is
     * locked. */
    public boolean setArmValue(String instanceId, double value) {
        int idx = indexOfInstance(instanceId);
        return idx >= 0 && arms.setValue(idx, value);
    }

    /** Reads instanceId's own current lever-arm value - whatever it is
     * right now, however it got there (scripted easing, a drag or a
     * setArmValue caller). This is the roadmap's long-flagged
     * "getArmRotationValue" accessor, named ArmValue instead: the value
     * isn't necessarily a pure rotation (openRot can be null, leaving only
     * a position change - see PbdInstance.LeverArm), so "rotation" would
     * be inaccurate for a sliding or combined arm. Returns Double.NaN if
     * instanceId doesn't exist or isn't a lever-arm instance - callers that
     * only care about open/closed as a simple yes/no should use
     * isInstanceOpen/isInstanceFullyOpen/isInstanceFullyClosed instead;
     * this is for a caller that genuinely needs the in-between, e.g. an AI
     * deciding whether it can see/reach through a door that's only partly
     * open. */
    public double getArmValue(String instanceId) {
        int idx = indexOfInstance(instanceId);
        return idx < 0 ? Double.NaN : arms.value(idx); // NaN already for an instance with no arm
    }

    /** The player let go of a dragged arm: a `release=snap` arm (the
     * default) swings on to whichever end it is nearer, a `release=free`
     * one stays exactly where it was let go. Linked twins are released
     * with it. Returns whether the arm went back to scripted easing. */
    public boolean releaseArm(String instanceId) {
        int idx = indexOfInstance(instanceId);
        return idx >= 0 && arms.release(idx);
    }

    /** Whether instanceId is a lever arm that is locked in place (its file
     * said locked=true, or setArmLocked locked it): no drag, no setOpen,
     * no easing moves it. False for anything else, including an unknown id. */
    public boolean isArmLocked(String instanceId) {
        int idx = indexOfInstance(instanceId);
        return idx >= 0 && arms.isLocked(idx);
    }

    /** Locks instanceId's arm where it stands, or unlocks it - an unlocked
     * `snap` arm then settles at its nearer end, a `free` one stays put.
     * The hook for a key, a script or a mod ("the door is locked until the
     * player has the key"). False if instanceId isn't a lever arm. */
    public boolean setArmLocked(String instanceId, boolean locked) {
        int idx = indexOfInstance(instanceId);
        return idx >= 0 && arms.setLocked(idx, locked);
    }

    private int indexOfInstance(String instanceId) {
        for (int i = 0; i < scene.instances.size(); i++) {
            if (scene.instances.get(i).id.equals(instanceId)) return i;
        }
        return -1;
    }

    private boolean isHiddenInstance(int index) {
        return hiddenInstances != null && index >= 0 && index < hiddenInstances.length && hiddenInstances[index];
    }

    /** Instance i's world matrix as it really is right now: the one the
     * renderer holds, or - for a hidden (voxel-destroyed) instance, whose
     * own entry is zeroed - its live transform. */
    private Matrix4f liveWorldOf(int i) {
        return isHiddenInstance(i) ? resolveLiveTransforms()[i] : worldTransforms[i];
    }

    /** Plays instance i's sound field for whichever keyframe it's
     * CURRENTLY sitting at, right at the moment a click toggles it into
     * motion - the fix for a sound on the first or last keyframe never
     * triggering via checkSoundTrigger's crossing check: animTime is
     * always clamped to [firstTime, lastTime], so it can never be
     * "crossed INTO" from outside that range, only reached exactly -
     * meaning a creak meant to play "as the door leaves its resting
     * pose" (the natural place for one, on either end) could never fire
     * under crossing detection alone, in EITHER direction. Called once,
     * synchronously, at the toggle itself - not from updateAnimation's
     * per-frame loop - so it fires exactly once per click regardless of
     * frame rate, rather than depending on animTime happening to land in
     * some range during a later frame. checkSoundTrigger is untouched
     * and still separately handles a sound on any MIDDLE keyframe being
     * crossed during playback, which this doesn't overlap with. */
    private void playSoundAtCurrentTime(int instanceIndex) {
        if (soundPlayer == null) return;
        double time = instanceAnimTime[instanceIndex];
        if (Double.isNaN(time)) return;
        for (PbdInstance.Keyframe kf : scene.instances.get(instanceIndex).keyframes) {
            if (kf.sound != null && Math.abs(kf.time - time) < 0.01) {
                soundPlayer.play(kf.sound);
            }
        }
    }

    private void uploadWorldTransforms(Matrix4f[] worldTransforms) {
        ByteBuffer buf = nativeBuffer(worldTransforms.length * 64);
        for (Matrix4f m : worldTransforms) {
            m.get(buf);              // writes 16 floats in column-major order, does not advance position
            buf.position(buf.position() + 64);
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, worldTransformBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_WORLD_TRANSFORMS, worldTransformBuffer);
        MemoryUtil.memFree(buf);
    }

    private void uploadModifiers(PbdScene scene, ModifierRegistry modifierRegistry) {
        int total = 0;
        for (PbdInstance inst : scene.instances) total += inst.modifiers.size();

        final int stride = 32;
        ByteBuffer buf = nativeBuffer(Math.max(total, 1) * stride);
        for (PbdInstance inst : scene.instances) {
            for (PbdModifier mod : inst.modifiers) {
                buf.putInt(modifierRegistry.idOf(mod.type));
                buf.putInt(0); // curveRef: no curve table on the GPU side yet, see README
                buf.position(buf.position() + 8); // std430 padding before the vec4 (see file header)
                if ("taper".equals(mod.type)) {
                    // bottomScale (-Y end) in .x, topScale (+Y end) in .y -
                    // both default to 1.0 (no taper) if unset, matching
                    // pbd.tese's applyTaper.
                    buf.putFloat(mod.getFloat("bottomScale", 1f));
                    buf.putFloat(mod.getFloat("topScale", 1f));
                    buf.putFloat(0).putFloat(0);
                } else if ("bend".equals(mod.type)) {
                    // axis chooses BOTH the rotation plane and (cyclically)
                    // which coordinate is the arc-length parameter - see
                    // pbd.tese's applyBend (a real constant-radius arc, not
                    // to be confused with `shear` below).
                    buf.putFloat(mod.getFloat("angle", 0f));
                    buf.putFloat(axisIndex(mod.get("axis")));
                    buf.putFloat(0).putFloat(0);
                } else if ("shear".equals(mod.type)) {
                    // Three independent factors applied together (not a
                    // choice of one axis) - factorX/Y/Z default to 0 (no
                    // effect from that case) if unset. See pbd.tese's
                    // applyShear for why this was renamed from `bend`: it
                    // rotates a plane by an angle proportional to the third
                    // coordinate, which - unlike a real bend - scales the
                    // cross-section rather than preserving it.
                    buf.putFloat(mod.getFloat("factorX", 0f));
                    buf.putFloat(mod.getFloat("factorY", 0f));
                    buf.putFloat(mod.getFloat("factorZ", 0f));
                    buf.putFloat(0);
                } else {
                    buf.putFloat(mod.getFloat("angle", 0f));
                    buf.putFloat(0).putFloat(0).putFloat(0);
                }
            }
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, modifierBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MODIFIERS, modifierBuffer);
        MemoryUtil.memFree(buf);
    }

    private void uploadMaterials(MaterialRegistry materialRegistry) {
        if (textureArray != null) textureArray.close();
        if (normalTextureArray != null) normalTextureArray.close();
        if (roughnessTextureArray != null) roughnessTextureArray.close();
        if (displacementTextureArray != null) displacementTextureArray.close();

        java.util.List<Path> texturePaths = new java.util.ArrayList<>();
        java.util.List<Path> normalPaths = new java.util.ArrayList<>();
        java.util.List<Path> roughnessPaths = new java.util.ArrayList<>();
        java.util.List<Path> displacementPaths = new java.util.ArrayList<>();
        for (Map<String, String> fields : scene.materialOverrides.values()) {
            if (fields.get("texture") != null) texturePaths.add(Path.of(fields.get("texture")));
            if (fields.get("normalMap") != null) normalPaths.add(Path.of(fields.get("normalMap")));
            if (fields.get("roughnessMap") != null) roughnessPaths.add(Path.of(fields.get("roughnessMap")));
            if (fields.get("displacementMap") != null) displacementPaths.add(Path.of(fields.get("displacementMap")));
        }
        System.out.println("[Materials] Diffuse textures requested: " + texturePaths);
        System.out.println("[Materials] Normal maps requested: " + normalPaths);
        System.out.println("[Materials] Roughness maps requested: " + roughnessPaths);
        textureArray = MaterialTextureArray.build(texturePaths);
        normalTextureArray = MaterialTextureArray.build(normalPaths);
        roughnessTextureArray = MaterialTextureArray.build(roughnessPaths);
        displacementTextureArray = MaterialTextureArray.build(displacementPaths);
        // Reads the SAME pixel data textureArray.build() above already
        // decoded for its own GL upload - MaterialTextureArray.PixelData,
        // copied there from that call's own single stbi_load per file -
        // rather than calling STBImage separately here a second time.
        // The very first version of this cache DID call stbi_load again,
        // right here, on these same paths - and a real crash log placed
        // the failure immediately after THIS array's own resize step for
        // a large (3000x2000) JPEG, strongly pointing at the double
        // decode itself (two native allocate/decode/free cycles on the
        // same large image back to back) as the actual problem, not
        // WHEN or WHERE stbi_load was being called from. This also
        // fixes a second, separate, real concern raised directly: a
        // cache built by scanning scene.materialOverrides only ONCE,
        // here, would silently miss any texture loaded later through
        // this engine's own intended hot-loading - reading from
        // textureArray's own already-built data instead means this
        // cache can never drift out of sync with whatever textureArray
        // itself was actually built from, including a future re-
        // upload() triggered by a hot-loaded asset, since it's the same
        // call, not a second independent one running on its own
        // schedule.
        voxelTexturePixels.clear();
        for (Path texturePath : texturePaths) {
            MaterialTextureArray.PixelData pd = textureArray.pixelDataOf(texturePath);
            if (pd != null) {
                voxelTexturePixels.put(texturePath.toString(), new VoxelTexturePixels(pd.rgba(), pd.width(), pd.height()));
            }
        }
        textureArrayGlId = textureArray.glId;
        normalTextureArrayGlId = normalTextureArray.glId;
        roughnessTextureArrayGlId = roughnessTextureArray.glId;
        displacementTextureArrayGlId = displacementTextureArray.glId;

        int count = Math.max(materialRegistry.count(), 1);
        // 64, not 60: a struct containing a vec4 has 16-byte base
        // alignment in std430, and the array stride must be a multiple
        // of that - the struct's natural size (60 bytes = 15 floats,
        // now that uvScale split into uvScaleU/uvScaleV) isn't a
        // multiple of 16, so GLSL pads every element up to 64. Writing
        // exactly the natural size with no padding put every material
        // past index 0 at the wrong offset - confirmed against the
        // official GLSL std430 layout rules (a struct's base alignment
        // is its largest member's alignment, vec4 = 16 here, and array
        // stride is that size rounded up to that alignment).
        final int stride = 64;
        ByteBuffer buf = nativeBuffer(count * stride);
        for (int id = 0; id < count; id++) {
            String name = materialRegistry.nameOf(id);
            MaterialCatalog.Entry entry = resolveMaterialEntry(name);
            float textureLayer = -1f, uvScaleU = 1f, uvScaleV = 1f, normalLayer = -1f, roughnessLayer = -1f, reflectivity = 0f, transparency = 0f;
            float displacementLayer = -1f, displacementScale = 0f;
            Map<String, String> override = name != null ? scene.materialOverrides.get(name) : null;
            if (override != null) {
                if (override.containsKey("texture")) {
                    int layer = textureArray.layerOf(Path.of(override.get("texture")));
                    // -2, not -1: a texture WAS requested here, so a
                    // missing file needs to look different in the
                    // shader from "no texture was ever specified" - see
                    // pbd.frag's magenta fallback, which only fires for
                    // -2. Without this distinction, a texture that
                    // silently failed to load (wrong path, moved file)
                    // renders identically to a plain-color material, and
                    // any fix to how a texture blends with baseColor
                    // - like yesterday's multiply-vs-replace change -
                    // would look like it did nothing at all, because the
                    // texture was never the thing being shown either way.
                    textureLayer = (layer >= 0) ? layer : -2f;
                    // uvScale= alone (no U/V suffix) still works and sets
                    // BOTH axes - a hand-edited or older file with the
                    // old scalar field isn't broken by this change.
                    // uvScaleU=/uvScaleV= override it independently when
                    // present, for exactly the "two axes" need this
                    // split exists for.
                    if (override.containsKey("uvScale")) {
                        uvScaleU = uvScaleV = Float.parseFloat(override.get("uvScale"));
                    }
                    if (override.containsKey("uvScaleU")) uvScaleU = Float.parseFloat(override.get("uvScaleU"));
                    if (override.containsKey("uvScaleV")) uvScaleV = Float.parseFloat(override.get("uvScaleV"));
                }
                if (override.containsKey("normalMap")) {
                    int layer = normalTextureArray.layerOf(Path.of(override.get("normalMap")));
                    normalLayer = (layer >= 0) ? layer : -2f;
                }
                if (override.containsKey("roughnessMap")) {
                    int layer = roughnessTextureArray.layerOf(Path.of(override.get("roughnessMap")));
                    roughnessLayer = (layer >= 0) ? layer : -2f;
                }
                if (override.containsKey("reflectivity")) reflectivity = Float.parseFloat(override.get("reflectivity"));
                if (override.containsKey("transparency")) transparency = Float.parseFloat(override.get("transparency"));
                if (override.containsKey("displacementMap")) {
                    displacementLayer = displacementTextureArray.layerOf(Path.of(override.get("displacementMap")));
                    // Default scale intentionally small (a fraction of the
                    // canonical unit): with no explicit displacementScale,
                    // this still visibly reads as "surface has texture"
                    // rather than "surface exploded", since a height map
                    // is usually meant to add wood-grain/brick-mortar
                    // scale detail, not restructure the whole shape.
                    displacementScale = override.containsKey("displacementScale")
                        ? Float.parseFloat(override.get("displacementScale")) : 0.02f;
                }
            }
            System.out.printf("[Materials] id=%d name=%s texLayer=%.0f uvScaleU=%.2f uvScaleV=%.2f normalLayer=%.0f roughLayer=%.0f dispLayer=%.0f reflect=%.2f transp=%.2f%n",
                id, name, textureLayer, uvScaleU, uvScaleV, normalLayer, roughnessLayer, displacementLayer, reflectivity, transparency);
            buf.putFloat(entry.r).putFloat(entry.g).putFloat(entry.b).putFloat(1f); // baseColor (vec4, alpha unused)
            buf.putFloat(entry.shininess);
            buf.putFloat(entry.specularStrength);
            buf.putFloat(textureLayer);
            buf.putFloat(uvScaleU);
            buf.putFloat(uvScaleV);
            buf.putFloat(normalLayer);
            buf.putFloat(roughnessLayer);
            buf.putFloat(reflectivity);
            buf.putFloat(transparency);
            buf.putFloat(displacementLayer);
            buf.putFloat(displacementScale);
            buf.putFloat(0f); // std430 padding - see the stride comment above (was 2 floats before uvScaleV took one of them)
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MATERIALS, materialBuffer);
        MemoryUtil.memFree(buf);
    }

    /** How many "light"-type instances the last uploadLights() call
     * actually found - 0 is a completely normal, common case (most
     * scenes have none), not an error; the shader's own light-
     * accumulation loop (see pbd.frag) just contributes nothing extra
     * beyond the existing sun/ambient terms when this is 0, same
     * result as before lights existed at all. */
    public int lightCount = 0;

    /** Public (unlike every OTHER upload* method here, all private and
     * only ever called as part of the full upload() sequence at scene
     * load) specifically so a caller can refresh JUST the lights after
     * changing one's state at runtime - PbdEngine.SceneHandle's own
     * setLightEnabled/toggleLight change the PbdScene's in-memory data,
     * not anything already sitting in this renderer's GPU buffers;
     * re-running the ENTIRE upload() for a light switch would needlessly
     * re-upload every instance/patch/material too. Main.java's own game
     * loop calls this (not the full upload()) after any such change. */
    public void uploadLights(PbdScene scene) {
        List<Integer> lightIndices = new ArrayList<>();
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance candidate = scene.instances.get(i);
            if ("light".equals(candidate.type) && candidate.lightEnabled) lightIndices.add(i);
        }
        lightCount = lightIndices.size();
        // count is never 0 for the SSBO itself even with no lights in
        // the scene - same "at least 1" convention uploadMaterials uses
        // for its own buffer, avoiding a zero-length buffer (which some
        // drivers are known to mishandle) for a case that's completely
        // normal, not exceptional.
        int count = Math.max(lightCount, 1);
        // 64-byte stride: 3 (vec3 + float) pairs = 48 bytes, + 1 int
        // (isSpot) = 52 bytes, rounded up to the next multiple of 16
        // (std430's own array-stride rule for a struct whose largest
        // member - any of the vec3s - has 16-byte alignment) = 64.
        // Same reasoning as the material struct's own stride comment,
        // re-derived for this different field layout rather than
        // assumed to be the same number by coincidence.
        final int stride = 64;
        ByteBuffer buf = nativeBuffer(count * stride);
        for (int idx : lightIndices) {
            pbd.format.PbdInstance inst = scene.instances.get(idx);
            Vector3f worldPos = worldTransforms[idx].getTranslation(new Vector3f());
            float range = inst.lightRange != null ? inst.lightRange : 6f;
            Vector3f color = inst.lightColor != null ? inst.lightColor : new Vector3f(1f, 1f, 1f);
            float intensity = inst.lightIntensity != null ? inst.lightIntensity : 1f;
            boolean isSpot = "spot".equals(inst.lightMode);
            // -Z in this instance's OWN local frame, rotated into world
            // space - matches Blender's own spot-lamp convention (see
            // properties.py's own pbd_light_mode description), so
            // aiming a spot in the addon by rotating the Empty aims it
            // the same way here.
            Vector3f spotDir = LightTracker.aim(worldTransforms[idx]); // the one definition of "aim", shared with the tracker that decides when to rebuild this buffer
            float spotCosAngle = isSpot && inst.lightSpotAngleDeg != null
                ? (float) Math.cos(Math.toRadians(inst.lightSpotAngleDeg)) : -1f; // -1 = cos(180deg) = every direction passes, i.e. no cone restriction at all for a non-spot light

            buf.putFloat(worldPos.x).putFloat(worldPos.y).putFloat(worldPos.z).putFloat(range);
            buf.putFloat(color.x).putFloat(color.y).putFloat(color.z).putFloat(intensity);
            buf.putFloat(spotDir.x).putFloat(spotDir.y).putFloat(spotDir.z).putFloat(spotCosAngle);
            buf.putInt(isSpot ? 1 : 0);
            buf.putFloat(0f).putFloat(0f).putFloat(0f); // std430 padding to the 64-byte stride
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, lightBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_LIGHTS, lightBuffer);
        MemoryUtil.memFree(buf);
        // From here on, "moved" means moved since THIS build (see updateAnimation).
        lightTracker.remember(scene, worldTransforms);
    }

    /**
     * A material's data comes from, in order: an include_material'd
     * .pbdmat entry for this exact name if one was loaded for this scene
     * (scene.materialOverrides - see PbdParser.parseIncludeMaterial), else
     * the hard-coded MaterialCatalog, else a neutral default - unknown
     * names never fail to resolve, they just render plain grey.
     *
     * `color`/`shininess`/`specular` are read here (kept as raw strings in
     * pbd.format so it doesn't depend on pbd.render - see PbdScene).
     * `texture`/`uvScale` are handled directly in uploadMaterials, which
     * needs the material's registry id to look up its texture array layer
     * - not worth threading that back through this helper too.
     */
    private MaterialCatalog.Entry resolveMaterialEntry(String name) {
        if (name == null) return MaterialCatalog.DEFAULT;
        Map<String, String> override = scene.materialOverrides.get(name);
        if (override == null) {
            return MaterialCatalog.lookup(name);
        }
        MaterialCatalog.Entry base = MaterialCatalog.lookup(name); // sensible fallback for any field the file didn't set
        float[] rgb = override.containsKey("color") ? parseVec3(override.get("color")) : new float[]{base.r, base.g, base.b};
        float shininess = override.containsKey("shininess") ? Float.parseFloat(override.get("shininess")) : base.shininess;
        float specular = override.containsKey("specular") ? Float.parseFloat(override.get("specular")) : base.specularStrength;
        return new MaterialCatalog.Entry(rgb[0], rgb[1], rgb[2], shininess, specular);
    }

    private static float[] parseVec3(String raw) {
        String inner = raw.trim();
        if (inner.startsWith("(")) inner = inner.substring(1, inner.length() - 1);
        String[] parts = inner.split(",");
        return new float[]{
            Float.parseFloat(parts[0].trim()),
            Float.parseFloat(parts[1].trim()),
            Float.parseFloat(parts[2].trim())};
    }

    // Flat floor for a non-curved parametric direction - mirrors
    // FLAT_LEVEL in pbd.tesc exactly. Must stay in sync with that constant.
    private static final float FLAT_LEVEL = 1f;

    /** "x"/"y"/"z" (case-insensitive) -> 0/1/2, matching pbd.tese's applyBend cyclic convention. Defaults to 0 (x) for null/unrecognized input. */
    static float axisIndex(String axis) {
        if (axis == null) return 0f;
        return switch (axis.trim().toLowerCase()) {
            case "y" -> 1f;
            case "z" -> 2f;
            default -> 0f;
        };
    }

    /**
     * Sums the per-patch triangle count across every patch, using the same
     * distance -> level formula as pbd.tesc, and the same round/flat axis
     * assignment as pbd.tesc's roundedAxes() - see the field doc on
     * lastTriangleCount for how each branch below was verified. Used for
     * the live path's estimate; the cached path sums real baked vertex
     * counts instead (see renderCached()).
     */
    int computeLiveTriangleCount(float camX, float camY, float camZ) {
        float range = Math.max(lodMaxDistance - lodMinDistance, 0.0001f);
        long total = 0;
        for (GpuPatch patch : patches) {
            Matrix4f world = worldTransforms[patch.instanceIndex];
            Vector3f pos = world.getTranslation(new Vector3f());
            float dx = pos.x - camX, dy = pos.y - camY, dz = pos.z - camZ;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float t = Math.max(0f, Math.min(1f, (dist - lodMinDistance) / range));
            float level = lodMaxLevel * (1f - t) + lodMinLevel * t; // GLSL mix(lodMaxLevel, lodMinLevel, t)

            PbdInstance inst = scene.instances.get(patch.instanceIndex);
            total += trianglesForPatch(inst, level);
        }
        return (int) total;
    }

    private static long trianglesForPatch(PbdInstance inst, float level) {
        String type = inst.type;
        if ("sphere".equals(type)) {
            return Math.round(2.0 * level * level);
        }
        if ("cylinder".equals(type) || "cone".equals(type) || "disc".equals(type)) {
            return Math.round(4.0 * level - 2.0);
        }
        if ("cube".equals(type)) {
            float base = Float.parseFloat(inst.params.getOrDefault("smooth", "0"));
            float sx = Float.parseFloat(inst.params.getOrDefault("smoothX", String.valueOf(base)));
            float sy = Float.parseFloat(inst.params.getOrDefault("smoothY", String.valueOf(base)));
            float sz = Float.parseFloat(inst.params.getOrDefault("smoothZ", String.valueOf(base)));
            if (sx > 0.001f || sy > 0.001f || sz > 0.001f) {
                return Math.round(2.0 * level * level);
            }
        }
        return Math.round(2.0 * FLAT_LEVEL * FLAT_LEVEL);
    }

    /** Zeroes instanceIndex's world transform (scale to nothing, in
     * effect) and re-uploads - the safe way to make one instance stop
     * being drawn by the main tessellation batch without restructuring
     * how that batch itself works (every instance renders together, in
     * one pass - there's no per-instance "skip" the batch itself
     * exposes). Used when a primitive gets voxel-destroyed (Main.java):
     * the original stops rendering via this, and its voxelized
     * replacement renders separately, through the classic-mesh path
     * (see ClassicMeshRenderer) instead. Irreversible from here - there
     * is no un-hide, matching a destroyed primitive not coming back
     * either. */
    public void hideInstance(int instanceIndex) {
        if (hiddenInstances == null) hiddenInstances = new boolean[scene.instances.size()];
        hiddenInstances[instanceIndex] = true;
        worldTransforms[instanceIndex] = new Matrix4f().scale(0f);
        uploadWorldTransforms(worldTransforms);
    }

    // ===================================================================
    // Voxel destruction (G-key) facade - moved here from Main.java's own
    // game loop, where this whole system (raycast, retarget, crater
    // computation, per-voxel color, GPU renderer construction, and the
    // state tracking + draw loop for the resulting debris) used to live
    // inline. Explicitly, repeatedly requested: this logic has no
    // business sitting in the game loop, it belongs BEHIND this facade,
    // the same place setOpen/hideInstance/isContainerOpen and everything
    // else destruction-adjacent already lives. Main.java's own G-key
    // handler is now just: call destroyAt with the camera's ray, log the
    // result's own message, play the SFX if result.hit(). Every voxel-
    // destruction helper this project built up over several turns
    // (applyCraterAdaptive's true anisotropic crater application,
    // computeCraterRemovedSet's ray-cast crater, buildVoxelColorFn's
    // texture-pixel sampling from voxelTexturePixels) moved here as
    // private methods rather than being reimplemented - same logic,
    // same fixes, new home.
    // ===================================================================

    /** hit: whether anything was actually destroyed/further-carved this
     * call - the caller's own cue for whether to play the destruction
     * SFX, without needing to know which of the several possible
     * internal cases (first hit, follow-up hit, a falling piece hit
     * again, fully consumed, nothing under the crosshair) it was.
     * message: a ready-to-print, already-detailed log line - the same
     * information this project's own console output already carried when
     * this logic lived in Main.java, just returned instead of printed
     * directly, so the caller decides whether/how to surface it. */
    public record DestructionResult(boolean hit, String message) {}

    /** Everything a G hit does that does not need a GPU - the crater, what
     * stays attached, what breaks off and falls, the physics of the falling
     * pieces - lives in pbd.voxel.DestructionWorld so it can be tested
     * without a window (tools/regression/RegressionDestructionWorld). This
     * class is its GL adapter: VoxelHost below builds/frees the meshes and
     * says where instances are right now. Created lazily (it needs the
     * uploaded scene), dropped by clearDestroyed(). */
    private DestructionWorld<pbd.classicmesh.ClassicMeshRenderer> destruction;

    private DestructionWorld<pbd.classicmesh.ClassicMeshRenderer> destruction() {
        if (destruction == null) destruction = new DestructionWorld<>(scene.instances, new VoxelHost());
        return destruction;
    }

    private final class VoxelHost implements DestructionWorld.Host<pbd.classicmesh.ClassicMeshRenderer> {
        @Override public Matrix4f[] liveTransforms() { return resolveLiveTransforms(); }

        @Override public boolean isHidden(int index) {
            return hiddenInstances != null && index < hiddenInstances.length && hiddenInstances[index];
        }

        @Override public void hideInstance(int index) { PbdRenderer.this.hideInstance(index); }

        @Override public DestructionWorld.Style styleFor(PbdInstance original, PbdInstance voxelized, int gridSize, float voxelWorldSize) {
            var matFields = scene.materialOverrides.get(original.material);
            float[] flatColor = {0.62f, 0.63f, 0.66f};
            if (matFields != null && matFields.get("color") != null) {
                float[] parsed = parseColorTriple(matFields.get("color"));
                if (parsed != null) flatColor = parsed;
            }
            return new DestructionWorld.Style(buildVoxelColorFn(voxelized, matFields, flatColor, gridSize, voxelWorldSize), flatColor);
        }

        @Override public pbd.classicmesh.ClassicMeshRenderer buildMesh(List<int[]> entries, float voxelWorldSize, DestructionWorld.Style style) {
            var colored = pbd.voxel.VoxelMeshBuilder.buildMeshWithColor(entries, voxelWorldSize, style.colorFn);
            return buildVoxelMeshRenderer(colored.meshData, colored.colors, style.fallback);
        }

        @Override public void closeMesh(pbd.classicmesh.ClassicMeshRenderer mesh) { mesh.close(); }
    }

    /** The full G-key action: find what the ray hits first - a
     * never-destroyed instance (the bounding-box test below, refined
     * against its real voxels inside DestructionWorld), what is left of an
     * already-destroyed one, or a piece that already broke off - carve a
     * crater, and let whatever the crater leaves unattached fall (see
     * updateDebris / drawDestroyed). */
    public DestructionResult destroyAt(Vector3f rayOrigin, Vector3f rayDir) {
        DestructionWorld.Fresh candidate = findFresh(rayOrigin, rayDir);
        if (candidate != null) lastHitLocalPoint.set(candidate.localHit());
        DestructionWorld.Outcome outcome = destruction().destroyAt(
            new Vector3d(rayOrigin.x, rayOrigin.y, rayOrigin.z),
            new Vector3d(rayDir.x, rayDir.y, rayDir.z), candidate);
        return new DestructionResult(outcome.hit(), outcome.message());
    }

    /** Advances the pieces a G hit broke off (gravity, floor, landing on
     * remains and nearby boxes). Call once per frame, after
     * updateAnimation; free until something has broken off. */
    public void updateDebris(double dt) {
        if (destruction != null) destruction.update(dt);
    }

    /** Minimal GPU-renderer construction for a voxel-destruction result
     * specifically - NOT Main.java's own general-purpose
     * buildMeshRenderer (which also handles regular mesh instances and
     * container items, texture/uvScale setup included): every voxel
     * mesh carries real per-vertex colors (VoxelMeshBuilder's own
     * ColoredMesh) and renders through ClassicMeshRenderer's own
     * useVertexColor path, which the shader itself prioritizes over
     * baseColor/texture entirely - so this needs none of that other
     * setup, only the mesh itself plus a baseColor for consistency/
     * fallback. Kept deliberately separate from buildMeshRenderer rather
     * than sharing it across the Main/PbdRenderer boundary - that
     * method's other 3 call sites (regular instance upload, LOD
     * rebuild) are outside what was actually asked to move here, and
     * pulling them in too would have expanded this refactor well past
     * "the G-key logic" into unrelated code paths. */
    private pbd.classicmesh.ClassicMeshRenderer buildVoxelMeshRenderer(pbd.format.PbdMeshData meshData, float[] colors, float[] baseColor) {
        try {
            pbd.classicmesh.ObjMesh objMesh = new pbd.classicmesh.ObjMesh(meshData.toPositionNormalUvInterleaved(), meshData.indices, true, colors);
            pbd.classicmesh.ClassicMeshRenderer meshRenderer = new pbd.classicmesh.ClassicMeshRenderer(Path.of("src/main/resources/shaders/classic"), objMesh);
            meshRenderer.baseColor = baseColor;
            return meshRenderer;
        } catch (java.io.IOException e) {
            System.err.println("[Destroy] Failed to create voxel-debris renderer: " + e.getMessage());
            return null;
        }
    }

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

    /** Reads voxelTexturePixels (this class's own field, populated in
     * upload() from MaterialTextureArray's own already-decoded pixel
     * data - see that field's own doc for why this never calls
     * STBImage itself, a real, repeatedly-crash-causing approach this
     * project tried and moved away from). Per-object-extent box/
     * triplanar mapping: normalizes a position against THIS object's
     * own real per-axis extent (gvx/gvy/gvz, recomputed the same way
     * PrimitiveVoxelizer itself derives them) rather than the shared
     * octree grid's own span, which for a non-cubic object would
     * compress almost the whole texture into a tiny sliver - verified
     * directly against a thin door panel spanning the full 0..1 range
     * on both axes.
     *
     * BiFunction, called once per VERTEX (worldPos - VoxelMeshBuilder's
     * own addFace calls this per corner of every face, not once per
     * region) rather than the single Function<int[],float[]> (once per
     * REGION) this used to be - a real, confirmed, directly-reported
     * bug: a merged region can be dozens of voxels across (most of a
     * real object's own surface stays merged this large - see
     * PrimitiveVoxelizer's own doc on why untouched interior regions
     * aren't expanded), and sampling ONE color at that region's own
     * corner, then flat-shading its whole box with it, discarded
     * essentially all of the real texture's own detail across
     * anything but the smallest, crater-adjacent pieces - visible
     * directly as blocky, single-color patches/streaks where real
     * texture variation should have been. worldPos is the SAME world-
     * space coordinate system addBox already builds its box corners
     * in (voxel-grid units times voxelWorldSize) - dividing back by
     * voxelWorldSize here recovers grid-space, floating-point this
     * time rather than one region's own integer corner, so each of a
     * large region's 4 corners per face now samples a DIFFERENTLY
     * positioned texel, and the GPU's own rasterizer interpolates
     * smoothly between them across the face - not per-fragment GPU
     * texture sampling (a real further improvement, not attempted
     * here - see this method's own roadmap entry), but a genuine
     * gradient instead of one flat color no matter how large the
     * block. */
    private java.util.function.BiFunction<int[], float[], float[]> buildVoxelColorFn(
            PbdInstance inst, Map<String, String> matFields, float[] fallbackColor,
            int gridSize, float voxelWorldSize) {
        String texturePath = matFields != null ? matFields.get("texture") : null;
        if (texturePath == null) return (voxel, worldPos) -> fallbackColor;

        VoxelTexturePixels tex = voxelTexturePixelsFor(texturePath);
        if (tex == null) {
            System.out.println("[Destroy] No pre-decoded pixel data for texture '" + texturePath + "' - using flat color instead");
            return (voxel, worldPos) -> fallbackColor;
        }

        int texW = tex.width(), texH = tex.height();
        byte[] pixelBytes = tex.pixelBytes();
        // taperMaxScale mirrors PrimitiveVoxelizer.voxelize()'s own
        // identically-named local exactly (same loop, same formula) -
        // gvx/gvz here have to agree with the REAL gridX/gridZ that
        // method actually built the octree at, or every voxel's own
        // nx/nz below is normalized against the wrong extent (visibly
        // wrong for a widening taper specifically, since the grid
        // there is now genuinely wider than a plain inst.scale.x/z
        // recomputation accounts for - narrowing and identity tapers
        // are unaffected, matching PrimitiveVoxelizer's own doc on why
        // max(...,1f) never shrinks anything). Duplicated rather than
        // read off a stored field because PrimitiveVoxelizer.Result
        // does not currently expose its own gridX/gridY/gridZ, only
        // the already-centered gridOriginLocal - re-deriving here is
        // small and cheap, unlike carrying a wider result type through
        // every caller.
        float taperMaxScale = 1f;
        for (pbd.format.PbdModifier mod : inst.modifiers) {
            if ("taper".equals(mod.type)) {
                float bottomScale = mod.getFloat("bottomScale", 1f);
                float topScale = mod.getFloat("topScale", 1f);
                taperMaxScale = Math.max(taperMaxScale, Math.max(Math.abs(bottomScale), Math.abs(topScale)));
            }
        }
        int gvx = Math.max(1, Math.round(inst.scale.x * taperMaxScale / voxelWorldSize));
        int gvy = Math.max(1, Math.round(inst.scale.y / voxelWorldSize));
        int gvz = Math.max(1, Math.round(inst.scale.z * taperMaxScale / voxelWorldSize));
        int foX = (gridSize - gvx) / 2, foY = (gridSize - gvy) / 2, foZ = (gridSize - gvz) / 2;
        return (voxel, worldPos) -> {
            float nx = (worldPos[0] / voxelWorldSize - foX) / (float) gvx;
            float ny = (worldPos[1] / voxelWorldSize - foY) / (float) gvy;
            float nz = (worldPos[2] / voxelWorldSize - foZ) / (float) gvz;
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

    /** Draws every destroyed instance's own current voxel-debris mesh and
     * every detached piece - called once per frame from Main.java's own
     * render pass, the same place every other kind of geometry already
     * gets drawn from. resolveLiveTransforms() is called ONCE per call
     * (not once per destroyed entry - see that method's own doc on why it
     * isn't cheap), and only when there are remains to place; a falling
     * piece carries its own world transform. */
    public void drawDestroyed(FlyCamera camera, float aspectRatio) {
        if (destruction == null || destruction.isEmpty()) return;
        var remains = destruction.remains();
        if (!remains.isEmpty()) {
            Matrix4f[] liveTransforms = resolveLiveTransforms();
            for (var r : remains) {
                Matrix4f liveWorld = liveTransforms[r.instanceIndex];
                Matrix4f world = new Matrix4f()
                    .translate(liveWorld.getTranslation(new Vector3f()))
                    .rotate(extractRotationRobust(liveWorld))
                    .translate(r.gridOrigin);
                r.mesh().render(camera, aspectRatio, world);
            }
        }
        for (var chunk : destruction.chunks()) {
            var b = chunk.body;
            // mesh space -> body space (centre of mass at the origin) -> world
            Matrix4f world = new Matrix4f()
                .translate((float) b.position.x, (float) b.position.y, (float) b.position.z)
                .rotate(new Quaternionf((float) b.orientation.x, (float) b.orientation.y, (float) b.orientation.z, (float) b.orientation.w))
                .translate((float) -b.comMesh.x, (float) -b.comMesh.y, (float) -b.comMesh.z);
            chunk.mesh.render(camera, aspectRatio, world);
        }
    }

    /** Whether anything is currently voxel-destroyed (remains or falling
     * pieces). Not currently called from anywhere in this project -
     * clearDestroyed() below is cheap enough (iterating empty
     * collections) that Main.java's own scene-switch code just calls it
     * unconditionally rather than gating it on this first. Kept as a
     * small, genuinely useful piece of this facade's own public surface
     * regardless (a HUD indicator, a "nothing to reset" early-out for
     * some future caller), rather than removed for being momentarily
     * unused. */
    public boolean hasDestroyed() {
        return destruction != null && !destruction.isEmpty();
    }

    /** Called on every scene switch (N/B), BEFORE this renderer's own
     * upload() for the new scene - a confirmed real bug otherwise: an
     * index destroyed in one scene stayed in these maps into the next,
     * differently-sized one, an ArrayIndexOutOfBoundsException waiting
     * to happen the next time the draw loop or a retarget indexed into
     * the NEW scene's own arrays with a stale index from the old one.
     * Each renderer properly closed first, not just dropped - the same
     * GL-resource-leak reasoning as every removal/replacement in this
     * whole system - including every falling piece's. The world is
     * dropped too (it holds the previous scene's instance list). */
    public void clearDestroyed() {
        if (destruction != null) {
            destruction.clear();
            destruction = null;
        }
    }

    public void render(FlyCamera camera, float aspectRatio) {
        if (useCache) {
            renderCached(camera, aspectRatio);
        } else {
            renderLive(camera, aspectRatio);
        }
    }

    private void renderLive(FlyCamera camera, float aspectRatio) {
        program.use();
        glBindVertexArray(vao);

        // Re-bind explicitly rather than relying on upload()'s initial
        // binding alone: PbdMeshCache's bake() reuses these same binding
        // points (0-3) with its own temporary single-instance buffers
        // while baking, so after any bake has happened these points no
        // longer point at the real scene data until this puts them back.
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_INSTANCES, instanceBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_PATCHES, patchBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_WORLD_TRANSFORMS, worldTransformBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MODIFIERS, modifierBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MATERIALS, materialBuffer);

        if (textureArrayGlId != 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayGlId);
        }
        if (normalTextureArrayGlId != 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D_ARRAY, normalTextureArrayGlId);
        }
        if (roughnessTextureArrayGlId != 0) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D_ARRAY, roughnessTextureArrayGlId);
        }
        if (displacementTextureArrayGlId != 0) {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D_ARRAY, displacementTextureArrayGlId);
        }

        glUniform3f(LOC_CAMERA_POS, camera.position.x, camera.position.y, camera.position.z);
        glUniform1f(LOC_LOD_MIN_DIST, lodMinDistance);
        glUniform1f(LOC_LOD_MAX_DIST, lodMaxDistance);
        glUniform1f(LOC_LOD_MIN_LVL, lodMinLevel);
        glUniform1f(LOC_LOD_MAX_LVL, lodMaxLevel);
        glUniform3f(LOC_LIGHT_DIR, lightDir[0], lightDir[1], lightDir[2]);
        glUniform1i(LOC_LIGHT_COUNT, lightCount);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(LOC_VIEW_PROJ, false, camera.viewProj(aspectRatio).get(stack.mallocFloat(16)));
        }

        lastTriangleCount = computeLiveTriangleCount(camera.position.x, camera.position.y, camera.position.z);
        lastDrawCallCount = 1;

        glDrawArrays(GL_PATCHES, 0, patchCount);
    }

    /**
     * One instance per draw call, each reading a baked (and, after the
     * first time any instance needs a given shape, reused) vertex buffer
     * from PbdMeshCache - no tessellation shader runs on this path at all,
     * once every distinct shape in the scene has been baked at least once.
     *
     * lodMinLevel == lodMaxLevel (the 5 flat named tiers) still means every
     * instance shares one level, computed once outside the loop. AUTO
     * (min != max, see Main.java's LOD_PRESETS) restores genuine
     * distance-based variation, so each instance's own distance is used -
     * the cache key already supported this (see PbdMeshCache.getOrBake),
     * only this call site needed to start actually passing per-instance
     * levels instead of always the scene-wide one.
     */
    /** Distance-based level for one instance under AUTO, same formula as pbd.tesc's mix(). */
    private int autoLevelFor(int instanceIndex, FlyCamera camera) {
        Vector3f pos = worldTransforms[instanceIndex].getTranslation(new Vector3f());
        float dx = pos.x - camera.position.x, dy = pos.y - camera.position.y, dz = pos.z - camera.position.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float range = Math.max(lodMaxDistance - lodMinDistance, 0.0001f);
        float t = Math.max(0f, Math.min(1f, (dist - lodMinDistance) / range));
        float level = lodMaxLevel * (1f - t) + lodMinLevel * t;
        return Math.max(1, Math.round(level));
    }

    private void renderCached(FlyCamera camera, float aspectRatio) {
        boolean auto = lodMinLevel != lodMaxLevel;
        int flatLevel = Math.max(1, (int) lodMaxLevel); // only meaningful when !auto
        int drawCalls = 0;
        long triangleTotal = 0;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < scene.instances.size(); i++) {
                PbdInstance inst = scene.instances.get(i);
                if (inst.type.equals("group")) continue; // pbd_ref anchor - no geometry, nothing to bake or draw
                if ("true".equals(inst.params.get("metadata"))) continue; // storage-space marker - real transform, no geometry
                int level = auto ? autoLevelFor(i, camera) : flatLevel;
                // Baking (if this shape/level hasn't been seen yet) uses its
                // own program/VAO/bindings internally and leaves them bound
                // on return - every state this draw call depends on is set
                // fresh below rather than assumed to still be in effect.
                PbdMeshCache.Entry entry = meshCache.getOrBake(inst, level, primitiveRegistry, modifierRegistry,
                    materialRegistry, materialBuffer, displacementTextureArrayGlId);

                cachedProgram.use();
                glBindVertexArray(cachedVao);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MATERIALS, materialBuffer);

                glBindBuffer(GL_ARRAY_BUFFER, entry.vbo);
                glEnableVertexAttribArray(0);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 8 * Float.BYTES, 0L);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(1, 3, GL_FLOAT, false, 8 * Float.BYTES, 3L * Float.BYTES);
                glEnableVertexAttribArray(2);
                glVertexAttribPointer(2, 2, GL_FLOAT, false, 8 * Float.BYTES, 6L * Float.BYTES);

                if (textureArrayGlId != 0) {
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayGlId);
                }
                if (normalTextureArrayGlId != 0) {
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D_ARRAY, normalTextureArrayGlId);
                }
                if (roughnessTextureArrayGlId != 0) {
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D_ARRAY, roughnessTextureArrayGlId);
                }
                if (displacementTextureArrayGlId != 0) {
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_2D_ARRAY, displacementTextureArrayGlId);
                }

                glUniformMatrix4fv(CACHED_LOC_WORLD, false, worldTransforms[i].get(stack.mallocFloat(16)));
                glUniformMatrix4fv(CACHED_LOC_VIEW_PROJ, false, camera.viewProj(aspectRatio).get(stack.mallocFloat(16)));
                glUniform3f(CACHED_LOC_CAMERA_POS, camera.position.x, camera.position.y, camera.position.z);
                glUniform3f(CACHED_LOC_LIGHT_DIR, lightDir[0], lightDir[1], lightDir[2]);
                glUniform1i(CACHED_LOC_LIGHT_COUNT, lightCount);
                glUniform1ui(CACHED_LOC_MATERIAL_ID,
                    materialRegistry.idOf(inst.material != null ? inst.material : "default"));

                glDrawArrays(GL_TRIANGLES, 0, entry.vertexCount);

                drawCalls++;
                triangleTotal += entry.vertexCount / 3;
            }
        }

        lastDrawCallCount = drawCalls;
        lastTriangleCount = (int) triangleTotal;
    }

    @Override
    public void close() {
        clearDestroyed(); // voxel remains + falling pieces own GL meshes of their own
        glDeleteBuffers(instanceBuffer);
        glDeleteBuffers(patchBuffer);
        glDeleteBuffers(worldTransformBuffer);
        glDeleteBuffers(modifierBuffer);
        glDeleteBuffers(materialBuffer);
        glDeleteVertexArrays(vao);
        program.close();

        glDeleteVertexArrays(cachedVao);
        cachedProgram.close();
        meshCache.close();
        if (textureArray != null) textureArray.close();
        if (normalTextureArray != null) normalTextureArray.close();
        if (roughnessTextureArray != null) roughnessTextureArray.close();
        if (displacementTextureArray != null) displacementTextureArray.close();
    }
}
