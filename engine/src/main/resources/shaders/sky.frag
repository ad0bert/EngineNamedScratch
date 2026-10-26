#version 330 core

#define PI 3.14159265

// The sky's radiance in a direction: a procedural gradient or an equirectangular image, optionally with a sun disc.
in vec3 vDir;

uniform int uSource;          // 0: gradient, 1: equirectangular image
uniform vec3 uZenith;         // linear radiance
uniform vec3 uHorizon;
uniform vec3 uGround;
uniform sampler2D uEquirect;
uniform float uEquirectLod;   // mip level that matches the target's texel size
uniform vec2 uRotation;       // cos, sin of the turn about +y (world -> sky)
uniform float uIntensity;
uniform int uSun;             // 1: draw the sun disc and its glow (background only, never baked)
uniform vec3 uSunDir;         // towards the sun, world space
uniform vec3 uSunColor;       // the light's colour * intensity
uniform float uSunCos;        // cos of the disc's angular radius
uniform float uSunBrightness;

out vec4 fragColor;

vec3 sky(vec3 d) {
  if (uSource == 1) {
    vec2 uv = vec2(atan(d.x, -d.z) / (2.0 * PI) + 0.5, acos(clamp(d.y, -1.0, 1.0)) / PI);
    return textureLod(uEquirect, uv, uEquirectLod).rgb;
  }
  if (d.y >= 0.0) {
    return mix(uHorizon, uZenith, pow(d.y, 0.45));
  }
  return mix(uHorizon, uGround, smoothstep(0.0, 0.06, -d.y));
}

void main() {
  vec3 d = normalize(vDir);
  vec3 s = vec3(uRotation.x * d.x + uRotation.y * d.z, d.y, -uRotation.y * d.x + uRotation.x * d.z);
  vec3 c = sky(s) * uIntensity;
  if (uSun == 1) {
    float cosAngle = dot(d, uSunDir);
    float glow = pow(max(cosAngle, 0.0), 200.0) * 0.6 + pow(max(cosAngle, 0.0), 12.0) * 0.08;
    c += uSunColor * glow;
    // a soft edge one hundredth of the radius wide keeps the disc from aliasing
    float edge = 1.0 - uSunCos;
    c += uSunColor * uSunBrightness * smoothstep(uSunCos - 0.02 * edge, uSunCos + 0.02 * edge, cosAngle);
  }
  fragColor = vec4(c, 1.0);
}
