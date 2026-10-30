#version 330 core

// Screen-space ambient occlusion: how much of the hemisphere above each pixel (view space, normal from the depth of
// its neighbours) is inside the scene's depth. 16 samples in a cone-weighted hemisphere, turned per pixel; samples
// whose depth difference is far beyond the radius count less, so a distant background does not shadow an object.
in vec2 vUv;
uniform sampler2D uDepth;
uniform mat4 uProj;
uniform mat4 uInvProj;
uniform vec2 uTexel;        // one texel of the depth texture
uniform float uRadius;      // in metres
out float ao;

const int SAMPLES = 16;

vec3 viewPos(vec2 uv) {
  float d = texture(uDepth, uv).r;
  vec4 p = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
  return p.xyz / p.w;
}

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  // the centre of a full-size depth texel: a half-size pixel centre lies on a texel border, where the neighbours
  // below could round to the same texel (a zero tangent, and no normal)
  vec2 uv = (floor(vUv / uTexel) + 0.5) * uTexel;
  float d = texture(uDepth, uv).r;
  if (d >= 1.0) {
    ao = 1.0; // the sky
    return;
  }
  vec3 p = viewPos(uv);
  // the normal from the neighbours on the side closer in depth (no smearing across silhouettes)
  vec3 r = viewPos(uv + vec2(uTexel.x, 0.0)) - p;
  vec3 l = p - viewPos(uv - vec2(uTexel.x, 0.0));
  vec3 u = viewPos(uv + vec2(0.0, uTexel.y)) - p;
  vec3 b = p - viewPos(uv - vec2(0.0, uTexel.y));
  vec3 dx = abs(r.z) < abs(l.z) ? r : l;
  vec3 dy = abs(u.z) < abs(b.z) ? u : b;
  vec3 cr = cross(dx, dy);
  if (dot(cr, cr) < 1e-20) {
    ao = 1.0;
    return;
  }
  vec3 n = normalize(cr);
  if (dot(n, p) > 0.0) {
    n = -n; // towards the camera
  }
  float angle = hash(gl_FragCoord.xy) * 6.2831853;
  vec3 helper = abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
  vec3 t = normalize(cross(helper, n));
  vec3 bt = cross(n, t);
  float c = cos(angle);
  float s = sin(angle);
  vec3 t2 = c * t + s * bt;
  vec3 b2 = -s * t + c * bt;
  // depth precision falls with distance: a larger bias there, and no occlusion at all far away
  float bias = 0.03 * uRadius + 0.004 * -p.z;
  float occlusion = 0.0;
  for (int i = 0; i < SAMPLES; i++) {
    float fi = (float(i) + 0.5) / float(SAMPLES);
    float z = sqrt(1.0 - fi);                    // cosine-weighted
    float rr = sqrt(fi);
    float phi = float(i) * 2.3999632;            // golden angle
    float scale = mix(0.15, 1.0, fi * fi);       // more samples near the point
    vec3 k = (t2 * cos(phi) * rr + b2 * sin(phi) * rr + n * z) * scale * uRadius;
    vec3 sp = p + k;
    vec4 q = uProj * vec4(sp, 1.0);
    vec2 suv = q.xy / q.w * 0.5 + 0.5;
    if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) {
      continue;
    }
    float sceneZ = viewPos(suv).z;
    float range = smoothstep(0.0, 1.0, uRadius / max(abs(p.z - sceneZ), 1e-4));
    occlusion += (sceneZ >= sp.z + bias ? 1.0 : 0.0) * range;
  }
  ao = mix(1.0 - occlusion / float(SAMPLES), 1.0, smoothstep(30.0, 60.0, -p.z));
}
