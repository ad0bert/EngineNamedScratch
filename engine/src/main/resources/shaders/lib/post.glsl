// What a post pass's fragment shader gets (engine.render.PostPass). Write the result to fragColor.
//
//   #version 330 core
//   #include "lib/post.glsl"
//   void main() { fragColor = vec4(1.0 - texture(uImage, vUv).rgb, 1.0); }

in vec2 vUv;                   // 0..1 across the screen

uniform sampler2D uImage;      // the image so far: linear HDR (Stage.HDR) or sRGB 0..1 (Stage.DISPLAY)
uniform sampler2D uDepth;      // the scene's depth buffer, 0 (near) .. 1 (far, nothing drawn)
uniform vec2 uTexel;           // 1 / size in pixels
uniform float uTime;           // seconds since the renderer started
uniform mat4 uProj;            // the camera's projection
uniform mat4 uInvProj;         // and its inverse

out vec4 fragColor;

// The view-space position (camera at the origin, looking along -z) of the scene at uv.
vec3 viewPosition(vec2 uv) {
  float depth = texture(uDepth, uv).r;
  vec4 p = uInvProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
  return p.xyz / p.w;
}
