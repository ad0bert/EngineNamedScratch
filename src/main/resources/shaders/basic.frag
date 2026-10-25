#version 330 core

#define MAX_LIGHTS 8

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUv;
in vec4 vShadowPos;

uniform sampler2D uTexture;
uniform sampler2DShadow uShadowMap;
uniform vec4 uColor;
uniform vec3 uAmbient;
uniform vec3 uCameraPos;
uniform float uSpecular;
uniform float uShininess;
uniform int uUnlit;

uniform int uLightCount;
uniform vec4 uLightPos[MAX_LIGHTS];   // xyz: direction towards the light (w=0) or position (w=1)
uniform vec4 uLightColor[MAX_LIGHTS]; // rgb: colour * intensity, a: range (point lights)
uniform int uShadowEnabled;           // light 0 casts shadows
uniform float uShadowTexel;

out vec4 fragColor;

float shadowFactor(vec3 n, vec3 l) {
  vec3 p = vShadowPos.xyz / vShadowPos.w * 0.5 + 0.5;
  if (p.z > 1.0 || p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) {
    return 1.0;
  }
  float bias = max(0.002 * (1.0 - dot(n, l)), 0.0005);
  float sum = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      sum += texture(uShadowMap, vec3(p.xy + vec2(x, y) * uShadowTexel, p.z - bias));
    }
  }
  return sum / 9.0;
}

void main() {
  vec4 albedo = texture(uTexture, vUv) * uColor;
  if (albedo.a < 0.01) {
    discard;
  }
  if (uUnlit == 1) {
    fragColor = albedo;
    return;
  }
  vec3 n = normalize(vNormal);
  // light both sides so un-culled thin geometry never goes black
  if (!gl_FrontFacing) {
    n = -n;
  }
  vec3 v = normalize(uCameraPos - vWorldPos);
  vec3 diffuse = vec3(0.0);
  vec3 specular = vec3(0.0);
  for (int i = 0; i < uLightCount; i++) {
    vec3 l;
    float atten = 1.0;
    if (uLightPos[i].w < 0.5) {
      l = normalize(uLightPos[i].xyz);
    } else {
      vec3 d = uLightPos[i].xyz - vWorldPos;
      float dist = length(d);
      l = d / max(dist, 1e-4);
      float x = clamp(1.0 - (dist * dist) / (uLightColor[i].a * uLightColor[i].a), 0.0, 1.0);
      atten = x * x;
    }
    float diff = max(dot(n, l), 0.0);
    float shadow = (i == 0 && uShadowEnabled == 1 && diff > 0.0) ? shadowFactor(n, l) : 1.0;
    vec3 c = uLightColor[i].rgb * atten * shadow;
    diffuse += c * diff;
    if (diff > 0.0) {
      vec3 h = normalize(l + v);
      specular += c * pow(max(dot(n, h), 0.0), uShininess) * uSpecular;
    }
  }
  vec3 lit = albedo.rgb * (uAmbient + diffuse) + specular;
  fragColor = vec4(lit, albedo.a);
}
