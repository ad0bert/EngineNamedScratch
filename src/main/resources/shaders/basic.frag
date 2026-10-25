#version 330 core

#define MAX_LIGHTS 8
#define MAX_CASCADES 4

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUv;

uniform sampler2D uTexture;
uniform sampler2D uNormalMap;
uniform sampler2DArrayShadow uShadowMap;
uniform vec4 uColor;
uniform vec3 uAmbient;
uniform vec3 uCameraPos;
uniform float uSpecular;
uniform float uShininess;
uniform int uUnlit;

uniform int uHasNormalMap;
uniform float uNormalScale;
uniform float uNormalFlipY;

uniform int uLightCount;
uniform vec4 uLightPos[MAX_LIGHTS];   // xyz: direction towards the light (w=0) or position (w=1)
uniform vec4 uLightColor[MAX_LIGHTS]; // rgb: colour * intensity, a: range (point lights)

// cascaded shadows for light 0
uniform int uCascadeCount;                     // 0: no shadows
uniform mat4 uCascadeMatrix[MAX_CASCADES];     // world -> light clip space
uniform float uCascadeSplit[MAX_CASCADES];     // far distance (view depth) of each cascade
uniform float uCascadeTexel[MAX_CASCADES];     // world size of one shadow texel
uniform float uShadowTexel;                    // 1 / shadow map size
uniform int uDebugCascades;

out vec4 fragColor;

// Perturbs the normal with a tangent-space normal map. The tangent frame comes from screen-space derivatives of the
// position and uv, so meshes need no tangent attribute.
vec3 applyNormalMap(vec3 n) {
  vec3 dp1 = dFdx(vWorldPos);
  vec3 dp2 = dFdy(vWorldPos);
  vec2 duv1 = dFdx(vUv);
  vec2 duv2 = dFdy(vUv);
  vec3 dp2perp = cross(dp2, n);
  vec3 dp1perp = cross(n, dp1);
  vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
  vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
  float m = max(dot(t, t), dot(b, b));
  if (m < 1e-20) {
    return n; // no usable uv derivatives (e.g. a mesh without texture coordinates)
  }
  float invmax = inversesqrt(m);
  vec3 map = texture(uNormalMap, vUv).xyz * 2.0 - 1.0;
  map.y *= uNormalFlipY;
  map.xy *= uNormalScale;
  return normalize(mat3(t * invmax, b * invmax, n) * map);
}

float sampleCascade(int c, vec3 n, float ndotl) {
  // push the lookup position along the normal by about a texel: removes acne without a depth bias that detaches shadows
  vec3 p = vWorldPos + n * uCascadeTexel[c] * (1.0 + 2.0 * (1.0 - ndotl));
  vec4 s = uCascadeMatrix[c] * vec4(p, 1.0);
  vec3 q = s.xyz / s.w * 0.5 + 0.5;
  if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0 || q.z > 1.0) {
    return 1.0;
  }
  float sum = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      sum += texture(uShadowMap, vec4(q.xy + vec2(x, y) * uShadowTexel, float(c), q.z - 0.0004));
    }
  }
  return sum / 9.0;
}

float shadowFactor(vec3 n, vec3 l, out int cascade) {
  float depth = 1.0 / gl_FragCoord.w; // view-space distance of this fragment
  int c = 0;
  while (c < uCascadeCount - 1 && depth > uCascadeSplit[c]) {
    c++;
  }
  cascade = c;
  float ndotl = max(dot(n, l), 0.0);
  float s = sampleCascade(c, n, ndotl);
  if (c < uCascadeCount - 1) { // blend into the next (coarser) cascade near the boundary
    float start = c == 0 ? 0.0 : uCascadeSplit[c - 1];
    float len = uCascadeSplit[c] - start;
    float t = smoothstep(uCascadeSplit[c] - 0.1 * len, uCascadeSplit[c], depth);
    if (t > 0.0) {
      s = mix(s, sampleCascade(c + 1, n, ndotl), t);
    }
  }
  float last = uCascadeSplit[uCascadeCount - 1];
  return mix(s, 1.0, smoothstep(0.9 * last, last, depth)); // fade shadows out at the end of the range
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
  if (uHasNormalMap == 1) {
    n = applyNormalMap(n);
  }
  vec3 v = normalize(uCameraPos - vWorldPos);
  vec3 diffuse = vec3(0.0);
  vec3 specular = vec3(0.0);
  int cascade = 0;
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
    float shadow = 1.0;
    if (i == 0 && uCascadeCount > 0 && diff > 0.0) {
      // shadows use the geometric normal: the normal map must not change where a surface is in shadow
      shadow = shadowFactor(normalize(vNormal) * (gl_FrontFacing ? 1.0 : -1.0), l, cascade);
    }
    vec3 c = uLightColor[i].rgb * atten * shadow;
    diffuse += c * diff;
    if (diff > 0.0) {
      vec3 h = normalize(l + v);
      specular += c * pow(max(dot(n, h), 0.0), uShininess) * uSpecular;
    }
  }
  vec3 lit = albedo.rgb * (uAmbient + diffuse) + specular;
  if (uDebugCascades == 1 && uCascadeCount > 0) {
    vec3 tint[4] = vec3[4](vec3(1.0, 0.4, 0.4), vec3(0.4, 1.0, 0.4), vec3(0.4, 0.5, 1.0), vec3(1.0, 1.0, 0.4));
    lit = mix(lit, lit * tint[cascade], 0.5);
  }
  fragColor = vec4(lit, albedo.a);
}
