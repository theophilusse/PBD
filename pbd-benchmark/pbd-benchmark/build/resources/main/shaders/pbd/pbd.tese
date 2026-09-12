#version 430 core

layout(quads, equal_spacing, cw) in;

const uint TYPE_PLANE    = 0u;
const uint TYPE_SPHERE   = 1u;
const uint TYPE_CYLINDER = 2u;
const uint TYPE_CONE     = 3u;
const uint TYPE_CUBE     = 4u;
const uint TYPE_DISC     = 5u;
const uint TYPE_TORUS    = 6u;

const uint MOD_BEND  = 0u;
const uint MOD_TAPER = 1u;
const uint MOD_TWIST = 2u;
const uint MOD_CURVE = 3u;
const uint MOD_SHEAR = 4u;

const float PI  = 3.14159265359;
const float TAU = 6.28318530718;

struct PbdInstanceGpu {
    uint type;
    uint modifierStart;
    uint modifierCount;
    uint materialID;
    vec4 params0;
    vec4 params1;
};

struct GpuPatch {
    uint instanceIndex;
    uint part;
};

struct PbdModifierGpu {
    uint type;
    uint curveRef;
    vec4 params0;
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

layout(location = 5) uniform mat4 viewProj;

layout(location = 0) out vec3 vNormal;
layout(location = 1) flat out uint vMaterialID;
layout(location = 2) out vec3 vWorldPos;
// Pre-world-transform position/normal - unused by pbd.frag (an unconsumed
// `out` is fine in GLSL), captured via Transform Feedback for
// PbdMeshCache's baking pass instead. Local space specifically because a
// baked mesh is reused across every instance sharing the same (type,
// modifiers, LOD level), each with its own world transform applied at
// draw time - baking world-space data would defeat that reuse entirely.
layout(location = 3) out vec3 vLocalPos;
layout(location = 4) out vec3 vLocalNormal;
// The exact (u,v) tessellation coordinate, unmodified by any modifier -
// every evalXXX function below already derives its geometry directly
// from this same uv, so it doubles as a natural texture coordinate for
// free: no unwrapping needed, for any primitive type. Not consumed by
// pbd.frag yet (same "unconsumed out is fine" note as vLocalPos above) -
// laid down now since both bump and displacement mapping need it, ahead
// of implementing either.
layout(location = 5) out vec2 vUV;

// ---- canonical primitives (unit size, before transform) -------------------
// Actual sizing comes entirely from worldTransforms (T*R*S): an ellipsoid,
// for instance, is just a sphere with a non-uniform scale, so there is no
// "radius" field here - only non-linear params (cube's smooth radius,
// taper's end scales...) live in inst.params0/params1 or a modifier's own
// params0.

void evalPlane(vec2 uv, out vec3 pos, out vec3 nrm) {
    pos = vec3(uv.x - 0.5, 0.0, uv.y - 0.5);
    nrm = vec3(0.0, 1.0, 0.0);
}

void evalSphere(vec2 uv, out vec3 pos, out vec3 nrm) {
    float theta = uv.x * TAU;
    float phi = uv.y * PI;
    pos = 0.5 * vec3(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta));
    nrm = normalize(pos);
}

void evalCylinderWall(vec2 uv, out vec3 pos, out vec3 nrm) {
    float angle = uv.x * TAU;
    float h = uv.y - 0.5;
    pos = vec3(0.5 * cos(angle), h, 0.5 * sin(angle));
    nrm = normalize(vec3(cos(angle), 0.0, sin(angle)));
}

// Flat disc lying in XZ (normal +Y or -Y), diameter 1 - shared by the
// cylinder/cone caps below and by the standalone TYPE_DISC primitive.
void evalDisc(vec2 uv, float y, float normalSign, out vec3 pos, out vec3 nrm) {
    float angle = uv.x * TAU;
    float r = uv.y * 0.5;
    pos = vec3(r * cos(angle), y, r * sin(angle));
    nrm = vec3(0.0, normalSign, 0.0);
}

// Major radius (tube centerline to torus center) 0.35, minor radius (tube
// itself) 0.15 - outer extent 0.35+0.15=0.5, matching every other
// primitive's canonical radius; inner hole radius 0.35-0.15=0.2.
// u sweeps once around the main axis (theta), v sweeps once around the
// tube's own cross-section (phi). Position is the tube centerline
// (major*cos/sin theta, 0, ...) plus a minor-radius offset in the plane
// spanned by the radial direction (cos theta, 0, sin theta) and world Y:
//   offset = minor*cos(phi)*radial + minor*sin(phi)*Y
// which is exactly what falls out of the x/y/z formula below. Both
// "radial" and Y are unit and mutually perpendicular, so the same offset
// with cos(phi)/sin(phi) swapped for the coefficients (instead of the
// position's radius terms) is already a unit vector - the normal, with no
// separate normalization needed.
const float TORUS_MAJOR = 0.35;
const float TORUS_MINOR = 0.15;

