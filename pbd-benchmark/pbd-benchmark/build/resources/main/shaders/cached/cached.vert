#version 430 core

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUV;

layout(location = 0) uniform mat4 world;
layout(location = 1) uniform mat4 viewProj;

out vec3 vNormal;
out vec3 vWorldPos;
out vec2 vUV;

void main() {
    vec4 worldPos = world * vec4(inPosition, 1.0);
    mat3 normalMatrix = transpose(inverse(mat3(world)));
    vNormal = normalize(normalMatrix * inNormal);
    vWorldPos = worldPos.xyz;
    vUV = inUV;
    gl_Position = viewProj * worldPos;
}
