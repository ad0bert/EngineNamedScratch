#version 330 core

// One triangle covering the target, at the far plane. vDir is the direction seen through each pixel: the camera's
// view ray (background) or the direction of a cube map texel (baking a face).
uniform int uFace;            // -1: background, 0..5: cube face +x -x +y -y +z -z
uniform mat4 uInvProj;        // background: clip -> view space
uniform mat4 uViewToWorld;    // background: view -> world space (only its rotation is used)

out vec3 vDir;

// OpenGL's cube map layout: the direction of the texel at (s, t) in -1..1 on each face.
vec3 faceDir(int face, vec2 st) {
  if (face == 0) return vec3(1.0, -st.y, -st.x);
  if (face == 1) return vec3(-1.0, -st.y, st.x);
  if (face == 2) return vec3(st.x, 1.0, st.y);
  if (face == 3) return vec3(st.x, -1.0, -st.y);
  if (face == 4) return vec3(st.x, -st.y, 1.0);
  return vec3(-st.x, -st.y, -1.0);
}

void main() {
  vec2 ndc = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
  if (uFace < 0) {
    vec4 far = uInvProj * vec4(ndc, 1.0, 1.0); // points on the far plane are affine in ndc: safe to interpolate
    vDir = mat3(uViewToWorld) * (far.xyz / far.w);
  } else {
    vDir = faceDir(uFace, ndc);
  }
  gl_Position = vec4(ndc, 1.0, 1.0);
}
