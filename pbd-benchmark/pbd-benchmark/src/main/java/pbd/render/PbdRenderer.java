package pbd.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdInstance;
import pbd.format.PbdModifier;
import pbd.format.PbdScene;
import pbd.format.PrimitiveRegistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
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

    private static final int CACHED_LOC_WORLD       = 0;
    private static final int CACHED_LOC_VIEW_PROJ   = 1;
    private static final int CACHED_LOC_CAMERA_POS  = 2;
    private static final int CACHED_LOC_LIGHT_DIR   = 3;
    private static final int CACHED_LOC_MATERIAL_ID = 4;

    private static final int BINDING_INSTANCES        = 0;
    private static final int BINDING_PATCHES           = 1;
    private static final int BINDING_WORLD_TRANSFORMS  = 2;
    private static final int BINDING_MODIFIERS         = 3;
    private static final int BINDING_MATERIALS         = 4;

    private final ShaderProgram program;
    private final int vao;
    private final int instanceBuffer;
    private final int patchBuffer;
    private final int worldTransformBuffer;
    private final int modifierBuffer;
    private final int materialBuffer;

    private final ShaderProgram cachedProgram;
    private final int cachedVao;
    private final PbdMeshCache meshCache;
    private MaterialTextureArray textureArray;
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
        this.hasAnimatedInstances = scene.instances.stream().anyMatch(inst -> !inst.keyframes.isEmpty());
        this.instanceAnimTime = new double[scene.instances.size()];
        this.instanceOpen = new boolean[scene.instances.size()];
        this.instanceLoop = new boolean[scene.instances.size()];
        this.instanceLoopForward = new boolean[scene.instances.size()];
        for (int i = 0; i < scene.instances.size(); i++) {
            PbdInstance inst = scene.instances.get(i);
            // Starts closed (sitting at the first keyframe's time) - a
            // cabinet loads shut, not mid-animation.
            instanceAnimTime[i] = inst.keyframes.isEmpty() ? Double.NaN : inst.keyframes.get(0).time;
            instanceOpen[i] = false;
            instanceLoop[i] = "true".equals(inst.params.get("loop"));
            instanceLoopForward[i] = true;
        }
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
    private boolean[] instanceOpen;      // target state: true = animating toward the last keyframe, false = toward the first
    private boolean[] instanceLoop;      // true = ping-pongs continuously, ignoring instanceOpen/clicks entirely

    /** True once instance i's keyframe animation has fully reached its
     * last keyframe's time (the "open" pose) - the gate for computing
     * and showing container contents (bin-packed items), per this
     * project's own rule: no computation or display before the
     * container is actually open. False for a non-keyframed instance
     * (index out of the keyframed set entirely) or one still mid-animation. */
    public boolean isInstanceFullyOpen(int instanceIndex) {
        if (instanceIndex < 0 || instanceIndex >= scene.instances.size()) return false;
        if (Double.isNaN(instanceAnimTime[instanceIndex])) return false;
        PbdInstance inst = scene.instances.get(instanceIndex);
        if (inst.keyframes.isEmpty()) return false;
        double lastTime = inst.keyframes.get(inst.keyframes.size() - 1).time;
        return instanceOpen[instanceIndex] && Math.abs(instanceAnimTime[instanceIndex] - lastTime) < 0.01;
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

    public PbdScene scene() {
        return scene;
    }
    private boolean[] instanceLoopForward; // current ping-pong direction, looping instances only
    private static final double KEYFRAME_PLAYBACK_RATE = 1.0; // seconds of animation time per real second - matches the time= units keyframes are authored in

    /**
     * Advances every keyframed instance's own local time toward its
     * current open/closed target, then re-resolves and re-uploads world
     * transforms - only does any work if the scene actually has a
     * keyframed instance, so a static scene pays nothing extra per
     * frame. Call once per frame, before render().
     */
    public void updateAnimation(double dt) {
        if (!hasAnimatedInstances) return;
        for (int i = 0; i < scene.instances.size(); i++) {
            if (Double.isNaN(instanceAnimTime[i])) continue;
            PbdInstance inst = scene.instances.get(i);
            double lastTime = inst.keyframes.get(inst.keyframes.size() - 1).time;
            double firstTime = inst.keyframes.get(0).time;
            double step = KEYFRAME_PLAYBACK_RATE * dt;

            if (instanceLoop[i]) {
                // Continuous ping-pong, ignoring open/closed state and
                // clicks entirely - a ceiling fan or decorative element,
                // not a container someone opens and closes.
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
        }
        worldTransforms = new HierarchyResolver().resolve(scene, instanceAnimTime);
        uploadWorldTransforms(worldTransforms);
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
        return Math.max(tMin, 0f);
    }
    public int toggleKeyframedInstanceAlongRay(Vector3f rayOrigin, Vector3f rayDir) {
        if (!hasAnimatedInstances) return -1;
        int closestIndex = -1;
        float closestDist = Float.MAX_VALUE;
        for (int i = 0; i < scene.instances.size(); i++) {
            if (Double.isNaN(instanceAnimTime[i])) continue;
            if (instanceLoop[i]) continue; // ping-pongs continuously, clicks have no effect on it

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
            Matrix4f invWorld = new Matrix4f(worldTransforms[i]).invert();
            Vector3f localOrigin = invWorld.transformPosition(new Vector3f(rayOrigin));
            Vector3f localDir = invWorld.transformDirection(new Vector3f(rayDir));

            float t = rayBoxIntersection(localOrigin, localDir, -0.55f, 0.55f); // 0.5 canonical half-extent + a small margin so an edge click still lands
            if (t < 0) continue;

            // Compare hit distance in WORLD space (t alone is in the
            // instance's own, differently-scaled local space and isn't
            // comparable across instances) - re-derive the world-space
            // hit point and measure from the real ray origin.
            Vector3f localHit = new Vector3f(localDir).mul(t).add(localOrigin);
            Vector3f worldHit = worldTransforms[i].transformPosition(new Vector3f(localHit));
            float worldDist = worldHit.distance(rayOrigin);
            if (worldDist < closestDist) {
                closestDist = worldDist;
                closestIndex = i;
            }
        }
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
        } else {
            for (int i = 0; i < scene.instances.size(); i++) {
                if (Double.isNaN(instanceAnimTime[i])) continue;
                if (group.equals(scene.instances.get(i).params.get("linkGroup"))) {
                    instanceOpen[i] = newState;
                }
            }
        }
        return closestIndex;
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
        textureArrayGlId = textureArray.glId;
        normalTextureArrayGlId = normalTextureArray.glId;
        roughnessTextureArrayGlId = roughnessTextureArray.glId;
        displacementTextureArrayGlId = displacementTextureArray.glId;

        int count = Math.max(materialRegistry.count(), 1);
        // 64, not 56: a struct containing a vec4 has 16-byte base
        // alignment in std430, and the array stride must be a multiple
        // of that - the struct's natural size (56 bytes = 14 floats)
        // isn't a multiple of 16, so GLSL pads every element up to 64.
        // Writing exactly 56 bytes per entry with no padding put every
        // material past index 0 at the wrong offset - confirmed against
        // the official GLSL std430 layout rules (a struct's base
        // alignment is its largest member's alignment, vec4 = 16 here,
        // and array stride is that size rounded up to that alignment).
        final int stride = 64;
        ByteBuffer buf = nativeBuffer(count * stride);
        for (int id = 0; id < count; id++) {
            String name = materialRegistry.nameOf(id);
            MaterialCatalog.Entry entry = resolveMaterialEntry(name);
            float textureLayer = -1f, uvScale = 1f, normalLayer = -1f, roughnessLayer = -1f, reflectivity = 0f, transparency = 0f;
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
                    if (override.containsKey("uvScale")) uvScale = Float.parseFloat(override.get("uvScale"));
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
            System.out.printf("[Materials] id=%d name=%s texLayer=%.0f uvScale=%.2f normalLayer=%.0f roughLayer=%.0f dispLayer=%.0f reflect=%.2f transp=%.2f%n",
                id, name, textureLayer, uvScale, normalLayer, roughnessLayer, displacementLayer, reflectivity, transparency);
            buf.putFloat(entry.r).putFloat(entry.g).putFloat(entry.b).putFloat(1f); // baseColor (vec4, alpha unused)
            buf.putFloat(entry.shininess);
            buf.putFloat(entry.specularStrength);
            buf.putFloat(textureLayer);
            buf.putFloat(uvScale);
            buf.putFloat(normalLayer);
            buf.putFloat(roughnessLayer);
            buf.putFloat(reflectivity);
            buf.putFloat(transparency);
            buf.putFloat(displacementLayer);
            buf.putFloat(displacementScale);
            buf.putFloat(0f).putFloat(0f); // std430 padding - see the stride comment above
        }
        buf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, buf, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MATERIALS, materialBuffer);
        MemoryUtil.memFree(buf);
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
