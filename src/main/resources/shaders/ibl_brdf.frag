#version 330 core

#define PI 3.14159265

// The split-sum BRDF table: for (n . v, roughness), the scale and bias that turn F0 into the specular reflectance
// of a GGX surface under uniform light (Karis 2013). Independent of the sky; made once.
in vec2 vUv;

out vec4 fragColor;

const uint SAMPLES = 256u;

float radicalInverse(uint bits) {
  bits = (bits << 16u) | (bits >> 16u);
  bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
  bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
  bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
  bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
  return float(bits) * 2.3283064365386963e-10;
}

float smithG1(float ndotx, float k) {
  return ndotx / (ndotx * (1.0 - k) + k);
}

void main() {
  float ndotv = max(vUv.x, 1e-3);
  float roughness = clamp(vUv.y, 0.03, 1.0);
  vec3 v = vec3(sqrt(1.0 - ndotv * ndotv), 0.0, ndotv);
  float a = roughness * roughness;
  float a2 = a * a;
  float k = a / 2.0; // the image-based lighting remapping of the Schlick-GGX k
  float scale = 0.0;
  float bias = 0.0;
  for (uint i = 0u; i < SAMPLES; i++) {
    vec2 xi = vec2(float(i) / float(SAMPLES), radicalInverse(i));
    float phi = 2.0 * PI * xi.x;
    float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a2 - 1.0) * xi.y));
    float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
    vec3 h = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
    vec3 l = 2.0 * dot(v, h) * h - v;
    float ndotl = max(l.z, 0.0);
    float ndoth = max(h.z, 0.0);
    float vdoth = max(dot(v, h), 0.0);
    if (ndotl > 0.0) {
      float g = smithG1(ndotv, k) * smithG1(ndotl, k);
      float visibility = g * vdoth / max(ndoth * ndotv, 1e-6);
      float fc = pow(1.0 - vdoth, 5.0);
      scale += (1.0 - fc) * visibility;
      bias += fc * visibility;
    }
  }
  fragColor = vec4(scale / float(SAMPLES), bias / float(SAMPLES), 0.0, 1.0);
}