void evalTorus(vec2 uv, out vec3 pos, out vec3 nrm) {
    float theta = uv.x * TAU;
    float phi = uv.y * TAU;
    float ct = cos(theta), st = sin(theta);
    float cp = cos(phi), sp = sin(phi);
    float ringR = TORUS_MAJOR + TORUS_MINOR * cp;
    pos = vec3(ringR * ct, TORUS_MINOR * sp, ringR * st);
    nrm = vec3(cp * ct, sp, cp * st);
}

void evalConeWall(vec2 uv, out vec3 pos, out vec3 nrm) {
    float angle = uv.x * TAU;
    float h = uv.y - 0.5;           // -0.5 (base) .. 0.5 (apex)
    float radius = 0.5 * (0.5 - h); // 0.5 at the base, 0 at the apex
    pos = vec3(radius * cos(angle), h, radius * sin(angle));
    // Approximate normal (fixed slope for a unit cone) - refine if very
    // precise shading near the apex is ever needed.
    nrm = normalize(vec3(cos(angle), 0.5, sin(angle)));
}

void evalCubeFaceFlat(vec2 uv, uint face, out vec3 pos, out vec3 nrm) {
    vec2 p = uv - 0.5;
    if (face == 0u)      { pos = vec3( 0.5,  p.y, -p.x); nrm = vec3( 1.0, 0.0, 0.0); }
    else if (face == 1u) { pos = vec3(-0.5,  p.y,  p.x); nrm = vec3(-1.0, 0.0, 0.0); }
    else if (face == 2u) { pos = vec3( p.x,  0.5, -p.y); nrm = vec3( 0.0, 1.0, 0.0); }
    else if (face == 3u) { pos = vec3( p.x, -0.5,  p.y); nrm = vec3( 0.0,-1.0, 0.0); }
    else if (face == 4u) { pos = vec3( p.x,  p.y,  0.5); nrm = vec3( 0.0, 0.0, 1.0); }
    else                 { pos = vec3(-p.x,  p.y, -0.5); nrm = vec3( 0.0, 0.0,-1.0); }
}

// Rounded cube edges/corners (params0.xyz = per-axis smoothness, 0..0.5
// each), for cushions and soft furniture. Generalizes a uniform-radius
// version (Minkowski sum of a box and a SPHERE) to a per-axis one
// (Minkowski sum of a box and an ELLIPSOID): clamp the flat position to
// an inner box shrunk independently on each axis, then project the
// leftover onto an ellipsoid with those same per-axis radii instead of a
// single-radius sphere. This is what lets a cushion have a flat top
// (smoothY ~ 0, so the inner box's Y bound stays the full 0.5 and nothing
// ever lands outside it in Y) while its side edges are still rounded
// (larger smoothX/smoothZ) - one shared radius forced onto every axis
// made a wide flat cushion round on every side including the sittable
// top, not just its edges. Uniform input (equal x/y/z) reduces to exactly
// a single-radius sphere (dividing and re-multiplying by the same
// positive scalar is a no-op on direction), and the degenerate limits
// (0 = flat on that axis, 0.5 = fully round on that axis) still hold
// per-axis.
void evalCubeFaceSmooth(vec2 uv, uint face, vec3 smoothness, out vec3 pos, out vec3 nrm) {
    vec3 flatPos, flatNrm;
    evalCubeFaceFlat(uv, face, flatPos, flatNrm);

    vec3 s = clamp(smoothness, 0.0, 0.5);
    vec3 innerBound = vec3(0.5) - s;
    vec3 innerPos = clamp(flatPos, -innerBound, innerBound);
    vec3 offset = flatPos - innerPos;
    float offsetLen = length(offset);

    if (offsetLen < 1e-6) {
        pos = flatPos;
        nrm = flatNrm;
    } else {
        vec3 sSafe = max(s, vec3(1e-6)); // guards the divide; that axis's offset is already ~0 whenever its own s is ~0
        vec3 scaledDir = normalize(offset / sSafe);
        vec3 localOffset = scaledDir * s;                // a point on the (sx,sy,sz) ellipsoid, in the direction of offset
        pos = innerPos + localOffset;
        nrm = normalize(localOffset / (sSafe * sSafe));  // gradient of (x/sx)^2+(y/sy)^2+(z/sz)^2=1
    }
}

