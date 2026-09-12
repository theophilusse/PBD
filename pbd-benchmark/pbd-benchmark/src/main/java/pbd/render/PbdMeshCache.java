package pbd.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import pbd.format.MaterialRegistry;
import pbd.format.ModifierRegistry;
import pbd.format.PbdInstance;
import pbd.format.PbdModifier;
import pbd.format.PrimitiveRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

/**
 * Bakes a PBD shape's LOCAL-space geometry (before any per-instance world
 * transform) into a real GPU vertex buffer via Transform Feedback, once
 * per distinct (type, modifiers, LOD level) combination, then hands that
 * same buffer back to every instance that shares the combination. This is
 * what makes caching worthwhile for this game's actual use case (fixed
 * isometric, pixelated, non-moving scenery, discussed separately): the
 * "correct" tessellated shape for a static prop doesn't change frame to
 * frame once baked, and doesn't depend on which instance is being drawn -
 * only on its type, its modifiers, and the current LOD tier.
 *
 * Baking happens on a SEPARATE, second linked instance of the exact same
 * pbd.vert/pbd.tesc/pbd.tese/pbd.frag sources PbdRenderer uses for live
 * rendering (glTransformFeedbackVaryings must be called before linking, so
 * it can't be retrofitted onto the already-linked live program) - both
 * programs are compiled from the same shader files, so a change to the
 * shape math only has to happen in one place.
 */
public final class PbdMeshCache implements AutoCloseable {

    /** A baked mesh: a GPU buffer of interleaved (localPos, localNormal) triangles, unindexed. */
    public static final class Entry {
        public final int vbo;
        public final int vertexCount;

        Entry(int vbo, int vertexCount) {
            this.vbo = vbo;
            this.vertexCount = vertexCount;
        }
    }

    private final int bakeProgram;
    private final int bakeVao;
    private final int instanceBuffer;
    private final int patchBuffer;
    private final int worldBuffer;
    private final int modifierBuffer;

    private final Map<String, Entry> cache = new HashMap<>();

    /** Number of distinct bakes performed so far - exposed for the stats overlay. */
    public int bakeCount = 0;

