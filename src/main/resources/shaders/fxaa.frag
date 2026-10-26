#version 330 core

// Fast approximate anti-aliasing (after Timothy Lottes' FXAA 3.11, "quality 10"-like): finds the local edge
// direction from the luma (in alpha) and blends along it.
in vec2 vUv;

uniform sampler2D uImage;
uniform vec2 uTexel;
uniform int uEnabled;

out vec4 fragColor;

void main() {
  vec4 center = texture(uImage, vUv);
  if (uEnabled == 0) {
    fragColor = vec4(center.rgb, 1.0);
    return;
  }
  float lumaM = center.a;
  float lumaNW = texture(uImage, vUv + vec2(-1.0, -1.0) * uTexel).a;
  float lumaNE = texture(uImage, vUv + vec2(1.0, -1.0) * uTexel).a;
  float lumaSW = texture(uImage, vUv + vec2(-1.0, 1.0) * uTexel).a;
  float lumaSE = texture(uImage, vUv + vec2(1.0, 1.0) * uTexel).a;
  float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
  float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
  if (lumaMax - lumaMin < max(0.0312, lumaMax * 0.125)) {
    fragColor = vec4(center.rgb, 1.0); // no edge here
    return;
  }
  vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)), (lumaNW + lumaSW) - (lumaNE + lumaSE));
  float reduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * 0.125, 1.0 / 128.0);
  float scale = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
  dir = clamp(dir * scale, vec2(-8.0), vec2(8.0)) * uTexel;
  vec3 a = 0.5 * (texture(uImage, vUv + dir * (1.0 / 3.0 - 0.5)).rgb
      + texture(uImage, vUv + dir * (2.0 / 3.0 - 0.5)).rgb);
  vec3 b = a * 0.5 + 0.25 * (texture(uImage, vUv + dir * -0.5).rgb + texture(uImage, vUv + dir * 0.5).rgb);
  float lumaB = dot(b, vec3(0.299, 0.587, 0.114));
  fragColor = vec4(lumaB < lumaMin || lumaB > lumaMax ? a : b, 1.0);
}