// ---- modifiers --------------------------------------------------------------
// `bend` and `taper` are implemented. `twist`, and especially `curve`
// (which needs a Frenet frame along a curve), are still to do - see the
// project README.

// `shear` (formerly mislabeled `bend`) rotates one plane of two
// coordinates by an angle proportional to the third. This is NOT a true
// bend: since the angle itself grows with the driving coordinate, a
// cross-section further from center gets rotated further, which - worked
// out algebraically - scales that cross-section's extent by cos(angle)
// rather than preserving it, on top of the rotation. Kept because it
// produces a usable spiral/warp effect on flat shapes (a disc-based
// leaf's curved profile, say), but renamed once it became clear on a
// cylinder that it does not behave like an actual bend. Applies all three
// cyclic cases (X, Y, Z) in sequence rather than requiring a single axis
// choice - params0.xyz are the three factors (0 = no effect from that
// case), so any single-axis effect from before is just two of the three
// left at zero, and combining more than one is now possible where that's
// useful.
void applyShear(PbdModifierGpu mod, inout vec3 pos, inout vec3 nrm) {
    float fx = mod.params0.x; // case X: arc-length Y, rotates Y/Z
    float fy = mod.params0.y; // case Y: arc-length Z, rotates Z/X
    float fz = mod.params0.z; // case Z: arc-length X, rotates X/Y

    if (abs(fx) > 1e-6) {
        float angle = fx * pos.y;
        float c = cos(angle), s = sin(angle);
        float py = pos.y, pz = pos.z;
        pos.y = py * c - pz * s; pos.z = py * s + pz * c;
        float ny = nrm.y, nz = nrm.z;
        nrm.y = ny * c - nz * s; nrm.z = ny * s + nz * c;
    }
    if (abs(fy) > 1e-6) {
        float angle = fy * pos.z;
        float c = cos(angle), s = sin(angle);
        float pz = pos.z, px = pos.x;
        pos.z = pz * c - px * s; pos.x = pz * s + px * c;
        float nz = nrm.z, nx = nrm.x;
        nrm.z = nz * c - nx * s; nrm.x = nz * s + nx * c;
    }
    if (abs(fz) > 1e-6) {
        float angle = fz * pos.x;
        float c = cos(angle), s = sin(angle);
        float px = pos.x, py = pos.y;
        pos.x = px * c - py * s; pos.y = px * s + py * c;
        float nx = nrm.x, ny = nrm.y;
        nrm.x = nx * c - ny * s; nrm.y = nx * s + ny * c;
    }
}

