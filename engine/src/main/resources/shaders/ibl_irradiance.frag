#version 330 core

#define PI 3.14159265

// Diffuse irradiance for one cube texel: the cosine-weighted average of the sky over the hemisphere around vDir
// (divided by pi, so a uniform sky of radiance L gives L and a white surface under it looks as bright as the sky).
in vec3 vDir;

uniform samplerCube uEnv;
uniform float uLod;           // a blurry mip of the environment: the integral is smooth anyway

out vec4 fragColor;

void main() {
  vec3 n = normalize(vDir);
  vec3 up = abs(n.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
  vec3 right = normalize(cross(up, n));
  up = cross(n, right);
  vec3 sum = vec3(0.0);
  float count = 0.0;
  const float STEP = 0.12;
  for (float phi = 0.0; phi < 2.0 * PI; phi += STEP) {
    for (float theta = 0.5 * STEP; theta < 0.5 * PI; theta += STEP) {
      vec3 t = vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta));
      vec3 dir = t.x * right + t.y * up + t.z * n;
      sum += textureLod(uEnv, dir, uLod).rgb * cos(theta) * sin(theta);
      count += 1.0;
    }
  }
  fragColor = vec4(PI * sum / count, 1.0);
}
