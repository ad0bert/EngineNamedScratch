#version 330 core

// 4 x 4 blur of the occlusion that keeps to surfaces at the same depth (the noise of the turned samples averages
// out, edges stay sharp).
in vec2 vUv;
uniform sampler2D uAo;
uniform sampler2D uDepth;
uniform vec2 uTexel;        // one texel of the occlusion texture
uniform mat4 uInvProj;
out float ao;

float viewZ(vec2 uv) {
  float d = texture(uDepth, uv).r;
  vec4 p = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
  return p.z / p.w;
}

void main() {
  float z = viewZ(vUv);
  float sum = 0.0;
  float weights = 0.0;
  for (int x = -2; x < 2; x++) {
    for (int y = -2; y < 2; y++) {
      vec2 uv = vUv + (vec2(x, y) + 0.5) * uTexel;
      float w = 1.0 / (1e-3 + abs(viewZ(uv) - z) * 10.0);
      sum += texture(uAo, uv).r * w;
      weights += w;
    }
  }
  ao = sum / weights;
}
