#version 430 core

layout(vertices = 1) out;

const uint TYPE_PLANE    = 0u;
const uint TYPE_SPHERE   = 1u;
const uint TYPE_CYLINDER = 2u;
const uint TYPE_CONE     = 3u;
const uint TYPE_CUBE     = 4u;
const uint TYPE_DISC     = 5u;
const uint TYPE_TORUS    = 6u;

// Floor for any parametric direction that doesn't represent curvature -
// a flat surface (cube face, plane, a cylinder wall's height, a disc's
// radius) is geometrically exact regardless of how finely it's cut, so
// subdividing it beyond this never changes how it looks, only how many
// triangles it costs. 1 is the lowest a tessellation level can go.
const float FLAT_LEVEL = 1.0;

const uint MOD_BEND = 0u;

struct PbdInstanceGpu {
    uint type;
    uint modifierStart;
    uint modifierCount;
    uint materialID;
    vec4 params0;
    vec4 params1;
};

struct PbdModifierGpu {
    uint type;
    uint curveRef;
    vec4 params0;
};

struct GpuPatch {
    uint instanceIndex;
    uint part;
};

layout(std430, binding = 0) readonly buffer InstanceBuffer {
    PbdInstanceGpu instances[];
};

layout(std430, binding = 1) readonly buffer PatchBuffer {
    GpuPatch patches[];
};

layout(std430, binding = 2) readonly buffer WorldTransformBuffer {
    mat4 worldTransforms[];
};

layout(std430, binding = 3) readonly buffer ModifierBuffer {
    PbdModifierGpu modifiers[];
};

// True if this instance has an active bend modifier - checked so the
// length/height direction (normally pinned flat below, since a straight
// cylinder or a sharp cube face is geometrically exact at any
// subdivision) gets real tessellation instead. Without this, the bend
// modifier's math can be perfectly correct and still render as a couple
// of straight segments meeting at a visible kink, since there's simply
// nowhere for a curve to appear between two widely-spaced rings of
// vertices - confirmed directly: rendering the existing (mathematically
// verified) bend formula on an ordinary cylinder showed exactly that
// kink, not a smooth arc, before this fix.
bool hasBendModifier(PbdInstanceGpu inst) {
    for (uint i = 0u; i < inst.modifierCount; i++) {
        if (modifiers[inst.modifierStart + i].type == MOD_BEND) {
            return true;
        }
    }
    return false;
}

layout(location = 0) uniform vec3 cameraPos;
layout(location = 1) uniform float lodMinDistance;
layout(location = 2) uniform float lodMaxDistance;
layout(location = 3) uniform float lodMinLevel;
layout(location = 4) uniform float lodMaxLevel;

// Returns which of the patch's two parametric directions actually
// represent curvature and need the LOD level - the other direction (or
// both, for flat primitives) is pinned to FLAT_LEVEL regardless of LOD.
//   sphere                     -> both directions round (theta and phi)
//   cylinder/cone wall (part 0) -> only the circumference (u) is round
//   cylinder/cone cap, disc (a flat disc's u=angle is round; radius isn't)
//   cube with smoothness > 0   -> both round (its rounded edges/corners
//                                  need real tessellation to look curved,
//                                  same as a sphere)
//   plane, sharp cube (smoothness == 0) -> both flat
void roundedAxes(PbdInstanceGpu inst, uint part, out bool uRound, out bool vRound) {
    if (inst.type == TYPE_SPHERE || inst.type == TYPE_TORUS) {
        uRound = true;
        vRound = true;
    } else if (inst.type == TYPE_CYLINDER || inst.type == TYPE_CONE || inst.type == TYPE_DISC) {
        uRound = true;
        vRound = hasBendModifier(inst); // height/length direction needs subdivision only once it's actually curved
    } else if (inst.type == TYPE_CUBE) {
        bool isSmooth = inst.params0.x > 0.001 || inst.params0.y > 0.001 || inst.params0.z > 0.001;
        bool bent = hasBendModifier(inst);
        uRound = isSmooth || bent;
        vRound = isSmooth || bent;
    } else {
        uRound = false;
        vRound = false;
    }
}

void main() {
    if (gl_InvocationID == 0) {
        GpuPatch gpuPatch = patches[gl_PrimitiveID];
        PbdInstanceGpu inst = instances[gpuPatch.instanceIndex];
        vec3 worldPos = vec3(worldTransforms[gpuPatch.instanceIndex][3]);
        float dist = distance(cameraPos, worldPos);

        float t = clamp((dist - lodMinDistance) / max(lodMaxDistance - lodMinDistance, 0.0001), 0.0, 1.0);
        float lodLevel = mix(lodMaxLevel, lodMinLevel, t);

        bool uRound, vRound;
        roundedAxes(inst, gpuPatch.part, uRound, vRound);
        float uLevel = uRound ? lodLevel : FLAT_LEVEL;
        float vLevel = vRound ? lodLevel : FLAT_LEVEL;

        // Outer[1]/[3] bound the u=0/u=1 edges (span the domain lengthwise
        // in u) so they carry uLevel; Outer[0]/[2] bound the v=0/v=1 edges
        // and carry vLevel. Inner[0]/[1] match u/v respectively. Verified
        // against a real GL_PRIMITIVES_GENERATED query with deliberately
        // distinct u/v values before this was trusted - see the README.
        gl_TessLevelOuter[0] = vLevel;
        gl_TessLevelOuter[1] = uLevel;
        gl_TessLevelOuter[2] = vLevel;
        gl_TessLevelOuter[3] = uLevel;
        gl_TessLevelInner[0] = uLevel;
        gl_TessLevelInner[1] = vLevel;
    }
    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
}