// A real bend: a constant-radius circular arc, preserving both arc length
// and cross-section shape (no compression, unlike `shear` above).
// params0.x = total angle across the full extent, params0.y = axis (0=X:
// bends the Y extent into the Y/Z plane, X untouched; 1=Y: bends Z into
// Z/X, Y untouched; 2=Z: bends X into X/Y, Z untouched) - same cyclic
// convention as `shear` and `taper`'s callers.
//
// Scale-aware: worldScale is this instance's actual (X,Y,Z) world scale,
// extracted from its world matrix before this runs. This matters because
// bending a cylinder along its own natural length axis (Y) necessarily
// picks one of the cylinder's OWN RADIUS axes (X or Z) as the "offset"
// direction - and a realistic pole is thin, meaning that radius axis
// carries a small scale factor. Earlier this modifier computed a bend
// purely in local (pre-scale) space, so BOTH the cross-section radius AND
// the bend's own sideways displacement shared that same small scale
// factor - the displacement got crushed to invisibility by the exact
// scale that makes the pole thin (looked "still straight"), while
// separately, the self-intersection threshold (R must exceed the LOCAL
// canonical radius of 0.5) depended only on the angle, never the scale -
// so past about 115 degrees the local cross-section geometry folded onto
// itself regardless of how thin the pole visually was (the jagged
// self-intersecting mess, confirmed by reproducing it standalone before
// writing this fix). Both symptoms were the same root cause.
//
// Fix: derive the local-space formula so that, AFTER this instance's own
// world-space scale is applied, the result is a properly circular arc in
// WORLD space - not local space. Letting aspect = lengthAxisScale /
// offsetAxisScale (large for a thin, tall pole):
//   effForLength = 1/totalAngle - offsetCoord / aspect
//   effForOffset = aspect/totalAngle - offsetCoord
//   newLength = effForLength * sin(phi)
//   newOffset = aspect/totalAngle - effForOffset * cos(phi)
// At aspect=1 (isotropic scale) this reduces algebraically to exactly the
// previous formula - checked directly, not assumed. Checked numerically
// before being written here: the world-space centerline traces an exact
// circle regardless of aspect ratio, a world-space cross-section at a
// fixed height stays a perfect circle of the correct (scaled) radius, and
// for this project's actual thin-pole proportions (scale 0.06 radius, 3
// height, aspect 50) the self-intersection threshold moves from ~115
// degrees to several full turns - i.e., it stops being reachable at all
// for a realistic bend angle.
void applyBend(PbdModifierGpu mod, inout vec3 pos, inout vec3 nrm, vec3 worldScale) {
    float totalAngle = mod.params0.x;
    if (abs(totalAngle) < 1e-6) return; // identity - also guards the 1/totalAngle below

    uint axis = uint(mod.params0.y + 0.5);

    float lengthScale = (axis == 0u) ? worldScale.y : (axis == 1u) ? worldScale.z : worldScale.x;
    float offsetScale  = (axis == 0u) ? worldScale.z : (axis == 1u) ? worldScale.x : worldScale.y;
    float aspect = lengthScale / max(offsetScale, 1e-6);

    float lengthCoord = (axis == 0u) ? pos.y : (axis == 1u) ? pos.z : pos.x;
    float offsetCoord  = (axis == 0u) ? pos.z : (axis == 1u) ? pos.x : pos.y;
    // Anchored at the -length end (phi=0 there, full totalAngle at the
    // +length end) rather than symmetric around the center. A symmetric
    // bend curves the two halves in OPPOSITE directions from the middle -
    // for a pole meant to be planted at one end and curve over at the
    // other (a lamp post, an elbow pipe), that produces a "V" opening away
    // from center, not the single continuous curve a fixed base implies.
    float phi = totalAngle * (lengthCoord + 0.5);
    float c = cos(phi), s = sin(phi);

    float rOff = aspect / totalAngle;
    float effForLength = 1.0 / totalAngle - offsetCoord / aspect;
    float effForOffset = rOff - offsetCoord;

    float newLength = effForLength * s;
    float newOffset = rOff - effForOffset * c;

    // Normal: direction only, so it rotates by phi around the same local
    // tangent frame - no radius terms needed, unlike position. The
    // tangent frame's own orientation doesn't depend on aspect (aspect
    // only rescales how far along that frame a given offset sits), so
    // this stays the same rotation-by-phi as the isotropic case.
    float nLength = (axis == 0u) ? nrm.y : (axis == 1u) ? nrm.z : nrm.x;
    float nOffset = (axis == 0u) ? nrm.z : (axis == 1u) ? nrm.x : nrm.y;
    float newNLength = nLength * c - nOffset * s;
    float newNOffset = nLength * s + nOffset * c;

    if (axis == 0u) {
        pos.y = newLength; pos.z = newOffset;
        nrm.y = newNLength; nrm.z = newNOffset;
    } else if (axis == 1u) {
        pos.z = newLength; pos.x = newOffset;
        nrm.z = newNLength; nrm.x = newNOffset;
    } else {
        pos.x = newLength; pos.y = newOffset;
        nrm.x = newNLength; nrm.y = newNOffset;
    }
}

// Scales a cylinder wall/cap radially by different amounts at each end
// (bottomScale at local Y=-0.5, topScale at Y=+0.5, linearly interpolated
// between) - the frustum a lampshade needs, without a dedicated primitive.
// The wall's normal must tilt to stay perpendicular to the now-slanted
// surface rather than staying purely radial (derived from the surface
// tangents, see the design discussion); a cap's normal is already exactly
// (0,+-1,0) before this runs, so it's left alone - detected by its
// near-zero Y component, which no wall point ever has.
void applyTaper(PbdModifierGpu mod, inout vec3 pos, inout vec3 nrm) {
    float bottomScale = mod.params0.x;
    float topScale = mod.params0.y;
    float t = pos.y + 0.5; // 0 at the bottom, 1 at the top
    float scale = mix(bottomScale, topScale, t);

    bool isWallNormal = abs(nrm.y) < 0.01;
    if (isWallNormal) {
        float dr = (topScale - bottomScale) * 0.5; // d(radius)/d(height) for a linear taper
        vec3 radial = normalize(vec3(nrm.x, 0.0, nrm.z));
        nrm = normalize(vec3(radial.x, -dr, radial.z));
    }

    pos.x *= scale;
    pos.z *= scale;
}

