#version 430 core

layout(location = 0) uniform vec2 screenSize;
layout(location = 1) uniform vec2 charPos;   // top-left corner, in pixels
layout(location = 2) uniform vec2 charSize;  // in pixels
layout(location = 3) uniform vec4 uvRect;    // u0, v0, u1, v1 in the atlas

out vec2 vUv;

void main() {
    float lx = float(gl_VertexID & 1);
    float ly = float((gl_VertexID >> 1) & 1);

    vec2 screenPos = charPos + vec2(lx, ly) * charSize;
    vec2 ndc = vec2(
        (screenPos.x / screenSize.x) * 2.0 - 1.0,
        1.0 - (screenPos.y / screenSize.y) * 2.0
    );

    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = mix(uvRect.xy, uvRect.zw, vec2(lx, ly));
}
