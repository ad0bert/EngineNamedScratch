#version 330 core

// The demo's custom material: the engine's standard surface, with a glow that pulses over time. Everything else
// (lights, shadows, sky, transparency) comes from the engine's library.
#include "lib/surface.glsl"

uniform vec3 uGlowColor;   // linear
uniform float uGlowSpeed;  // pulses per second

void main() {
  Surface s = standardSurface();
  float pulse = 0.5 + 0.5 * sin(uTime * uGlowSpeed * 6.2831853);
  s.emissive += uGlowColor * pulse * pulse;
  shade(s);
}
