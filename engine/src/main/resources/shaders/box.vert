#version 330 core

// Unit cube corners (0..1) stretched over an axis-aligned box; used for occlusion queries only.
layout(location = 0) in vec3 aPos;

uniform mat4 uViewProj;
uniform vec3 uMin;
uniform vec3 uMax;

void main() {
  gl_Position = uViewProj * vec4(mix(uMin, uMax, aPos), 1.0);
}