void applyModifiers(PbdInstanceGpu inst, inout vec3 pos, inout vec3 nrm, vec3 worldScale) {
    for (uint i = 0u; i < inst.modifierCount; i++) {
        PbdModifierGpu mod = modifiers[inst.modifierStart + i];
        if (mod.type == MOD_BEND) {
            applyBend(mod, pos, nrm, worldScale);
        } else if (mod.type == MOD_SHEAR) {
            applyShear(mod, pos, nrm);
        } else if (mod.type == MOD_TAPER) {
            applyTaper(mod, pos, nrm);
        }
        // MOD_TWIST / MOD_CURVE: not wired up here yet.
    }
}

struct PbdMaterialGpu {
    vec4 baseColor;
    float shininess;
    float specularStrength;
    float textureLayer;
    float uvScale;
    float normalMapLayer;
    float roughnessMapLayer;
    float reflectivity;
    float transparency;
    float displacementLayer; // -1 if none, else layer in displacementTextures
    float displacementScale; // world-space height at a fully-white (1.0) texel; 0 = no visible effect even with a map assigned
};

layout(std430, binding = 4) readonly buffer MaterialBuffer {
    PbdMaterialGpu materials[];
};

layout(binding = 3) uniform sampler2DArray displacementTextures;

void main() {
    GpuPatch gpuPatch = patches[gl_PrimitiveID];
    PbdInstanceGpu inst = instances[gpuPatch.instanceIndex];
    vec2 uv = gl_TessCoord.xy;
    vUV = uv;

    vec3 pos;
    vec3 nrm;

    if (inst.type == TYPE_PLANE) {
        evalPlane(uv, pos, nrm);
    } else if (inst.type == TYPE_SPHERE) {
        evalSphere(uv, pos, nrm);
    } else if (inst.type == TYPE_CYLINDER) {
        if (gpuPatch.part == 0u)      evalCylinderWall(uv, pos, nrm);
        else if (gpuPatch.part == 1u) evalDisc(uv, 0.5, 1.0, pos, nrm);
        else                          evalDisc(uv, -0.5, -1.0, pos, nrm);
    } else if (inst.type == TYPE_CONE) {
        if (gpuPatch.part == 0u) evalConeWall(uv, pos, nrm);
        else                     evalDisc(uv, -0.5, -1.0, pos, nrm);
    } else if (inst.type == TYPE_DISC) {
        evalDisc(uv, 0.0, 1.0, pos, nrm);
    } else if (inst.type == TYPE_TORUS) {
        evalTorus(uv, pos, nrm);
    } else {
        vec3 smoothness = inst.params0.xyz;
        bool anySmooth = smoothness.x > 0.001 || smoothness.y > 0.001 || smoothness.z > 0.001;
        if (anySmooth) evalCubeFaceSmooth(uv, gpuPatch.part, smoothness, pos, nrm);
        else           evalCubeFaceFlat(uv, gpuPatch.part, pos, nrm);
    }

    mat4 world = worldTransforms[gpuPatch.instanceIndex];
    // Extracted before modifiers run (not just before the final position
    // transform below, where it used to live) specifically so applyBend
    // can see this instance's actual world-space scale - see its comment
    // for why that's necessary now, not just a nice-to-have.
    vec3 worldScale = vec3(length(world[0].xyz), length(world[1].xyz), length(world[2].xyz));

    applyModifiers(inst, pos, nrm, worldScale);

    // Displacement: actually moves the vertex (along its own, possibly
    // modifier-bent, local normal), not just a shading trick like the
    // normal map in the fragment shaders - real geometric detail needs a
    // real position change, which can only happen here, before the world
    // transform and before vLocalPos is captured for the cache.
    // world-space height comes from worldScale along the normal's own
    // dominant direction being awkward to define in general (the local
    // normal doesn't point along one axis), so scale is applied in local
    // units directly - displacementScale is authored with that in mind.
    PbdMaterialGpu dispMat = materials[inst.materialID];
    if (dispMat.displacementLayer >= 0.0) {
        float height = texture(displacementTextures, vec3(vUV * dispMat.uvScale, dispMat.displacementLayer)).r;
        pos += nrm * (height - 0.5) * dispMat.displacementScale;
    }

    vec4 worldPos = world * vec4(pos, 1.0);

    // Non-uniform scale -> normals need the inverse-transpose of the 3x3
    // part, not `world` directly (otherwise shading gets distorted).
    mat3 normalMatrix = transpose(inverse(mat3(world)));
    vNormal = normalize(normalMatrix * nrm);
    vMaterialID = inst.materialID;
    vWorldPos = worldPos.xyz;
    vLocalPos = pos;
    vLocalNormal = nrm;

    gl_Position = viewProj * worldPos;
}
