#version 330 core

out vec4 fragColor;

void main() {
  fragColor = vec4(1.0); // colour writes are off: only whether a sample passed the depth test matters
}
