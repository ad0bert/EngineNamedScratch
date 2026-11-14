// The engine's lighting for the main pass, for fragment shaders: lights with their shadows (cascades for the first
// directional light, cube and spot maps for local ones), the sky's image-based light, reflection probes, and the
// output targets (opaque colour and ambient share, or weighted blended transparency).
//
// A fragment shader includes it (or lib/surface.glsl, which includes it), fills in a Surface and calls
// shade(surface), which writes the fragment. The renderer sets every uniform here for every main-pass program.

#define MAX_LIGHTS 8
#define MAX_CASCADES 4
#define PI 3.14159265

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUv;
in vec4 vTangent;

uniform vec3 uCameraPos;
uniform vec3 uAmbient;                         // scene ambient light (used without a sky)
uniform float uTime;                           // seconds since the renderer started
uniform int uOit;                              // 1: write weighted blended transparency targets
uniform sampler2DArrayShadow uShadowMap;
uniform sampler2DArrayShadow uPointShadowMap;

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
layout(location = 1) out vec4 fragWeight;      // transparency pass: weights; opaque pass: the ambient light
                                               // (what ambient occlusion may take away)
vec3 gAmbient = vec3(0.0);

// What a surface is like at this fragment; shade() lights it.
struct Surface {
  vec3 albedo;        // linear colour
  float alpha;        // coverage (transparent materials) or 1
  vec3 normal;        // shading normal in world space, unit length (normal maps applied)
  vec3 emissive;      // light of its own, linear, added after lighting
  bool pbr;           // metallic-roughness BRDF; otherwise Blinn-Phong with specular and shininess
  float metallic;     // 0 dielectric .. 1 metal (pbr)
  float roughness;    // 0 mirror .. 1 rough (pbr)
  float reflectance;  // F0 of non-metals (pbr)
  float specular;     // highlight strength (Blinn-Phong)
  float shininess;    // highlight exponent (Blinn-Phong)
  vec3 ambient;       // how much ambient and sky light it takes, linear
  bool unlit;         // albedo as it is, no light
  bool glass;         // light added in full, coverage rising edge-on (transparent materials only)
};

// A surface with the engine's default material: white, facing the viewer's side of the mesh.
Surface defaultSurface() {
  Surface s;
  s.albedo = vec3(1.0);
  s.alpha = 1.0;
  s.normal = normalize(vNormal);
  if (!gl_FrontFacing) {
    s.normal = -s.normal;
  }
  s.emissive = vec3(0.0);
  s.pbr = false;
  s.metallic = 0.0;
  s.roughness = 0.6;
  s.reflectance = 0.04;
  s.specular = 0.3;
  s.shininess = 32.0;
  s.ambient = vec3(1.0);
  s.unlit = false;
  s.glass = false;
  return s;
}

// The mesh's own normal, towards the viewer's side (both sides are lit, so thin geometry never goes black).
vec3 geometricNormal() {
  vec3 n = normalize(vNormal);
  return gl_FrontFacing ? n : -n;
}

