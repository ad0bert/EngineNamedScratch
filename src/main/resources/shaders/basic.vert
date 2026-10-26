#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in mat4 aModel;       // per instance, occupies locations 3-6
layout(location = 7) in mat3 aNormalMat;   // per instance (inverse transpose of the model), locations 7-9
layout(location = 10) in vec4 aTangent;    // xyz: direction of +u, w: handedness; zero when the mesh has no uvs

uniform mat4 uViewProj;
uniform float uPointSize;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUv;
out vec4 vTangent;

void main() {
  vec4 world = aModel * vec4(aPos, 1.0);
  vWorldPos = world.xyz;
  vNormal = normalize(aNormalMat * aNormal);
  vUv = aUv;
  // tangents lie in the surface, so they follow the model matrix itself
  vTangent = vec4(mat3(aModel) * aTangent.xyz, aTangent.w);
  gl_Position = uViewProj * world;
  gl_PointSize = uPointSize;
}
