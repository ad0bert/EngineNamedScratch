#version 330 core

// Removes the occluded share of the ambient light from the scene colour (blended with reverse subtraction), so
// direct light stays as it was.
in vec2 vUv;
uniform sampler2D uAo;
uniform sampler2D uAmbient;
uniform float uStrength;
uniform int uDebug;         // 1: show the unoccluded share in grey instead (blending off)
out vec4 fragColor;

void main() {
  float visible = pow(clamp(texture(uAo, vUv).r, 0.0, 1.0), uStrength);
  fragColor = uDebug == 1 ? vec4(vec3(visible), 1.0) : vec4(texture(uAmbient, vUv).rgb * (1.0 - visible), 0.0);
}
