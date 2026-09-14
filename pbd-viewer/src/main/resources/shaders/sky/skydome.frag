#version 430 core

in vec2 vScreenPos;
out vec4 fragColor;

layout(location = 0) uniform mat4 invViewProj;
layout(location = 1) uniform vec3 sunDirection;   // normalized, world Y-up - see SolarCalculator.toDirection
layout(location = 2) uniform float dayFactor;      // 0=full night, 1=full day; computed CPU-side from sun altitude (civil twilight band)
layout(location = 3) uniform float starRotation;   // radians; rotates the night sky over time (hour angle + a slow seasonal drift)
layout(location = 4) uniform int hasNightTexture;  // 0/1 - falls back to a plain dark sky if no texture was loaded (see README)

layout(binding = 0) uniform sampler2D nightSkyTex;

const float PI = 3.14159265359;
const float TAU = 6.28318530718;

// Reconstructs the world-space view ray for this pixel from the inverse
// view-projection matrix - near/far NDC points transformed back to world
// space, direction is just their difference. Avoids needing to also pass
// the camera position separately.
vec3 reconstructRayDir(vec2 ndc) {
    vec4 nearP = invViewProj * vec4(ndc, -1.0, 1.0);
    vec4 farP  = invViewProj * vec4(ndc, 1.0, 1.0);
    nearP /= nearP.w;
    farP  /= farP.w;
    return normalize(farP.xyz - nearP.xyz);
}

void main() {
    vec3 dir = reconstructRayDir(vScreenPos);

    // Day: a plain vertical gradient, brighter/paler near the horizon,
    // deeper blue toward the zenith - no clouds, per the brief. Below the
    // horizon fades toward a dim, neutral tone instead of continuing the
    // sky gradient (which would look wrong underground/indoors anyway).
    float t = clamp(dir.y, 0.0, 1.0);
    vec3 horizonColor = vec3(0.75, 0.83, 0.90);
    vec3 zenithColor  = vec3(0.15, 0.35, 0.70);
    vec3 dayColor = mix(horizonColor, zenithColor, smoothstep(0.0, 0.6, t));
    if (dir.y < 0.0) {
        dayColor = mix(horizonColor, vec3(0.20, 0.20, 0.22), clamp(-dir.y * 2.0, 0.0, 1.0));
    }

    // Night: equirectangular-sampled starfield, rotated over time so the
    // sky visibly turns through the night (and slowly shifts with the
    // season) rather than staying static.
    vec3 nightColor = vec3(0.02, 0.02, 0.05);
    if (hasNightTexture != 0) {
        float c = cos(starRotation), s = sin(starRotation);
        vec3 rdir = vec3(dir.x * c - dir.z * s, dir.y, dir.x * s + dir.z * c);
        float u = atan(rdir.x, -rdir.z) / TAU + 0.5;
        float v = acos(clamp(rdir.y, -1.0, 1.0)) / PI;
        nightColor = texture(nightSkyTex, vec2(u, v)).rgb;
    }

    vec3 skyColor = mix(nightColor, dayColor, dayFactor);

    // Sun: a small bright disc plus a soft glow, faded in alongside
    // daylight so it doesn't show as a bright dot through a night sky
    // that's meant to be lit only by stars.
    float sunDot = dot(dir, normalize(sunDirection));
    float sunDisc = smoothstep(0.9985, 0.9995, sunDot);
    float sunGlow = smoothstep(0.95, 0.999, sunDot) * 0.3;
    vec3 sunColor = vec3(1.0, 0.95, 0.85);
    skyColor += sunColor * (sunDisc + sunGlow) * clamp(dayFactor + 0.15, 0.0, 1.0);

    fragColor = vec4(skyColor, 1.0);
}
