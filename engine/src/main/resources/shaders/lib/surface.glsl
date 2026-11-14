// The engine's standard material for fragment shaders: the Material's colour, textures (albedo, normal, specular,
// emissive, height for parallax, metal-roughness), UV transform and lighting model, as a Surface.
//
//   #include "lib/surface.glsl"
//   void main() {
//     Surface s = standardSurface();     // discards below the material's alpha cut-off
//     s.emissive += ...;                 // change what the material says
//     shade(s);
//   }
//
// gUv is the material's UV after its transform and parallax, for sampling a shader's own textures in step.

#include "lighting.glsl"

uniform sampler2D uTexture;
uniform sampler2D uNormalMap;
uniform sampler2D uSpecularMap;
uniform sampler2D uEmissiveMap;
uniform sampler2D uHeightMap;
uniform sampler2D uMetalRoughMap;
uniform vec4 uColor;                           // linear (converted from sRGB on the CPU)
uniform vec3 uEmissive;
uniform float uSpecular;
uniform float uShininess;
uniform int uUnlit;
uniform float uAlphaCutoff;                    // 0: blend or keep everything; otherwise discard below it

uniform int uHasNormalMap;
uniform float uNormalScale;
uniform float uNormalFlipY;
uniform int uHasSpecularMap;
uniform int uHasEmissiveMap;
uniform int uHasHeightMap;
uniform float uHeightScale;
uniform int uPbr;                              // 1: metallic-roughness BRDF, 0: Blinn-Phong
uniform float uMetallic;
uniform float uRoughness;
uniform int uHasMetalRoughMap;                 // glTF layout: roughness in green, metallic in blue
uniform float uReflectance;                    // F0 of non-metals
uniform vec3 uMaterialAmbient;                 // linear
uniform vec4 uUvTransform;                     // xy scale, zw offset
uniform int uClampUv;
uniform int uGlass;                            // 1: light added in full, coverage rises edge-on (Material.glass)

vec2 gUv;

// Tangent frame (columns t, b, n). Meshes with uvs carry a tangent attribute; without one, the frame comes from
// screen-space derivatives of the position and uv. Returns false when there is no usable frame.
bool tangentFrame(vec3 n, out mat3 tbn) {
  if (dot(vTangent.xyz, vTangent.xyz) > 1e-8) {
    vec3 t = normalize(vTangent.xyz - n * dot(n, vTangent.xyz));
    float handedness = vTangent.w < 0.0 ? -1.0 : 1.0;
    tbn = mat3(t, cross(n, t) * handedness, n);
    return true;
  }
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
    tbn = mat3(1.0);
    return false;
  }
  float invmax = inversesqrt(m);
  tbn = mat3(t * invmax, b * invmax, n);
  return true;
}

// Parallax occlusion mapping: marches the height field (white = high) along the view ray in tangent space.
vec2 parallaxUv(vec2 uv, vec3 viewTs) {
  vec2 gx = dFdx(uv);
  vec2 gy = dFdy(uv);
  float layers = mix(32.0, 8.0, clamp(abs(viewTs.z), 0.0, 1.0));
  float layerStep = 1.0 / layers;
  vec2 delta = viewTs.xy / max(viewTs.z, 0.1) * uHeightScale / layers;
  vec2 cur = uv;
  float layerDepth = 0.0;
  float depth = 1.0 - textureGrad(uHeightMap, cur, gx, gy).r;
  for (int i = 0; i < 32 && layerDepth < depth; i++) {
    cur -= delta;
    depth = 1.0 - textureGrad(uHeightMap, cur, gx, gy).r;
    layerDepth += layerStep;
  }
  vec2 prev = cur + delta;
  float after = depth - layerDepth;
  float before = 1.0 - textureGrad(uHeightMap, prev, gx, gy).r - layerDepth + layerStep;
  float w = after / (after - before + 1e-6);
  return mix(cur, prev, clamp(w, 0.0, 1.0));
}

// The material at this fragment. Discards the fragment where the material's alpha is below its cut-off.
Surface standardSurface() {
  vec3 geometric = geometricNormal();
  vec3 v = viewDirection();
  vec2 uv = vUv * uUvTransform.xy + uUvTransform.zw;
  if (uClampUv == 1) {
    uv = clamp(uv, 0.0, 1.0);
  }
  mat3 tbn;
  bool hasFrame = (uHasNormalMap == 1 || uHasHeightMap == 1) && tangentFrame(geometric, tbn);
  if (hasFrame && uHasHeightMap == 1) {
    uv = parallaxUv(uv, transpose(tbn) * v);
  }
  gUv = uv;

  Surface s;
  vec4 albedo = texture(uTexture, uv) * uColor;
  if (albedo.a < max(uAlphaCutoff, 0.01)) {
    discard;
  }
  s.albedo = albedo.rgb;
  s.alpha = albedo.a;
  s.unlit = uUnlit == 1;
  s.glass = uGlass == 1;
  s.normal = geometric;
  if (hasFrame && uHasNormalMap == 1) {
    vec3 map = texture(uNormalMap, uv).xyz * 2.0 - 1.0;
    map.y *= uNormalFlipY;
    map.xy *= uNormalScale;
    s.normal = normalize(tbn * map);
  }
  s.specular = uSpecular;
  if (uHasSpecularMap == 1) {
    s.specular *= dot(texture(uSpecularMap, uv).rgb, vec3(1.0 / 3.0));
  }
  s.shininess = uShininess;
  s.pbr = uPbr == 1;
  s.metallic = uMetallic;
  s.roughness = uRoughness;
  if (uHasMetalRoughMap == 1) {
    vec3 mr = texture(uMetalRoughMap, uv).rgb;
    s.roughness *= mr.g;
    s.metallic *= mr.b;
  }
  s.reflectance = uReflectance;
  s.emissive = uEmissive;
  if (uHasEmissiveMap == 1) {
    s.emissive *= texture(uEmissiveMap, uv).rgb;
  }
  s.ambient = uMaterialAmbient;
  return s;
}
