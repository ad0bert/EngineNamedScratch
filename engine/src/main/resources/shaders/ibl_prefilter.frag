#version 330 core

#define PI 3.14159265

// Specular pre-filtering for one cube texel and roughness (split-sum approximation, view = normal = reflection):
// GGX importance sampling, each sample read from the environment mip whose texels cover about its share of the
// lobe (filtered importance sampling), so few samples give a smooth result.
in vec3 vDir;

uniform samplerCube uEnv;
uniform float uRoughness;
uniform float uEnvSize;       // edge length of the environment's level 0
uniform float uMaxLod;

out vec4 fragColor;

const uint SAMPLES = 96u;

float radicalInverse(uint bits) {
  bits = (bits << 16u) | (bits >> 16u);
  bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
  bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
  bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
  bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
  return float(bits) * 2.3283064365386963e-10;
}

void main() {
  vec3 n = normalize(vDir);
  if (uRoughness < 0.01) {
    fragColor = vec4(textureLod(uEnv, n, 0.0).rgb, 1.0);
    return;
  }
  vec3 up = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
  vec3 tx = normalize(cross(up, n));
  vec3 ty = cross(n, tx);
  float a = uRoughness * uRoughness;
  float a2 = a * a;
  float texel = 4.0 * PI / (6.0 * uEnvSize * uEnvSize); // solid angle of one environment texel
  vec3 sum = vec3(0.0);
  float weight = 0.0;
  for (uint i = 0u; i < SAMPLES; i++) {
    vec2 xi = vec2(float(i) / float(SAMPLES), radicalInverse(i));
    float phi = 2.0 * PI * xi.x;
    float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a2 - 1.0) * xi.y));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 h = tx * (cos(phi) * sinTheta) + ty * (sin(phi) * sinTheta) + n * cosTheta;
    vec3 l = 2.0 * dot(n, h) * h - n;
    float ndotl = dot(n, l);
    if (ndotl > 0.0) {
      float ndoth = max(dot(n, h), 0.0);
      float dd = ndoth * ndoth * (a2 - 1.0) + 1.0;
      float pdf = a2 / (PI * dd * dd) / 4.0 + 1e-4; // D * ndoth / (4 vdoth), with v = n
      float lod = clamp(0.5 * log2(1.0 / (float(SAMPLES) * pdf) / texel) + 1.0, 0.0, uMaxLod);
      sum += textureLod(uEnv, l, lod).rgb * ndotl;
      weight += ndotl;
    }
  }
  fragColor = vec4(sum / max(weight, 1e-4), 1.0);
}
