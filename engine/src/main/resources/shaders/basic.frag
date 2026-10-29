#version 330 core

#define MAX_LIGHTS 8
#define MAX_CASCADES 4
#define PI 3.14159265

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUv;
in vec4 vTangent;

uniform sampler2D uTexture;
uniform sampler2D uNormalMap;
uniform sampler2D uSpecularMap;
uniform sampler2D uEmissiveMap;
uniform sampler2D uHeightMap;
uniform sampler2D uMetalRoughMap;
uniform sampler2DArrayShadow uShadowMap;
uniform sampler2DArrayShadow uPointShadowMap;
uniform vec4 uColor;                           // linear (converted from sRGB on the CPU)
uniform vec3 uEmissive;
uniform vec3 uAmbient;
uniform vec3 uCameraPos;
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
uniform int uOit;                              // 1: write weighted blended transparency targets
uniform int uGlass;                            // 1: light added in full, coverage rises edge-on (Material.glass)

uniform int uLightCount;
uniform vec4 uLightPos[MAX_LIGHTS];   // xyz: direction towards the light (w=0) or position (w=1 point, w=2 spot)
uniform vec4 uLightColor[MAX_LIGHTS]; // rgb: colour * intensity, a: range (point and spot lights)
uniform vec4 uLightSpot[MAX_LIGHTS];  // spot lights: xyz direction the light shines, w: cos of the outer angle
uniform float uLightInner[MAX_LIGHTS]; // spot lights: cos of the angle where the fade to the edge starts
uniform int uLightShadow[MAX_LIGHTS]; // point/spot lights: first layer of their shadow maps, or -1
uniform vec2 uShadowRange[MAX_LIGHTS]; // point lights: near, far plane of the shadow cube
uniform mat4 uSpotShadow[MAX_LIGHTS]; // spot lights: world -> shadow clip space

// cascaded shadows for light 0
uniform int uCascadeCount;                     // 0: no shadows
uniform mat4 uCascadeMatrix[MAX_CASCADES];     // world -> light clip space
uniform float uCascadeSplit[MAX_CASCADES];     // far distance (view depth) of each cascade
uniform float uCascadeTexel[MAX_CASCADES];     // world size of one shadow texel
uniform float uShadowTexel;                    // 1 / shadow map size
uniform int uDebugCascades;

// local light shadows: six layers per shadowed point light (+x -x +y -y +z -z), one per spot light
uniform float uPointShadowTexel;               // 1 / layer size

// image-based lighting from the sky (replaces uAmbient when uSky is 1)
uniform int uSky;
uniform samplerCube uIrradianceMap;            // diffuse irradiance / pi
uniform samplerCube uPrefilteredMap;           // reflections, roughness = mip / uPrefilteredLevels
uniform sampler2D uBrdfLut;                    // split-sum scale and bias by (n . v, roughness)
uniform float uSkyIntensity;
uniform vec2 uSkyRotation;                     // cos, sin of the sky's turn about +y
uniform float uPrefilteredLevels;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragWeight;      // transparency pass only

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

// Must match GlSceneRenderer.CUBE_FORWARD / CUBE_UP.
const vec3 CUBE_FORWARD[6] = vec3[6](vec3(1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0), vec3(0, 0, 1),
    vec3(0, 0, -1));
const vec3 CUBE_UP[6] = vec3[6](vec3(0, 1, 0), vec3(0, 1, 0), vec3(0, 0, 1), vec3(0, 0, -1), vec3(0, 1, 0),
    vec3(0, 1, 0));

float pointShadow(int i, vec3 lightPos, vec3 n, float ndotl) {
  vec3 fromLight = vWorldPos - lightPos;
  float dist = length(fromLight);
  // normal offset of about a texel at this distance (a 90 degree face is 2 * dist wide)
  vec3 p = vWorldPos + n * (2.0 * dist * uPointShadowTexel) * (1.5 + 2.0 * (1.0 - ndotl));
  vec3 d = p - lightPos;
  vec3 a = abs(d);
  int face;
  if (a.x >= a.y && a.x >= a.z) {
    face = d.x > 0.0 ? 0 : 1;
  } else if (a.y >= a.z) {
    face = d.y > 0.0 ? 2 : 3;
  } else {
    face = d.z > 0.0 ? 4 : 5;
  }
  vec3 f = CUBE_FORWARD[face];
  vec3 u = CUBE_UP[face];
  vec3 r = cross(f, u);
  float z = dot(d, f);
  float near = uShadowRange[i].x;
  float far = uShadowRange[i].y;
  if (z >= far) {
    return 1.0;
  }
  vec2 uv = vec2(dot(d, r), dot(d, u)) / z * 0.5 + 0.5;
  float ndc = (far + near) / (far - near) - 2.0 * far * near / ((far - near) * z);
  float ref = ndc * 0.5 + 0.5 - 0.0002;
  float layer = float(uLightShadow[i] + face);
  float sum = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      sum += texture(uPointShadowMap, vec4(uv + vec2(x, y) * uPointShadowTexel, layer, ref));
    }
  }
  return sum / 9.0;
}

