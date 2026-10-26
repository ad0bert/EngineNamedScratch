#version 330 core

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in mat4 aModel;       // per instance, occupies locations 3-6
layout(location = 7) in mat3 aNormalMat;   // per instance (inverse transpose of the model), locations 7-9
layout(location = 10) in vec4 aTangent;    // xyz: direction of +u, w: handedness; zero when the mesh has no uvs
layout(location = 11) in vec4 aJoints;     // skinned meshes: four joint indices
layout(location = 12) in vec4 aWeights;    // and their weights (summing to 1)

uniform int uSkinned;
uniform samplerBuffer uJoints;             // joint matrices, four texels (columns) each

// The weighted sum of the vertex's joint matrices (identity for meshes that are not skinned).
mat4 skinMatrix() {
  if (uSkinned == 0) {
    return mat4(1.0);
  }
  mat4 m = mat4(0.0);
  for (int k = 0; k < 4; k++) {
    int b = int(aJoints[k] + 0.5) * 4;
    m += aWeights[k] * mat4(texelFetch(uJoints, b), texelFetch(uJoints, b + 1), texelFetch(uJoints, b + 2),
                            texelFetch(uJoints, b + 3));
  }
  return m;
}

uniform mat4 uViewProj;
uniform float uPointSize;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUv;
out vec4 vTangent;

void main() {
  mat4 skin = skinMatrix();
  vec4 world = aModel * (skin * vec4(aPos, 1.0));
  vWorldPos = world.xyz;
  vNormal = normalize(aNormalMat * (mat3(skin) * aNormal)); // joints are rotations (and uniform scales)
  vUv = aUv;
  // tangents lie in the surface, so they follow the model matrix itself
  vTangent = vec4(mat3(aModel) * (mat3(skin) * aTangent.xyz), aTangent.w);
  gl_Position = uViewProj * world;
  gl_PointSize = uPointSize;
}
