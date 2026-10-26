#version 330 core

// Weighted blended order-independent transparency (McGuire & Bavoil 2013), resolve: the weighted average colour of
// all transparent layers, covering the opaque image by 1 - (product of their transparencies).
in vec2 vUv;

uniform sampler2D uAccum;   // rgb: sum of colour * alpha * weight, a: product of (1 - alpha)
uniform sampler2D uWeights; // r: sum of alpha * weight

out vec4 fragColor;

void main() {
  vec4 accum = texture(uAccum, vUv);
  float revealage = accum.a;
  if (revealage >= 0.9999) {
    discard; // nothing transparent here
  }
  float weights = max(texture(uWeights, vUv).r, 1e-5);
  fragColor = vec4(accum.rgb / weights, 1.0 - revealage);
}
