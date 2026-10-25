#version 330 core

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUv;

uniform sampler2D uTexture;
uniform vec4 uColor;
uniform vec3 uToLight;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform vec3 uCameraPos;
uniform float uSpecular;
uniform float uShininess;
uniform int uUnlit;

out vec4 fragColor;

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
  vec3 l = normalize(uToLight);
  float diff = max(dot(n, l), 0.0);
  vec3 v = normalize(uCameraPos - vWorldPos);
  vec3 h = normalize(l + v);
  float spec = diff > 0.0 ? pow(max(dot(n, h), 0.0), uShininess) * uSpecular : 0.0;
  vec3 lit = albedo.rgb * (uAmbient + uLightColor * diff) + uLightColor * spec;
  fragColor = vec4(lit, albedo.a);
}
