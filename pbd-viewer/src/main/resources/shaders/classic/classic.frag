#version 430 core

in vec3 vNormal;
in vec3 vWorldPos;
in vec2 vUV;
in vec3 vColor;

layout(location = 1) uniform vec3 cameraPos;
layout(location = 2) uniform vec3 lightDir;
layout(location = 3) uniform vec3 baseColor;
layout(location = 5) uniform int useTexture; // 0 = flat baseColor (unchanged prior behavior), 1 = sample diffuseTexture
layout(location = 7) uniform int useVertexColor; // 1 = ignore useTexture/baseColor entirely, use this vertex's own vColor instead - a voxel-destruction mesh's per-voxel color (see VoxelMeshBuilder), which has no single shared color OR a shared UV mapping a texture sampler could use
layout(binding = 0) uniform sampler2D diffuseTexture; // explicit binding = fixed to texture unit 0 permanently, no application-side glUniform1i needed - see ClassicMeshRenderer.render()

out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 toLight = normalize(-lightDir);
    vec3 toEye = normalize(cameraPos - vWorldPos);
    vec3 halfVec = normalize(toLight + toEye);

    float diffuse = max(dot(n, toLight), 0.0);
    float specular = pow(max(dot(n, halfVec), 0.0), 32.0) * 0.35;

    // Same sky-based ambient as pbd.frag's own main tessellation
    // pipeline (identical formula, not just similar) - this pipeline's
    // own flat 0.15 constant used to sit well below pbd.frag's 0.22-0.28
    // floor, so the SAME material (a mesh instance vs an equivalent
    // procedural one) read as visibly, needlessly darker here whenever
    // diffuse was low - a normal-facing-away-from-the-sun surface with
    // a dark base color could end up reading as solid black on screen,
    // matching a reported "black triangle" on a mesh-type instance.
    float skyFactor = clamp(n.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 ambient = mix(vec3(0.28, 0.25, 0.22), vec3(0.40, 0.43, 0.50), skyFactor);

    vec3 surfaceColor = (useVertexColor == 1) ? vColor : ((useTexture == 1) ? texture(diffuseTexture, vUV).rgb : baseColor);
    vec3 color = surfaceColor * (ambient + 0.75 * diffuse) + vec3(specular);
    fragColor = vec4(color, 1.0);
}
