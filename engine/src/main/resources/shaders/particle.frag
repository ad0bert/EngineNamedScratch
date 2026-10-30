#version 330 core

// Premultiplied output for glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA): alpha particles cover the scene by their alpha,
// additive ones (alpha 0) only add their light. Soft particles fade out in front of opaque geometry.
in vec2 vUv;
in vec2 vLocal;
in vec4 vColor;
in float vDepth;

uniform sampler2D uTexture;
uniform int uHasTexture;
uniform sampler2D uDepth;  // copy of the opaque scene's depth
uniform float uSoftness;   // world units; 0 = hard
uniform vec2 uInvScreen;
uniform vec2 uNearFar;
uniform float uLit;             // 0: the particle's own colour, 1: lit like a surface facing the light half-way
uniform vec3 uLight;            // lights (and the flat ambient) at the emitter's centre, from the CPU
uniform int uSky;
uniform samplerCube uIrradianceMap;
uniform float uSkyIntensity;
uniform vec2 uSkyRotation;

out vec4 fragColor;

void main() {
  vec4 t;
  if (uHasTexture == 1) {
    t = texture(uTexture, vUv);
  } else {
    float fall = max(0.0, 1.0 - dot(vLocal, vLocal)); // soft round dot
    t = vec4(1.0, 1.0, 1.0, fall * fall);
  }
  float k = t.a;
  if (uSoftness > 0.0) {
    float z = texture(uDepth, gl_FragCoord.xy * uInvScreen).r * 2.0 - 1.0;
    float n = uNearFar.x;
    float f = uNearFar.y;
    float scene = 2.0 * n * f / (f + n - z * (f - n));
    k *= clamp((scene - vDepth) / uSoftness, 0.0, 1.0);
  }
  if (k < 1.0 / 1024.0) {
    discard;
  }
  vec3 color = vColor.rgb * t.rgb;
  if (uLit > 0.0) {
    vec3 light = uLight;
    if (uSky == 1) { // the sky's light from above, as a horizontal surface would get it
      vec3 up = vec3(0.0, 1.0, 0.0);
      light += texture(uIrradianceMap, vec3(uSkyRotation.x * up.x + uSkyRotation.y * up.z, up.y,
          -uSkyRotation.y * up.x + uSkyRotation.x * up.z)).rgb * uSkyIntensity;
    }
    color = mix(color, color * light, uLit);
  }
  fragColor = vec4(color * k, vColor.a * k);
}
