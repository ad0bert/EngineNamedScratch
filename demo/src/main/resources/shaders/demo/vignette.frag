#version 330 core

// The demo's post pass (display stage): darkens the corners of the image.
#include "lib/post.glsl"

uniform float uStrength;   // 0: off .. 1: black corners

void main() {
  vec3 c = texture(uImage, vUv).rgb;
  vec2 d = vUv - 0.5;
  float v = 1.0 - uStrength * smoothstep(0.2, 0.75, dot(d, d) * 2.0);
  fragColor = vec4(c * v, 1.0);
}
