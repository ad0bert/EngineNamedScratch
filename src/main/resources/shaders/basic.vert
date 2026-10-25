#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in mat4 aModel;       // per instance, occupies locations 3-6
layout(location = 7) in mat3 aNormalMat;   // per instance (inverse transpose of the model), locations 7-9

uniform mat4 uViewProj;
uniform float uPointSize;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUv;

void main() {
  vec4 world = aModel * vec4(aPos, 1.0);
  vWorldPos = world.xyz;
  vNormal = normalize(aNormalMat * aNormal);
  vUv = aUv;
  gl_Position = uViewProj * world;
  gl_PointSize = uPointSize;
}
