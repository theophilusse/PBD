#version 430 core

// "Attributeless" rendering: each patch has only a dummy control point.
// All the actual geometry is generated further down the pipeline (TES)
// from gl_PrimitiveID, which indexes the patch buffer - this vertex
// shader has nothing meaningful to pass through.
void main() {
    gl_Position = vec4(0.0);
}
