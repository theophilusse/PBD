#version 430 core

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
    float displacementLayer; // unused here (see pbd.frag) - struct layout must match what Java writes
    float displacementScale;
};

layout(std430, binding = 4) readonly buffer MaterialBuffer {
    PbdMaterialGpu materials[];
};

layout(binding = 0) uniform sampler2DArray materialTextures;
layout(binding = 1) uniform sampler2DArray normalTextures;
layout(binding = 2) uniform sampler2DArray roughnessTextures;

in vec3 vNormal;
in vec3 vWorldPos;
in vec2 vUV;

layout(location = 2) uniform vec3 cameraPos;
layout(location = 3) uniform vec3 lightDir;
layout(location = 4) uniform uint materialID;

out vec4 fragColor;

vec3 cheapSkyReflection(vec3 dir) {
    float t = clamp(dir.y, 0.0, 1.0);
    vec3 horizonColor = vec3(0.75, 0.83, 0.90);
    vec3 zenithColor = vec3(0.15, 0.35, 0.70);
    return mix(horizonColor, zenithColor, smoothstep(0.0, 0.6, t));
}

vec3 applyNormalMap(vec3 N, vec3 worldPos, vec2 uv, float layer) {
    vec3 dp1 = dFdx(worldPos), dp2 = dFdy(worldPos);
    vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, N);
    vec3 dp1perp = cross(N, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    float invmax = inversesqrt(max(max(dot(T, T), dot(B, B)), 1e-8));
    T *= invmax;
    B *= invmax;

    vec3 tsNormal = texture(normalTextures, vec3(uv, layer)).rgb * 2.0 - 1.0;
    return normalize(tsNormal.x * T + tsNormal.y * B + tsNormal.z * N);
}

void main() {
    PbdMaterialGpu mat = materials[materialID];
    vec2 uv = vUV * mat.uvScale;

    vec3 n = normalize(vNormal);
    vec3 missingMapTint = vec3(0.0);
    if (mat.normalMapLayer >= 0.0) {
        n = applyNormalMap(n, vWorldPos, uv, mat.normalMapLayer);
    } else if (mat.normalMapLayer <= -1.5) {
        missingMapTint += vec3(0.0, 0.5, 0.5); // cyan - see pbd.frag's comment
    }

    vec3 toLight = normalize(-lightDir);
    vec3 toEye = normalize(cameraPos - vWorldPos);
    vec3 halfVec = normalize(toLight + toEye);

    vec3 base = mat.baseColor.rgb;
    if (mat.textureLayer >= 0.0) {
        // Texture replaces baseColor when present - see pbd.frag's
        // comment for why the previous multiply was wrong.
        base = texture(materialTextures, vec3(uv, mat.textureLayer)).rgb;
    } else if (mat.textureLayer <= -1.5) {
        // -2 specifically (see PbdRenderer.uploadMaterials): a texture
        // WAS requested here but the file never loaded - bright magenta
        // so that's immediately obvious, rather than rendering
        // identically to an ordinary plain-color material and leaving
        // "why does this look wrong" with no visible clue which of the
        // two it even is.
        base = vec3(1.0, 0.0, 1.0);
    }

    float roughness = 0.0;
    if (mat.roughnessMapLayer >= 0.0) {
        roughness = texture(roughnessTextures, vec3(uv, mat.roughnessMapLayer)).r;
    } else if (mat.roughnessMapLayer <= -1.5) {
        missingMapTint += vec3(0.5, 0.5, 0.0); // yellow
    }
    float effShininess = mix(mat.shininess, 2.0, roughness);
    float effSpecular = mix(mat.specularStrength, mat.specularStrength * 0.3, roughness);

    float diffuse = max(dot(n, toLight), 0.0);
    float specAngle = max(dot(n, halfVec), 0.0);
    float specular = pow(specAngle, effShininess) * effSpecular;

    float skyFactor = clamp(n.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 ambient = mix(vec3(0.28, 0.25, 0.22), vec3(0.40, 0.43, 0.50), skyFactor);
    vec3 color = base * (ambient + 0.75 * diffuse) + vec3(specular);

    if (mat.reflectivity > 0.0) {
        vec3 reflectDir = reflect(-toEye, n);
        vec3 reflection = cheapSkyReflection(reflectDir);
        color = mix(color, reflection, mat.reflectivity);
    }

    color = mix(color, missingMapTint, 0.4 * min(1.0, length(missingMapTint) * 2.0));
    fragColor = vec4(color, 1.0 - mat.transparency);
}