    public PbdMeshCache(Path shaderDir) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, shaderDir.resolve("pbd.vert"));
        int tc = compile(GL_TESS_CONTROL_SHADER, shaderDir.resolve("pbd.tesc"));
        int te = compile(GL_TESS_EVALUATION_SHADER, shaderDir.resolve("pbd.tese"));
        int fs = compile(GL_FRAGMENT_SHADER, shaderDir.resolve("pbd.frag"));

        bakeProgram = glCreateProgram();
        glAttachShader(bakeProgram, vs);
        glAttachShader(bakeProgram, tc);
        glAttachShader(bakeProgram, te);
        glAttachShader(bakeProgram, fs);

        // Must happen before linking - captures pbd.tese's pre-world-
        // transform outputs, interleaved (pos, normal, pos, normal, ...)
        // into whatever buffer is bound at GL_TRANSFORM_FEEDBACK_BUFFER
        // index 0 during a bracketed begin/end capture.
        glTransformFeedbackVaryings(bakeProgram, new CharSequence[]{"vLocalPos", "vLocalNormal", "vUV"}, GL_INTERLEAVED_ATTRIBS);

        glLinkProgram(bakeProgram);
        if (glGetProgrami(bakeProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Bake program link failed:\n" + glGetProgramInfoLog(bakeProgram));
        }
        for (int shader : new int[]{vs, tc, te, fs}) {
            glDetachShader(bakeProgram, shader);
            glDeleteShader(shader);
        }

        bakeVao = glGenVertexArrays();
        instanceBuffer = glGenBuffers();
        patchBuffer = glGenBuffers();
        worldBuffer = glGenBuffers();
        modifierBuffer = glGenBuffers();
    }

    private static int compile(int type, Path path) throws IOException {
        String source = Files.readString(path);
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Bake shader compile failed (" + path + "):\n" + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /**
     * Everything about an instance that affects its BAKED geometry - not
     * its material (materials are applied at draw time, from the same
     * shared material buffer, via a uniform - see cached.frag) and not its
     * world transform (that's why baking is worth sharing across
     * instances at all). Two instances with the same signature at the
     * same LOD level are geometrically identical in local space.
     */
    public static String signatureFor(PbdInstance inst) {
        StringBuilder sb = new StringBuilder(inst.type);
        if ("cube".equals(inst.type)) {
            String base = inst.params.getOrDefault("smooth", "0");
            sb.append(":smoothX=").append(inst.params.getOrDefault("smoothX", base));
            sb.append(":smoothY=").append(inst.params.getOrDefault("smoothY", base));
            sb.append(":smoothZ=").append(inst.params.getOrDefault("smoothZ", base));
        }
        if ("cylinder".equals(inst.type) || "cone".equals(inst.type)) {
            sb.append(":cap=").append(inst.params.getOrDefault("cap", "both"));
        }
        for (PbdModifier mod : inst.modifiers) {
            sb.append(":mod=").append(mod.type).append(mod.params);
        }
        // Material is part of the shape's identity now, not just its
        // shading: displacement actually moves vertices, so two
        // instances with identical type/modifiers but different
        // materials can no longer safely share one baked mesh - one
        // material's height map would silently apply to the other's
        // geometry. Included unconditionally rather than only when a
        // material happens to have a displacement map, since that check
        // would need this method to know about materialOverrides too -
        // the cost is a few more cache entries for materials that didn't
        // need the split, not a correctness gap.
        sb.append(":mat=").append(inst.material != null ? inst.material : "default");
        return sb.toString();
    }

    /** Returns the cached bake for this instance's shape at this LOD level, baking it first if needed. */
    public Entry getOrBake(PbdInstance inst, int level, PrimitiveRegistry primitiveRegistry,
                            ModifierRegistry modifierRegistry, MaterialRegistry materialRegistry,
                            int materialBufferId, int displacementTextureArrayGlId) {
        String key = signatureFor(inst) + ":L" + level;
        Entry existing = cache.get(key);
        if (existing != null) return existing;

        Entry baked = bake(inst, level, primitiveRegistry, modifierRegistry, materialRegistry,
            materialBufferId, displacementTextureArrayGlId);
        cache.put(key, baked);
        bakeCount++;
        return baked;
    }

    private Entry bake(PbdInstance inst, int level, PrimitiveRegistry primitiveRegistry,
                        ModifierRegistry modifierRegistry, MaterialRegistry materialRegistry,
                        int materialBufferId, int displacementTextureArrayGlId) {
        List<GpuPatch> patches = PatchExpander.partsForBaking(inst);

        // --- instance buffer: one synthetic instance, at identity - see
        // the class doc for why baking is always in local space.
        ByteBuffer instBuf = nativeBuffer(48);
        instBuf.putInt(primitiveRegistry.idOf(inst.type));
        instBuf.putInt(0);
        instBuf.putInt(inst.modifiers.size());
        // Real materialID, not a placeholder - pbd.tese now reads
        // material data during this very bake pass (displacement:
        // moving a vertex needs the instance's own height map, not
        // whichever material happens to be id 0 - see signatureFor's
        // comment on why the cache key had to change alongside this).
        instBuf.putInt(materialRegistry.idOf(inst.material != null ? inst.material : "default"));
        float[] cubeSmooth = {0f, 0f, 0f};
        if ("cube".equals(inst.type)) {
            float base = Float.parseFloat(inst.params.getOrDefault("smooth", "0"));
            cubeSmooth[0] = Float.parseFloat(inst.params.getOrDefault("smoothX", String.valueOf(base)));
            cubeSmooth[1] = Float.parseFloat(inst.params.getOrDefault("smoothY", String.valueOf(base)));
            cubeSmooth[2] = Float.parseFloat(inst.params.getOrDefault("smoothZ", String.valueOf(base)));
        }
        instBuf.putFloat(cubeSmooth[0]).putFloat(cubeSmooth[1]).putFloat(cubeSmooth[2]).putFloat(0);
        instBuf.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        instBuf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, instanceBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, instBuf, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, instanceBuffer);
        MemoryUtil.memFree(instBuf);

        // --- patch buffer ---
        ByteBuffer patchBuf = nativeBuffer(patches.size() * 8);
        for (GpuPatch p : patches) {
            patchBuf.putInt(0); // every patch belongs to our one synthetic instance
            patchBuf.putInt(p.part);
        }
        patchBuf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, patchBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, patchBuf, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, patchBuffer);
        MemoryUtil.memFree(patchBuf);

        // --- world buffer: identity - baking captures local space only ---
        ByteBuffer worldBuf = nativeBuffer(64);
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        for (float f : identity) worldBuf.putFloat(f);
        worldBuf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, worldBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, worldBuf, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, worldBuffer);
        MemoryUtil.memFree(worldBuf);

        // --- modifier buffer: this instance's real modifiers, so a bend/
        // taper's actual parameters are baked into the local geometry ---
        int modCount = Math.max(inst.modifiers.size(), 1);
        ByteBuffer modBuf = nativeBuffer(modCount * 32);
        for (PbdModifier mod : inst.modifiers) {
            modBuf.putInt(modifierRegistry.idOf(mod.type));
            modBuf.putInt(0);
            modBuf.position(modBuf.position() + 8);
            if ("taper".equals(mod.type)) {
                modBuf.putFloat(mod.getFloat("bottomScale", 1f));
                modBuf.putFloat(mod.getFloat("topScale", 1f));
                modBuf.putFloat(0).putFloat(0);
            } else if ("bend".equals(mod.type)) {
                modBuf.putFloat(mod.getFloat("angle", 0f));
                modBuf.putFloat(PbdRenderer.axisIndex(mod.get("axis")));
                modBuf.putFloat(0).putFloat(0);
            } else if ("shear".equals(mod.type)) {
                modBuf.putFloat(mod.getFloat("factorX", 0f));
                modBuf.putFloat(mod.getFloat("factorY", 0f));
                modBuf.putFloat(mod.getFloat("factorZ", 0f));
                modBuf.putFloat(0);
            } else {
                modBuf.putFloat(mod.getFloat("angle", 0f));
                modBuf.putFloat(0).putFloat(0).putFloat(0);
            }
        }
        if (inst.modifiers.isEmpty()) {
            modBuf.putInt(0).putInt(0);
            modBuf.position(modBuf.position() + 8);
            modBuf.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        }
        modBuf.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, modifierBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, modBuf, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, modifierBuffer);
        MemoryUtil.memFree(modBuf);

        // --- capture buffer: tight upper bound, 2*level^2 triangles is the
        // worst case per patch regardless of which round/flat combination
        // actually applies (checked: this bound holds for every level >= 1) ---
        long maxTrianglesPerPatch = 2L * level * level;
        long maxVertices = (long) patches.size() * maxTrianglesPerPatch * 3;
        long captureBytes = maxVertices * 8 * Float.BYTES; // pos.xyz + normal.xyz + uv.xy

        int captureVbo = glGenBuffers();
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, captureVbo);
        glBufferData(GL_TRANSFORM_FEEDBACK_BUFFER, captureBytes, GL_STATIC_DRAW);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, captureVbo);

        glUseProgram(bakeProgram);
        glBindVertexArray(bakeVao);
        glPatchParameteri(GL_PATCH_VERTICES, 1);

        // Pin lodMinLevel = lodMaxLevel = level, exactly like Main.java's
        // flat per-tier presets - the distance-based mix() in pbd.tesc
        // then returns `level` regardless of cameraPos, so the values
        // below are placeholders, never actually read meaningfully.
        glUniform3f(0, 0f, 0f, 0f);
        glUniform1f(1, 1f);
        glUniform1f(2, 2f);
        glUniform1f(3, (float) level);
        glUniform1f(4, (float) level);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(5, false, stack.mallocFloat(16).put(identity).flip());
        }
        glUniform3f(6, 0.3f, -1f, -0.2f);

        // pbd.tese now reads material data (displacement) during this
        // very draw call - without these, binding=4 would be read
        // unbound (undefined behavior) and the displacement sampler
        // would see nothing bound at unit 3.
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, materialBufferId);
        if (displacementTextureArrayGlId != 0) {
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D_ARRAY, displacementTextureArrayGlId);
        }

        int query = glGenQueries();
        glEnable(GL_RASTERIZER_DISCARD);
        glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, query);
        glBeginTransformFeedback(GL_TRIANGLES);
        glDrawArrays(GL_PATCHES, 0, patches.size());
        glEndTransformFeedback();
        glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
        glDisable(GL_RASTERIZER_DISCARD);

        int trianglesWritten = glGetQueryObjecti(query, GL_QUERY_RESULT);
        glDeleteQueries(query);

        return new Entry(captureVbo, trianglesWritten * 3);
    }

    private static ByteBuffer nativeBuffer(int bytes) {
        return MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
    }

    @Override
    public void close() {
        for (Entry e : cache.values()) glDeleteBuffers(e.vbo);
        glDeleteProgram(bakeProgram);
        glDeleteVertexArrays(bakeVao);
        glDeleteBuffers(instanceBuffer);
        glDeleteBuffers(patchBuffer);
        glDeleteBuffers(worldBuffer);
        glDeleteBuffers(modifierBuffer);
    }
}
