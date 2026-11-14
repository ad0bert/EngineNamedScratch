#version 330 core

// Cel shading (engine.kits.toon): the engine's standard material and lights, with the light falling in a few flat
// bands instead of a smooth gradient (the darkest one band of light). Emission is added on top, unbanded. The bands
// do not take ambient occlusion.
#include "lib/surface.glsl"

uniform float uBands;      // how many steps of light, e.g. 3
uniform float uSoftness;   // 0: hard steps .. 0.5: blurred edges between them

float luminance(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
  Surface s = standardSurface();
  if (s.unlit) {
    shade(s);
    return;
  }
  vec3 emissive = s.emissive;
  s.emissive = vec3(0.0);
  vec3 lit = lighting(s);
  // how much light arrives, relative to the surface's own colour, in bands
  float light = luminance(lit) / max(luminance(s.albedo), 1e-4);
  float bands = max(uBands, 1.0);
  float x = light * bands;
  float level = floor(x);
  float edge = smoothstep(0.5 - uSoftness, 0.5 + uSoftness, fract(x));
  float banded = max(level + edge, 1.0) / bands; // the darkest band is one band of light, not black
  gAmbient = vec3(0.0);
  writeColor(s.albedo * banded + emissive, s.alpha);
}
