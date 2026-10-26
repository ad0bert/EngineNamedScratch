#version 330 core

// Upsamples with a 3x3 tent filter; drawn with additive blending onto the next larger level.
in vec2 vUv;

uniform sampler2D uSource;
uniform vec2 uTexel;   // 1 / source size
uniform float uRadius; // in source texels

out vec4 fragColor;

void main() {
  vec2 t = uTexel * uRadius;
  vec3 sum = texture(uSource, vUv).rgb * 4.0;
  sum += (texture(uSource, vUv + vec2(-t.x, 0)).rgb + texture(uSource, vUv + vec2(t.x, 0)).rgb
      + texture(uSource, vUv + vec2(0, -t.y)).rgb + texture(uSource, vUv + vec2(0, t.y)).rgb) * 2.0;
  sum += texture(uSource, vUv + vec2(-t.x, -t.y)).rgb + texture(uSource, vUv + vec2(t.x, -t.y)).rgb
      + texture(uSource, vUv + vec2(-t.x, t.y)).rgb + texture(uSource, vUv + vec2(t.x, t.y)).rgb;
  fragColor = vec4(sum / 16.0, 1.0);
}
