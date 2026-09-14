#version 430 core

// Traditional path: real per-vertex attributes uploaded once into a VBO,
// unlike the PBD path's attributeless, GPU-generated geometry. This is
// the baseline the benchmark compares against.
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;

layout(location = 0) uniform mat4 viewProj;
layout(location = 4) uniform mat4 worldMatrix;

out vec3 vNormal;
out vec3 vWorldPos;

void main() {
    vec4 worldPos = worldMatrix * vec4(inPosition, 1.0);
    vWorldPos = worldPos.xyz;
    vNormal = mat3(worldMatrix) * inNormal;
    gl_Position = viewProj * worldPos;
}
