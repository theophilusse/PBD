#version 430 core

struct PbdMaterialGpu {
    vec4 baseColor;         // rgb used, a unused for now
    float shininess;        // Blinn-Phong exponent: low = matte, high = sharp highlight
    float specularStrength;
    float textureLayer;     // -1 if this material has no diffuse texture, else its layer in materialTextures
    float uvScale;          // tiling repeat factor across the surface, 1.0 = no tiling
    float normalMapLayer;   // -1 if no normal map, else its layer in normalTextures
    float roughnessMapLayer; // -1 if no roughness map, else its layer in roughnessTextures
    float reflectivity;     // 0 = no reflection, 1 = fully mirror-like (blended with the diffuse/specular result)
    float transparency;     // 0 = fully opaque, 1 = fully invisible
    float displacementLayer; // unused here - actual displacement happens in pbd.tese, before this stage; kept only so the struct layout matches what Java writes into the shared buffer
    float displacementScale; // unused here, same reason
};

layout(std430, binding = 4) readonly buffer MaterialBuffer {
    PbdMaterialGpu materials[];
};

layout(binding = 0) uniform sampler2DArray materialTextures;
layout(binding = 1) uniform sampler2DArray normalTextures;
layout(binding = 2) uniform sampler2DArray roughnessTextures;

layout(location = 0) in vec3 vNormal;
layout(location = 1) flat in uint vMaterialID;
layout(location = 2) in vec3 vWorldPos;
layout(location = 5) in vec2 vUV;

// Shared with the TCS (same name, same type, same location = same uniform,
// not a collision - see README for the lesson that motivated writing it
// this explicitly).
layout(location = 0) uniform vec3 cameraPos;
layout(location = 6) uniform vec3 lightDir;

out vec4 fragColor;

// A cheap stand-in for real environment reflection: no cubemap or
// render-to-texture involved, just the same day-sky-gradient shape
// SkydomeRenderer draws, evaluated for the reflected ray direction
// instead of the camera ray. Good enough for "glass catching a hint of
// sky color", not a substitute for a real reflection probe - there's no
// day/night or star sampling here, on purpose: a highlight glinting off
// a window shouldn't need the skydome's night texture bound to this
// pass too, and the extra realism wouldn't read as different at the
// small, blurred scale a Fresnel-weighted reflection contributes at.
vec3 cheapSkyReflection(vec3 dir) {
    float t = clamp(dir.y, 0.0, 1.0);
    vec3 horizonColor = vec3(0.75, 0.83, 0.90);
    vec3 zenithColor = vec3(0.15, 0.35, 0.70);
    return mix(horizonColor, zenithColor, smoothstep(0.0, 0.6, t));
}

// Screen-space derivative tangent reconstruction - no per-vertex tangent
// attribute needed. Checked in isolation before being trusted here: a
// flat region of a test normal map left shading unchanged (difference
// ~1/255) while a deliberately tilted region visibly changed it
// (difference ~48/255), confirming the reconstructed tangent basis
// actually carries the normal map's perturbation in the right direction,
// not just algebraically well-formed but pointing nowhere meaningful.
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
    PbdMaterialGpu mat = materials[vMaterialID];
    vec2 uv = vUV * mat.uvScale;

    vec3 n = normalize(vNormal);
    vec3 missingMapTint = vec3(0.0);
    if (mat.normalMapLayer >= 0.0) {
        n = applyNormalMap(n, vWorldPos, uv, mat.normalMapLayer);
    } else if (mat.normalMapLayer <= -1.5) {
        // -2 (see PbdRenderer.uploadMaterials): a normal map WAS
        // requested but never loaded. Not a full override like
        // texture's magenta - a flat-looking surface is a much smaller
        // problem than an unreadable one - but blended in visibly
        // enough that "the bump map isn't doing anything" has an actual
        // signal to go on instead of just looking plain.
        missingMapTint += vec3(0.0, 0.5, 0.5); // cyan
    }

    vec3 toLight = normalize(-lightDir);
    vec3 toEye = normalize(cameraPos - vWorldPos);
    vec3 halfVec = normalize(toLight + toEye);

    vec3 base = mat.baseColor.rgb;
    if (mat.textureLayer >= 0.0) {
        // Texture REPLACES baseColor here, not multiplied by it - a
        // color value is meant as the fallback for when there's no
        // texture, not an unconditional tint stacked on top of one.
        // Multiplying was silently crushing any texture toward black
        // whenever the author's color happened to be dark (which it
        // often legitimately is, authored as a neutral base ready to
        // receive a texture) - confirmed directly: an isolated GPU test
        // with a bright, easily-read texture and a realistic dark base
        // color reproduced exactly this near-black result under the old
        // multiply, while the texture sample itself was proven correct
        // in isolation. This also now matches materials.py's own
        // Blender node setup, which already links the Image Texture
        // node straight to Base Color rather than multiplying it in.
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

    // Roughness narrows/widens and dims/brightens the specular highlight -
    // rough surfaces (roughness -> 1) get a wide, dim highlight (low
    // effective shininess exponent, capped specular strength); smooth
    // surfaces (roughness -> 0) keep the material's own shininess/
    // specularStrength as authored. Sampling the map's red channel: these
    // are single-value grayscale maps, every channel already carries the
    // same data.
    float roughness = 0.0;
    if (mat.roughnessMapLayer >= 0.0) {
        roughness = texture(roughnessTextures, vec3(uv, mat.roughnessMapLayer)).r;
    } else if (mat.roughnessMapLayer <= -1.5) {
        missingMapTint += vec3(0.5, 0.5, 0.0); // yellow - distinguishable from normal's cyan if both are missing at once
    }
    float effShininess = mix(mat.shininess, 2.0, roughness);
    float effSpecular = mix(mat.specularStrength, mat.specularStrength * 0.3, roughness);

    float diffuse = max(dot(n, toLight), 0.0);
    float specAngle = max(dot(n, halfVec), 0.0);
    float specular = pow(specAngle, effShininess) * effSpecular;

    // Ambient floor was 0.15 flat - fine for a fixed, always-favorable
    // light direction, too dark now that lightDir tracks the real sun:
    // most surfaces aren't facing it most of the day. A sky-bounce term
    // (brighter, cooler for up-facing surfaces catching sky light;
    // dimmer, warmer for down-facing ones catching only ground bounce)
    // reads as outdoor daylight instead of a single harsh spotlight.
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
