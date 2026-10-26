#version 330 core

// Downsamples by two with a 13-tap filter (Jimenez, "Next generation post processing in Call of Duty"). The first
// pass also keeps only what is brighter than the threshold, with a soft knee.
in vec2 vUv;

uniform sampler2D uSource;
uniform vec2 uTexel;       // 1 / source size
uniform int uThreshold;    // 1 on the first pass
uniform float uLimit;      // brightness where bloom starts
uniform float uKnee;

out vec4 fragColor;

vec3 prefilter(vec3 c) {
  float brightness = max(c.r, max(c.g, c.b));
  float soft = clamp(brightness - uLimit + uKnee, 0.0, 2.0 * uKnee);
  soft = soft * soft / (4.0 * uKnee + 1e-5);
  float contribution = max(soft, brightness - uLimit) / max(brightness, 1e-5);
  return c * contribution;
}

void main() {
  vec2 t = uTexel;
  vec3 a = texture(uSource, vUv + t * vec2(-2, 2)).rgb;
  vec3 b = texture(uSource, vUv + t * vec2(0, 2)).rgb;
  vec3 c = texture(uSource, vUv + t * vec2(2, 2)).rgb;
  vec3 d = texture(uSource, vUv + t * vec2(-2, 0)).rgb;
  vec3 e = texture(uSource, vUv).rgb;
  vec3 f = texture(uSource, vUv + t * vec2(2, 0)).rgb;
  vec3 g = texture(uSource, vUv + t * vec2(-2, -2)).rgb;
  vec3 h = texture(uSource, vUv + t * vec2(0, -2)).rgb;
  vec3 i = texture(uSource, vUv + t * vec2(2, -2)).rgb;
  vec3 j = texture(uSource, vUv + t * vec2(-1, 1)).rgb;
  vec3 k = texture(uSource, vUv + t * vec2(1, 1)).rgb;
  vec3 l = texture(uSource, vUv + t * vec2(-1, -1)).rgb;
  vec3 m = texture(uSource, vUv + t * vec2(1, -1)).rgb;
  vec3 color = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
  if (uThreshold == 1) {
    color = prefilter(color);
  }
  fragColor = vec4(max(color, vec3(0.0)), 1.0);
}
