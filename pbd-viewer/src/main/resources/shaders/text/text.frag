#version 430 core

in vec2 vUv;

layout(location = 4) uniform sampler2D fontAtlas;
layout(location = 5) uniform vec3 textColor;

out vec4 fragColor;

void main() {
    float a = texture(fontAtlas, vUv).r;
    if (a < 0.5) discard;
    fragColor = vec4(textColor, 1.0);
}