// Opaque pass: the colour. Transparency pass: weighted colour and revealage, and the weight (McGuire & Bavoil);
// the weight favours near surfaces.
void writeColor(vec3 color, float alpha) {
  if (uOit == 1) {
    float w = clamp(alpha * max(1e-2, 3e3 * pow(1.0 - gl_FragCoord.z, 3.0)), 1e-2, 3e3);
    fragColor = vec4(color * alpha * w, alpha);
    fragWeight = vec4(alpha * w, 0.0, 0.0, alpha);
  } else {
    fragColor = vec4(color, alpha);
    fragWeight = vec4(0.0);
  }
}

float spotShadow(int i, vec3 lightPos, vec3 n, float ndotl) {
  float dist = length(vWorldPos - lightPos);
  vec3 p = vWorldPos + n * (2.0 * dist * uPointShadowTexel) * (1.5 + 2.0 * (1.0 - ndotl));
  vec4 s = uSpotShadow[i] * vec4(p, 1.0);
  vec3 q = s.xyz / s.w * 0.5 + 0.5;
  if (s.w <= 0.0 || q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0 || q.z > 1.0) {
    return 1.0;
  }
  float sum = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      sum += texture(uPointShadowMap, vec4(q.xy + vec2(x, y) * uPointShadowTexel, float(uLightShadow[i]),
          q.z - 0.0002));
    }
  }
  return sum / 9.0;
}

// Cook-Torrance with the GGX distribution, Smith-Schlick geometry and Schlick's Fresnel. Lights are given as the
// light arriving at a surface facing them, so the diffuse part is albedo (not albedo / pi) like the Blinn-Phong path.
vec3 brdf(vec3 n, vec3 v, vec3 l, vec3 albedo, float metallic, float roughness) {
  vec3 h = normalize(l + v);
  float ndotl = max(dot(n, l), 0.0);
  float ndotv = max(dot(n, v), 1e-4);
  float ndoth = max(dot(n, h), 0.0);
  float a = roughness * roughness;
  float a2 = a * a;
  float d = ndoth * ndoth * (a2 - 1.0) + 1.0;
  float distribution = a2 / (PI * d * d);
  float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
  float geometry = ndotv / (ndotv * (1.0 - k) + k) * ndotl / (ndotl * (1.0 - k) + k);
  vec3 f0 = mix(vec3(uReflectance), albedo, metallic);
  vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
  vec3 specular = distribution * geometry * fresnel / (4.0 * ndotv * max(ndotl, 1e-4));
  vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * albedo;
  return (diffuse + specular * PI) * ndotl;
}

vec3 toSky(vec3 d) {
  return vec3(uSkyRotation.x * d.x + uSkyRotation.y * d.z, d.y, -uSkyRotation.y * d.x + uSkyRotation.x * d.z);
}

vec3 skyIrradiance(vec3 n) {
  return texture(uIrradianceMap, toSky(n)).rgb * uSkyIntensity;
}

// Reflected sky light (split sum); reflections that point into the surface are faded out (no sky down there).
vec3 skySpecular(vec3 n, vec3 v, vec3 geometric, vec3 f0, float roughness) {
  vec3 r = reflect(-v, n);
  float ndotv = max(dot(n, v), 1e-4);
  vec2 ab = texture(uBrdfLut, vec2(ndotv, roughness)).rg;
  vec3 light = textureLod(uPrefilteredMap, toSky(r), roughness * uPrefilteredLevels).rgb * uSkyIntensity;
  float horizon = clamp(1.0 + dot(r, geometric), 0.0, 1.0);
  return light * (f0 * ab.x + ab.y) * horizon * horizon;
}

