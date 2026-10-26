#version 330 core

// Shadow pass for cut-out materials: like depth.vert, plus the uv to look up the holes.
layout(location = 0) in vec3 aPos;
layout(location = 2) in vec2 aUv;
layout(location = 3) in mat4 aModel;
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

uniform mat4 uLightViewProj;

out vec2 vUv;

void main() {
  vUv = aUv;
  gl_Position = uLightViewProj * aModel * (skinMatrix() * vec4(aPos, 1.0));
}
