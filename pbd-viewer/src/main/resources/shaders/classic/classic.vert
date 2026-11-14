#version 430 core

// Traditional path: real per-vertex attributes uploaded once into a VBO,
// unlike the PBD path's attributeless, GPU-generated geometry. This is
// the baseline the benchmark compares against.
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
// Declared unconditionally - see ClassicMeshRenderer's own doc on why a
// mesh with no UV data (the OBJ-benchmark path, container items) simply
// never enables this location rather than the shader needing a separate
// variant without it. A disabled attribute reads as (0,0) here, which is
// harmless since useTexture (classic.frag) is never true for a mesh that
// was never given a texture in the first place.
layout(location = 2) in vec2 inUV;
// A voxel-destruction mesh's own PER-VOXEL color (see
// VoxelMeshBuilder's own doc) - declared unconditionally, same
// reasoning as inUV just above: a mesh that was never given per-vertex
// color simply never enables this location (ClassicMeshRenderer's own
// choice, not a shader variant), reading as (0,0,0) harmlessly since
// useVertexColor (classic.frag) is never true for that case.
layout(location = 3) in vec3 inColor;

layout(location = 0) uniform mat4 viewProj;
layout(location = 4) uniform mat4 worldMatrix;
layout(location = 6) uniform vec2 uvScale;

out vec3 vNormal;
out vec3 vWorldPos;
out vec2 vUV;
out vec3 vColor;

void main() {
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    vWorldPos = worldPos.xyz;
    // mat3(worldMatrix) alone is only correct for a UNIFORM scale - under
    // a non-uniform one (routine here: a mesh instance's own scale.xyz
    // rarely matches on all three axes, e.g. a flattened roof panel)
    // it skews the normal's DIRECTION, not just its length, in a way
    // normalize() downstream can't fix - confirmed against a real
    // scene's own data: a 3-vertex/1-triangle mesh instance with
    // scale=(0.44, 1.0, 0.12), whose geometrically-correct local normal
    // (0,-1,0) came out visibly wrong once carried through this exact
    // multiply, matching a reported "renders solid black" triangle.
    // transpose(inverse(...)) is the standard, textbook-correct normal
    // matrix - GLSL's built-in inverse() (available on mat3 since
    // #version 150) makes computing it here straightforward, and this
    // pipeline's own vertex counts are far too small for the extra
    // per-vertex cost to matter.
    vNormal = transpose(inverse(mat3(worldMatrix))) * inNormal;
    vUV = inUV * uvScale;
    vColor = inColor;
    gl_Position = viewProj * worldPos;
}
