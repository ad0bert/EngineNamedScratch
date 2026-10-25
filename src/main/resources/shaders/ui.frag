#version 330 core

in vec2 vUv;
in vec4 vColor;

uniform sampler2D uAtlas;

out vec4 fragColor;

void main() {
  fragColor = texture(uAtlas, vUv) * vColor;
}
