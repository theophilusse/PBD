#version 430 core

in vec3 vNormal;
in vec3 vWorldPos;

layout(location = 1) uniform vec3 cameraPos;
layout(location = 2) uniform vec3 lightDir;
layout(location = 3) uniform vec3 baseColor;

out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 toLight = normalize(-lightDir);
    vec3 toEye = normalize(cameraPos - vWorldPos);
    vec3 halfVec = normalize(toLight + toEye);

    float diffuse = max(dot(n, toLight), 0.0);
    float specular = pow(max(dot(n, halfVec), 0.0), 32.0) * 0.35;

    vec3 color = baseColor * (0.15 + 0.85 * diffuse) + vec3(specular);
    fragColor = vec4(color, 1.0);
}
