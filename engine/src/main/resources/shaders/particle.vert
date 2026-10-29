#version 330 core

// Camera-facing particle quads, one instance per particle (see ParticleEmitter.writeInstances).
layout(location = 0) in vec2 aCorner;    // -1..1
layout(location = 1) in vec4 aPosSize;   // world position, edge length
layout(location = 2) in vec4 aColor;     // linear, premultiplied; alpha 0 for additive particles
layout(location = 3) in vec2 aRotFrame;  // rotation (radians), atlas frame

uniform mat4 uViewProj;
uniform vec3 uCameraPos;
uniform vec3 uCameraForward;
uniform vec3 uCameraRight;
uniform vec3 uCameraUp;
uniform vec2 uAtlas; // columns, rows

out vec2 vUv;
out vec2 vLocal;
out vec4 vColor;
out float vDepth;

void main() {
  float c = cos(aRotFrame.x);
  float s = sin(aRotFrame.x);
  vec2 corner = vec2(c * aCorner.x - s * aCorner.y, s * aCorner.x + c * aCorner.y) * (0.5 * aPosSize.w);
  vec3 world = aPosSize.xyz + uCameraRight * corner.x + uCameraUp * corner.y;
  gl_Position = uViewProj * vec4(world, 1.0);

  // frames run left to right, top to bottom as painted; uv (0, 0) is the image's bottom left
  float frame = aRotFrame.y;
  float column = mod(frame, uAtlas.x);
  float row = floor(frame / uAtlas.x);
  vUv = (aCorner * 0.5 + 0.5 + vec2(column, uAtlas.y - 1.0 - row)) / uAtlas;
  vLocal = aCorner;
  vColor = aColor;
  vDepth = dot(world - uCameraPos, uCameraForward);
}
