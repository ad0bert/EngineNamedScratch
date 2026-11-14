#version 330 core

// The main pass for every material without a vertex shader of its own.
#include "lib/vertex.glsl"

void main() {
  emit(aPos, aNormal);
}