void main() {
  vec3 geometric = normalize(vNormal);
  // light both sides so un-culled thin geometry never goes black
  if (!gl_FrontFacing) {
    geometric = -geometric;
  }
  vec3 v = normalize(uCameraPos - vWorldPos);
  vec2 uv = vUv * uUvTransform.xy + uUvTransform.zw;
  if (uClampUv == 1) {
    uv = clamp(uv, 0.0, 1.0);
  }
  mat3 tbn;
  bool hasFrame = (uHasNormalMap == 1 || uHasHeightMap == 1) && tangentFrame(geometric, tbn);
  if (hasFrame && uHasHeightMap == 1) {
    uv = parallaxUv(uv, transpose(tbn) * v);
  }

  vec4 albedo = texture(uTexture, uv) * uColor;
  if (albedo.a < max(uAlphaCutoff, 0.01)) {
    discard;
  }
  if (uUnlit == 1) {
    writeColor(albedo.rgb, albedo.a);
    return;
  }
  vec3 n = geometric;
  if (hasFrame && uHasNormalMap == 1) {
    vec3 map = texture(uNormalMap, uv).xyz * 2.0 - 1.0;
    map.y *= uNormalFlipY;
    map.xy *= uNormalScale;
    n = normalize(tbn * map);
  }
  float specularStrength = uSpecular;
  if (uHasSpecularMap == 1) {
    specularStrength *= dot(texture(uSpecularMap, uv).rgb, vec3(1.0 / 3.0));
  }
  float metallic = uMetallic;
  float roughness = uRoughness;
  if (uHasMetalRoughMap == 1) {
    vec3 mr = texture(uMetalRoughMap, uv).rgb;
    roughness *= mr.g;
    metallic *= mr.b;
  }
  roughness = clamp(roughness, 0.03, 1.0);
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
      if (uLightPos[i].w > 1.5) { // spot: fade from the inner to the outer cone
        float c = dot(-l, uLightSpot[i].xyz);
        atten *= smoothstep(uLightSpot[i].w, uLightInner[i], c);
      }
    }
    float diff = max(dot(n, l), 0.0);
    float shadow = 1.0;
    // shadows use the geometric normal: the normal map must not change where a surface is in shadow
    if (diff > 0.0 && atten > 0.0) {
      if (i == 0 && uCascadeCount > 0) {
        shadow = shadowFactor(geometric, l, cascade);
      } else if (uLightShadow[i] >= 0) {
        float ndotl = max(dot(geometric, l), 0.0);
        shadow = uLightPos[i].w > 1.5 ? spotShadow(i, uLightPos[i].xyz, geometric, ndotl)
            : pointShadow(i, uLightPos[i].xyz, geometric, ndotl);
      }
    }
    vec3 c = uLightColor[i].rgb * atten * shadow;
    if (uPbr == 1) {
      if (diff > 0.0) {
        specular += c * brdf(n, v, l, albedo.rgb, metallic, roughness); // diffuse included
      }
    } else {
      diffuse += c * diff;
      if (diff > 0.0) {
        vec3 h = normalize(l + v);
        specular += c * pow(max(dot(n, h), 0.0), uShininess) * specularStrength;
      }
    }
  }
  vec3 emissive = uEmissive;
  if (uHasEmissiveMap == 1) {
    emissive *= texture(uEmissiveMap, uv).rgb;
  }
  vec3 lit;
  if (uPbr == 1) {
    vec3 f0 = mix(vec3(uReflectance), albedo.rgb, metallic);
    if (uSky == 1) {
      // Fresnel with roughness splits the sky's light between reflection and diffuse
      float ndotv = max(dot(n, v), 1e-4);
      vec3 f = f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(1.0 - ndotv, 5.0);
      vec3 kd = (1.0 - f) * (1.0 - metallic);
      lit = uMaterialAmbient * (kd * albedo.rgb * skyIrradiance(n) + skySpecular(n, v, geometric, f0, roughness))
          + specular + emissive;
    } else {
      vec3 ambient = uAmbient * uMaterialAmbient;
      lit = ambient * (albedo.rgb * (1.0 - metallic) + f0 * (1.0 - roughness) * 0.5) + specular + emissive;
    }
  } else {
    vec3 ambient = uSky == 1 ? skyIrradiance(n) : uAmbient;
    lit = albedo.rgb * (ambient * uMaterialAmbient + diffuse) + specular + emissive;
  }
  if (uDebugCascades == 1 && uCascadeCount > 0) {
    vec3 tint[4] = vec3[4](vec3(1.0, 0.4, 0.4), vec3(0.4, 1.0, 0.4), vec3(0.4, 0.5, 1.0), vec3(1.0, 1.0, 0.4));
    lit = mix(lit, lit * tint[cascade], 0.5);
  }
  if (uGlass == 1) {
    // blending scales the colour by alpha: divide it out, so the glass adds all of its light (reflections,
    // highlights) and only the light from behind is cut by its coverage, which rises edge-on (Fresnel)
    float coverage = albedo.a + (1.0 - albedo.a) * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    float a = max(coverage, 0.02);
    writeColor(lit / a, a);
    return;
  }
  writeColor(lit, albedo.a);
}
