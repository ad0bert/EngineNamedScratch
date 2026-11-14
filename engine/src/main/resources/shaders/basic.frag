#version 330 core

// The main pass for every material without a shader of its own: the standard material, lit.
#include "lib/surface.glsl"

void main() {
  shade(standardSurface());
}