// From this fragment towards the camera.
vec3 viewDirection() {
  return normalize(uCameraPos - vWorldPos);
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

// Opaque pass: the colour. Transparency pass: weighted colour and revealage, and the weight (McGuire & Bavoil);
// the weight favours near surfaces.
void writeColor(vec3 color, float alpha) {
  if (uOit == 1) {
    float w = clamp(alpha * max(1e-2, 3e3 * pow(1.0 - gl_FragCoord.z, 3.0)), 1e-2, 3e3);
    fragColor = vec4(color * alpha * w, alpha);
    fragWeight = vec4(alpha * w, 0.0, 0.0, alpha);
  } else {
    fragColor = vec4(color, alpha);
    fragWeight = vec4(gAmbient, 1.0);
  }
}

// Cook-Torrance with the GGX distribution, Smith-Schlick geometry and Schlick's Fresnel. Lights are given as the
// light arriving at a surface facing them, so the diffuse part is albedo (not albedo / pi) like the Blinn-Phong path.
vec3 brdf(vec3 n, vec3 v, vec3 l, vec3 albedo, float metallic, float roughness, float reflectance) {
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
  vec3 f0 = mix(vec3(reflectance), albedo, metallic);
  vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
  vec3 specular = distribution * geometry * fresnel / (4.0 * ndotv * max(ndotl, 1e-4));
  vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * albedo;
  return (diffuse + specular * PI) * ndotl;
}

vec3 toSky(vec3 d) {
  return vec3(uSkyRotation.x * d.x + uSkyRotation.y * d.z, d.y, -uSkyRotation.y * d.x + uSkyRotation.x * d.z);
}

// Local reflection probes: cube maps captured in the scene, used instead of the sky inside their boxes (faded out
// over the last uProbeMin.w metres to the box's faces). Reflections are box-projected: the reflected ray is traced
// to the box and the probe looked up towards where it leaves it, so near walls line up. Diffuse light comes from the
// roughest level.
uniform int uProbeCount;
uniform vec4 uProbeMin[4];        // box corner, w: fade distance
uniform vec4 uProbeMax[4];        // box corner, w: intensity
uniform vec3 uProbePos[4];        // where it was captured
uniform samplerCube uProbe0;
uniform samplerCube uProbe1;
uniform samplerCube uProbe2;
uniform samplerCube uProbe3;

vec3 probeLod(int i, vec3 dir, float lod) {
  if (i == 0) return textureLod(uProbe0, dir, lod).rgb;
  if (i == 1) return textureLod(uProbe1, dir, lod).rgb;
  if (i == 2) return textureLod(uProbe2, dir, lod).rgb;
  return textureLod(uProbe3, dir, lod).rgb;
}

float probeWeight(int i) {
  vec3 inside = min(vWorldPos - uProbeMin[i].xyz, uProbeMax[i].xyz - vWorldPos);
  float d = min(inside.x, min(inside.y, inside.z));
  return d <= 0.0 ? 0.0 : clamp(d / max(uProbeMin[i].w, 1e-3), 0.0, 1.0);
}

// The direction to look the probe up in for a ray from this point along r: towards where it leaves the box.
vec3 boxProject(int i, vec3 r) {
  vec3 far = mix(uProbeMin[i].xyz, uProbeMax[i].xyz, step(0.0, r));
  vec3 safe = mix(r, mix(vec3(1e-5), vec3(-1e-5), lessThan(r, vec3(0.0))), lessThan(abs(r), vec3(1e-5)));
  vec3 t = (far - vWorldPos) / safe;
  float exit = min(t.x, min(t.y, t.z));
  return vWorldPos + r * max(exit, 0.0) - uProbePos[i];
}

// Blends the probes (weights summing up to 1 at most, the rest is the sky) for a lookup of r at level lod.
vec3 probesOver(vec3 sky, vec3 r, float lod, bool project) {
  vec3 sum = vec3(0.0);
  float total = 0.0;
  for (int i = 0; i < uProbeCount; i++) {
    float w = probeWeight(i) * (1.0 - total);
    if (w > 0.0) {
      sum += w * probeLod(i, project ? boxProject(i, r) : r, lod) * uProbeMax[i].w;
      total += w;
    }
  }
  return sum + sky * (1.0 - total);
}

vec3 skyIrradiance(vec3 n) {
  vec3 sky = texture(uIrradianceMap, toSky(n)).rgb * uSkyIntensity;
  return uProbeCount > 0 ? probesOver(sky, n, uPrefilteredLevels, false) : sky;
}

// Reflected sky light (split sum); reflections that point into the surface are faded out (no sky down there).
vec3 skySpecular(vec3 n, vec3 v, vec3 geometric, vec3 f0, float roughness) {
  vec3 r = reflect(-v, n);
  float ndotv = max(dot(n, v), 1e-4);
  vec2 ab = texture(uBrdfLut, vec2(ndotv, roughness)).rg;
  vec3 light = textureLod(uPrefilteredMap, toSky(r), roughness * uPrefilteredLevels).rgb * uSkyIntensity;
  if (uProbeCount > 0) {
    light = probesOver(light, r, roughness * uPrefilteredLevels, true);
  }
  float horizon = clamp(1.0 + dot(r, geometric), 0.0, 1.0);
  return light * (f0 * ab.x + ab.y) * horizon * horizon;
}

// The light this surface sends towards the camera: every light (with shadows), the ambient or sky light, emission.
vec3 lighting(Surface s) {
  vec3 geometric = geometricNormal();
  vec3 v = viewDirection();
  vec3 n = s.normal;
  float roughness = clamp(s.roughness, 0.03, 1.0);
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
    if (s.pbr) {
      if (diff > 0.0) {
        specular += c * brdf(n, v, l, s.albedo, s.metallic, roughness, s.reflectance); // diffuse included
      }
    } else {
      diffuse += c * diff;
      if (diff > 0.0) {
        vec3 h = normalize(l + v);
        specular += c * pow(max(dot(n, h), 0.0), s.shininess) * s.specular;
      }
    }
  }
  vec3 lit;
  if (s.pbr) {
    vec3 f0 = mix(vec3(s.reflectance), s.albedo, s.metallic);
    if (uSky == 1) {
      // Fresnel with roughness splits the sky's light between reflection and diffuse
      float ndotv = max(dot(n, v), 1e-4);
      vec3 f = f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(1.0 - ndotv, 5.0);
      vec3 kd = (1.0 - f) * (1.0 - s.metallic);
      gAmbient = s.ambient * (kd * s.albedo * skyIrradiance(n) + skySpecular(n, v, geometric, f0, roughness));
      lit = gAmbient + specular + s.emissive;
    } else {
      vec3 ambient = uAmbient * s.ambient;
      gAmbient = ambient * (s.albedo * (1.0 - s.metallic) + f0 * (1.0 - roughness) * 0.5);
      lit = gAmbient + specular + s.emissive;
    }
  } else {
    vec3 ambient = uSky == 1 ? skyIrradiance(n) : uAmbient;
    gAmbient = s.albedo * ambient * s.ambient;
    lit = gAmbient + s.albedo * diffuse + specular + s.emissive;
  }
  if (uDebugCascades == 1 && uCascadeCount > 0) {
    vec3 tint[4] = vec3[4](vec3(1.0, 0.4, 0.4), vec3(0.4, 1.0, 0.4), vec3(0.4, 0.5, 1.0), vec3(1.0, 1.0, 0.4));
    lit = mix(lit, lit * tint[cascade], 0.5);
  }
  return lit;
}

// Lights the surface and writes the fragment (colour and ambient share, or transparency weights).
void shade(Surface s) {
  if (s.unlit) {
    writeColor(s.albedo, s.alpha);
    return;
  }
  vec3 lit = lighting(s);
  if (s.glass) {
    // blending scales the colour by alpha: divide it out, so the glass adds all of its light (reflections,
    // highlights) and only the light from behind is cut by its coverage, which rises edge-on (Fresnel)
    float coverage = s.alpha + (1.0 - s.alpha) * pow(1.0 - max(dot(s.normal, viewDirection()), 0.0), 5.0);
    float a = max(coverage, 0.02);
    writeColor(lit / a, a);
    return;
  }
  writeColor(lit, s.alpha);
}
