#version 330 core

// Ink outlines (engine.kits.toon) where the depth jumps: silhouettes and creases between objects.
#include "lib/post.glsl"

uniform vec3 uColor;       // the ink, in the stage's colours
uniform float uWidth;      // pixels
uniform float uThreshold;  // how big a jump in distance (relative to the distance) is an edge, e.g. 0.05

float distanceAt(vec2 uv) {
  return -viewPosition(uv).z;
}

void main() {
  vec3 c = texture(uImage, vUv).rgb;
  float d = distanceAt(vUv);
  vec2 o = uTexel * uWidth;
  float jump = max(max(abs(distanceAt(vUv + vec2(o.x, 0.0)) - d), abs(distanceAt(vUv - vec2(o.x, 0.0)) - d)),
      max(abs(distanceAt(vUv + vec2(0.0, o.y)) - d), abs(distanceAt(vUv - vec2(0.0, o.y)) - d)));
  float edge = smoothstep(uThreshold, uThreshold * 2.0, jump / max(d, 1e-3));
  fragColor = vec4(mix(c, uColor, edge), 1.0);
}
