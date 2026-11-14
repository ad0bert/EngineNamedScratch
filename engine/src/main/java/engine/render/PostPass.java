package engine.render;

/**
 * A full-screen pass of the game's own, added to the end of the image's way to the screen
 * ({@link RenderSettings#postPasses}): a fragment shader that reads the image so far and writes the next one. Passes run
 * in list order within their {@link Stage}.
 *
 * <p>
 * The shader gets (see {@code shaders/lib/post.glsl}, which declares them): {@code vUv} (0..1 across the screen),
 * {@code uImage} (the image so far), {@code uDepth} (the scene's depth buffer, 0..1), {@code uTexel} (1 / size in
 * pixels), {@code uTime} (seconds), {@code uProj} and {@code uInvProj} (the camera's projection and its inverse, to
 * get view-space positions from depth), and the values of {@link #params} and {@link RenderSettings#shaderGlobals}.
 *
 * <pre>{@code
 * #version 330 core
 * #include "lib/post.glsl"
 * uniform float uStrength;
 * void main() {
 *   vec3 c = texture(uImage, vUv).rgb;
 *   float d = distance(vUv, vec2(0.5));
 *   fragColor = vec4(c * (1.0 - uStrength * d * d), 1.0);
 * }
 * }</pre>
 */
public final class PostPass {
  /** Where in the chain a pass runs. */
  public enum Stage {
    /**
     * On the HDR scene in linear light (after transparency and particles, before bloom and tone mapping): fog, colour
     * effects that should bloom, distortion. Values above 1 are bright light.
     */
    HDR,
    /**
     * On the tone-mapped image, sRGB-encoded in 0..1 (before anti-aliasing): colour grading, vignettes, outlines,
     * scan lines.
     */
    DISPLAY
  }

  /** The fragment shader, a resource path such as {@code "shaders/vignette.frag"}. */
  public final String fragmentShader;
  public final Stage stage;
  /** Values for the shader's own uniforms. */
  public final ShaderParams params = new ShaderParams();
  /** Turned off passes are skipped. */
  public boolean enabled = true;

  public PostPass(String fragmentShader, Stage stage) {
    if (fragmentShader == null || stage == null) {
      throw new IllegalArgumentException("a post pass needs a shader and a stage");
    }
    this.fragmentShader = fragmentShader;
    this.stage = stage;
  }

  @Override
  public String toString() {
    return "PostPass[" + this.fragmentShader + ", " + this.stage + (this.enabled ? "" : ", off") + "]";
  }
}
