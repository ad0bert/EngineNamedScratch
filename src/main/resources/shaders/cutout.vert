#version 330 core

// Shadow pass for cut-out materials: like depth.vert, plus the uv to look up the holes.
layout(location = 0) in vec3 aPos;
layout(location = 2) in vec2 aUv;
layout(location = 3) in mat4 aModel;

uniform mat4 uLightViewProj;

out vec2 vUv;

void main() {
  vUv = aUv;
  gl_Position = uLightViewProj * aModel * vec4(aPos, 1.0);
}
