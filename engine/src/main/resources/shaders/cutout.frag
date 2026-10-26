#version 330 core

// A separate program from depth.frag: a shader that may discard turns off early depth testing, which only cut-out
// casters should pay for.
in vec2 vUv;

uniform sampler2D uTexture;
uniform float uAlpha;       // material alpha
uniform float uAlphaCutoff;

void main() {
  if (texture(uTexture, vUv).a * uAlpha < uAlphaCutoff) {
    discard; // the holes of a cut-out (leaves, fences) let light through
  }
}
