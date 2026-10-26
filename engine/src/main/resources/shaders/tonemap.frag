#version 330 core

// HDR scene + bloom -> exposure, ACES filmic curve, sRGB encoding. The luma goes to alpha for FXAA.
in vec2 vUv;

uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength; // 0: off
uniform float uExposure;

out vec4 fragColor;

vec3 aces(vec3 x) { // Narkowicz's fit of the ACES reference rendering transform
  return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 toSrgb(vec3 c) {
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
  vec3 hdr = texture(uScene, vUv).rgb;
  if (uBloomStrength > 0.0) {
    hdr += texture(uBloom, vUv).rgb * uBloomStrength;
  }
  vec3 ldr = toSrgb(aces(hdr * uExposure));
  fragColor = vec4(ldr, dot(ldr, vec3(0.299, 0.587, 0.114)));
}
