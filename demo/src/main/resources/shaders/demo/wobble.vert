#version 330 core

// The demo's custom vertex shader: the mesh breathes in and out (scaled about its origin, so meshes with split
// normals, like a box, keep their faces together).
#include "lib/vertex.glsl"

uniform float uWobble;     // how much bigger at most, 0.05 = 5 %

void main() {
  emit(aPos * (1.0 + uWobble * sin(uTime * 3.0)), aNormal);
}
