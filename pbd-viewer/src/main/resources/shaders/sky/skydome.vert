#version 430 core

// Standard "one triangle covers the screen" trick: for vertexID 0,1,2 this
// produces (-1,-1), (3,-1), (-1,3) - a triangle larger than the viewport
// but with no diagonal seam the way two triangles from a quad would need.
out vec2 vScreenPos;

void main() {
    vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vScreenPos = pos * 2.0 - 1.0;
    gl_Position = vec4(vScreenPos, 0.0, 1.0);
}
