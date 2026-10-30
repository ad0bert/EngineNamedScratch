package engine.render.gl;

import org.joml.Matrix4f;

import com.jogamp.opengl.GL3;

import engine.render.RenderSettings;

/**
 * Off-screen targets and full-screen passes around the scene:
 * <ol>
 * <li>the scene is drawn into an HDR target (RGBA16F colour, 24-bit depth texture); the opaque pass also writes its
 * ambient light into a second target;</li>
 * <li>ambient occlusion: at half size, from the depth, then blurred; the occluded share of the ambient light is taken
 * off the scene colour;</li>
 * <li>transparent surfaces are accumulated into two more targets that share that depth (weighted blended
 * order-independent transparency) and resolved over the opaque image;</li>
 * <li>on request, the depth is copied into a texture that particles sample to fade where they meet geometry;</li>
 * <li>bloom: the bright parts are downsampled through a chain of half-size targets and added back up;</li>
 * <li>tone mapping (exposure, ACES curve, sRGB) into an 8-bit target, then FXAA into the window.</li>
 * </ol>
 * Everything is reallocated when the window size changes.
 */
final class PostProcessor {
  private static final int MAX_BLOOM_LEVELS = 6;
  private static final float[] CLEAR_ACCUM = { 0f, 0f, 0f, 1f }; // nothing accumulated, fully revealed
  private static final float[] CLEAR_WEIGHTS = { 0f, 0f, 0f, 0f };

  private ShaderProgram down;
  private ShaderProgram up;
  private ShaderProgram tonemap;
  private ShaderProgram fxaa;
  private ShaderProgram composite;
  private ShaderProgram ssao;
  private ShaderProgram ssaoBlur;
  private ShaderProgram ssaoApply;
  private int emptyVao;

  private int width;
  private int height;
  private int sceneFbo;
  private int sceneColor;
  private int sceneAmbient;
  private int depth;
  private int aoW;
  private int aoH;
  private int aoTex;
  private int aoFbo;
  private int aoBlurTex;
  private int aoBlurFbo;
  private int applyFbo;
  private boolean ambientTarget;
  private final Matrix4f invProj = new Matrix4f();
  private int oitFbo;
  private int oitAccum;
  private int oitWeights;
  private int depthCopyFbo;
  private int depthCopy;
  private int ldrFbo;
  private int ldrColor;
  private final int[] bloomFbo = new int[MAX_BLOOM_LEVELS];
  private final int[] bloomTex = new int[MAX_BLOOM_LEVELS];
  private final int[] bloomW = new int[MAX_BLOOM_LEVELS];
  private final int[] bloomH = new int[MAX_BLOOM_LEVELS];
  private int bloomLevels;
  private boolean allocated;

  void init(GL3 gl) {
    this.down = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/bloom_down.frag");
    this.up = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/bloom_up.frag");
    this.tonemap = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/tonemap.frag");
    this.fxaa = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/fxaa.frag");
    this.composite = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/oit_composite.frag");
    this.ssao = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/ssao.frag");
    this.ssaoBlur = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/ssao_blur.frag");
    this.ssaoApply = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/ssao_apply.frag");
    final int[] ids = new int[1];
    gl.glGenVertexArrays(1, ids, 0);
    this.emptyVao = ids[0];
  }

  /**
   * Binds the HDR target (allocating it for this size if needed), sets the viewport and clears it. With
   * {@code ambient} the opaque pass also writes its ambient light, for {@link #ambientOcclusion}.
   */
  void beginScene(GL3 gl, int w, int h, float r, float g, float b, boolean ambient) {
    ensureSize(gl, Math.max(1, w), Math.max(1, h));
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.sceneFbo);
    gl.glViewport(0, 0, this.width, this.height);
    gl.glClearColor(r, g, b, 1f);
    this.ambientTarget = ambient;
    drawBuffers(gl, ambient);
    gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
  }

  private static void drawBuffers(GL3 gl, boolean both) {
    gl.glDrawBuffers(2, new int[] { GL3.GL_COLOR_ATTACHMENT0, both ? GL3.GL_COLOR_ATTACHMENT1 : GL3.GL_NONE }, 0);
  }

  /**
   * After the opaque pass: darkens its ambient light where the depth buffer says little of the sky is in view, then
   * leaves the scene target bound with only its colour written (sky, transparency and particles have no ambient
   * share). Does nothing but the latter unless {@link #beginScene} was asked for the ambient target.
   */
  void ambientOcclusion(GL3 gl, RenderSettings settings, Matrix4f proj) {
    if (!this.ambientTarget) {
      return;
    }
    this.ambientTarget = false;
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(false);
    proj.invert(this.invProj);
    // occlusion at half size
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.aoFbo);
    gl.glViewport(0, 0, this.aoW, this.aoH);
    this.ssao.use(gl);
    this.ssao.set(gl, "uDepth", 0);
    this.ssao.set(gl, "uProj", proj);
    this.ssao.set(gl, "uInvProj", this.invProj);
    this.ssao.set(gl, "uTexel", 1f / this.width, 1f / this.height);
    this.ssao.set(gl, "uRadius", settings.aoRadius);
    bind(gl, 0, this.depth);
    drawFullscreen(gl);
    // blurred, keeping to surfaces
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.aoBlurFbo);
    this.ssaoBlur.use(gl);
    this.ssaoBlur.set(gl, "uAo", 0);
    this.ssaoBlur.set(gl, "uDepth", 1);
    this.ssaoBlur.set(gl, "uTexel", 1f / this.aoW, 1f / this.aoH);
    this.ssaoBlur.set(gl, "uInvProj", this.invProj);
    bind(gl, 0, this.aoTex);
    bind(gl, 1, this.depth);
    drawFullscreen(gl);
    // off the scene colour: colour - ambient * occluded
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.applyFbo);
    gl.glViewport(0, 0, this.width, this.height);
    if (!settings.debugAmbientOcclusion) {
      gl.glEnable(GL3.GL_BLEND);
      gl.glBlendEquation(GL3.GL_FUNC_REVERSE_SUBTRACT);
      gl.glBlendFunc(GL3.GL_ONE, GL3.GL_ONE);
    }
    this.ssaoApply.use(gl);
    this.ssaoApply.set(gl, "uDebug", settings.debugAmbientOcclusion ? 1 : 0);
    this.ssaoApply.set(gl, "uAo", 0);
    this.ssaoApply.set(gl, "uAmbient", 1);
    this.ssaoApply.set(gl, "uStrength", settings.aoStrength);
    bind(gl, 0, this.aoBlurTex);
    bind(gl, 1, this.sceneAmbient);
    drawFullscreen(gl);
    gl.glBlendEquation(GL3.GL_FUNC_ADD);
    gl.glDisable(GL3.GL_BLEND);
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(true);
    bindScene(gl);
  }

  /** Re-binds the HDR target (after something else, like shadow maps, used another framebuffer). */
  void bindScene(GL3 gl) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.sceneFbo);
    gl.glViewport(0, 0, this.width, this.height);
    drawBuffers(gl, this.ambientTarget);
  }

  /**
   * Switches to the transparency targets: accumulated colour and revealage in the first, weights in the second, with
   * blending set up for both (additive colour and weights, multiplicative revealage) and depth writes off.
   */
  void beginTransparent(GL3 gl) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.oitFbo);
    gl.glClearBufferfv(GL3.GL_COLOR, 0, CLEAR_ACCUM, 0);
    gl.glClearBufferfv(GL3.GL_COLOR, 1, CLEAR_WEIGHTS, 0);
    gl.glEnable(GL3.GL_BLEND);
    gl.glBlendFuncSeparate(GL3.GL_ONE, GL3.GL_ONE, GL3.GL_ZERO, GL3.GL_ONE_MINUS_SRC_ALPHA);
    gl.glDepthMask(false);
  }

  /** Resolves the transparent layers over the opaque scene. */
  void endTransparent(GL3 gl) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.sceneFbo);
    gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);
    gl.glDisable(GL3.GL_DEPTH_TEST);
    this.composite.use(gl);
    this.composite.set(gl, "uAccum", 0);
    this.composite.set(gl, "uWeights", 1);
    bind(gl, 0, this.oitAccum);
    bind(gl, 1, this.oitWeights);
    drawFullscreen(gl);
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(true);
    gl.glDisable(GL3.GL_BLEND);
  }

  int width() {
    return this.width;
  }

  int height() {
    return this.height;
  }

  /**
   * Copies the scene's depth into a texture that can be sampled while the scene target stays bound (soft particles),
   * re-binds the scene target and returns the copy.
   */
  int copyDepth(GL3 gl) {
    if (this.depthCopyFbo == 0) {
      this.depthCopy = texture(gl, this.width, this.height, GL3.GL_DEPTH_COMPONENT24, GL3.GL_DEPTH_COMPONENT,
          GL3.GL_FLOAT);
      gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
      gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);
      final int[] ids = new int[1];
      gl.glGenFramebuffers(1, ids, 0);
      this.depthCopyFbo = ids[0];
      gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.depthCopyFbo);
      gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_2D, this.depthCopy, 0);
      gl.glDrawBuffers(1, new int[] { GL3.GL_NONE }, 0);
      gl.glReadBuffer(GL3.GL_NONE);
    }
    gl.glBindFramebuffer(GL3.GL_READ_FRAMEBUFFER, this.sceneFbo);
    gl.glBindFramebuffer(GL3.GL_DRAW_FRAMEBUFFER, this.depthCopyFbo);
    gl.glBlitFramebuffer(0, 0, this.width, this.height, 0, 0, this.width, this.height, GL3.GL_DEPTH_BUFFER_BIT,
        GL3.GL_NEAREST);
    bindScene(gl);
    return this.depthCopy;
  }

  /** Bloom, tone mapping and FXAA into the default framebuffer. */
  void finish(GL3 gl, RenderSettings settings, int defaultFramebuffer) {
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(false);
    final boolean bloom = settings.bloomStrength > 0f && this.bloomLevels > 0;
    if (bloom) {
      bloom(gl, settings);
    }
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.ldrFbo);
    gl.glViewport(0, 0, this.width, this.height);
    this.tonemap.use(gl);
    this.tonemap.set(gl, "uScene", 0);
    this.tonemap.set(gl, "uBloom", 1);
    this.tonemap.set(gl, "uBloomStrength", bloom ? settings.bloomStrength : 0f);
    this.tonemap.set(gl, "uExposure", settings.exposure);
    bind(gl, 0, this.sceneColor);
    bind(gl, 1, bloom ? this.bloomTex[0] : this.sceneColor);
    drawFullscreen(gl);

    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, defaultFramebuffer);
    gl.glViewport(0, 0, this.width, this.height);
    this.fxaa.use(gl);
    this.fxaa.set(gl, "uImage", 0);
    this.fxaa.set(gl, "uTexel", 1f / this.width, 1f / this.height);
    this.fxaa.set(gl, "uEnabled", settings.antiAliasing ? 1 : 0);
    bind(gl, 0, this.ldrColor);
    drawFullscreen(gl);
    gl.glDepthMask(true);
    gl.glEnable(GL3.GL_DEPTH_TEST);
  }

  private void bloom(GL3 gl, RenderSettings settings) {
    this.down.use(gl);
    this.down.set(gl, "uSource", 0);
    this.down.set(gl, "uLimit", settings.bloomThreshold);
    this.down.set(gl, "uKnee", Math.max(1e-3f, settings.bloomThreshold * 0.5f));
    int source = this.sceneColor;
    int sw = this.width;
    int sh = this.height;
    for (int i = 0; i < this.bloomLevels; i++) {
      gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.bloomFbo[i]);
      gl.glViewport(0, 0, this.bloomW[i], this.bloomH[i]);
      this.down.set(gl, "uTexel", 1f / sw, 1f / sh);
      this.down.set(gl, "uThreshold", i == 0 ? 1 : 0);
      bind(gl, 0, source);
      drawFullscreen(gl);
      source = this.bloomTex[i];
      sw = this.bloomW[i];
      sh = this.bloomH[i];
    }
    this.up.use(gl);
    this.up.set(gl, "uSource", 0);
    this.up.set(gl, "uRadius", 1f);
    gl.glEnable(GL3.GL_BLEND);
    gl.glBlendFunc(GL3.GL_ONE, GL3.GL_ONE);
    for (int i = this.bloomLevels - 1; i > 0; i--) {
      gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.bloomFbo[i - 1]);
      gl.glViewport(0, 0, this.bloomW[i - 1], this.bloomH[i - 1]);
      this.up.set(gl, "uTexel", 1f / this.bloomW[i], 1f / this.bloomH[i]);
      bind(gl, 0, this.bloomTex[i]);
      drawFullscreen(gl);
    }
    gl.glDisable(GL3.GL_BLEND);
  }

  private void drawFullscreen(GL3 gl) {
    gl.glBindVertexArray(this.emptyVao);
    gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
    gl.glBindVertexArray(0);
  }

  private static void bind(GL3 gl, int unit, int texture) {
    gl.glActiveTexture(GL3.GL_TEXTURE0 + unit);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, texture);
  }

  // ---- targets -----------------------------------------------------------------------------------------------

  private void ensureSize(GL3 gl, int w, int h) {
    if (this.allocated && w == this.width && h == this.height) {
      return;
    }
    release(gl);
    this.width = w;
    this.height = h;
    this.sceneColor = texture(gl, w, h, GL3.GL_RGBA16F, GL3.GL_RGBA, GL3.GL_FLOAT);
    this.sceneAmbient = texture(gl, w, h, GL3.GL_RGBA16F, GL3.GL_RGBA, GL3.GL_FLOAT);
    this.depth = texture(gl, w, h, GL3.GL_DEPTH_COMPONENT24, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, this.depth); // read exactly by the occlusion passes
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);
    this.sceneFbo = framebuffer(gl, this.depth, this.sceneColor, this.sceneAmbient);
    this.applyFbo = framebuffer(gl, 0, this.sceneColor);
    this.aoW = Math.max(1, w / 2);
    this.aoH = Math.max(1, h / 2);
    this.aoTex = texture(gl, this.aoW, this.aoH, GL3.GL_R8, GL3.GL_RED, GL3.GL_UNSIGNED_BYTE);
    this.aoFbo = framebuffer(gl, 0, this.aoTex);
    this.aoBlurTex = texture(gl, this.aoW, this.aoH, GL3.GL_R8, GL3.GL_RED, GL3.GL_UNSIGNED_BYTE);
    this.aoBlurFbo = framebuffer(gl, 0, this.aoBlurTex);
    this.oitAccum = texture(gl, w, h, GL3.GL_RGBA16F, GL3.GL_RGBA, GL3.GL_FLOAT);
    this.oitWeights = texture(gl, w, h, GL3.GL_RGBA16F, GL3.GL_RGBA, GL3.GL_FLOAT);
    this.oitFbo = framebuffer(gl, this.depth, this.oitAccum, this.oitWeights);
    this.ldrColor = texture(gl, w, h, GL3.GL_RGBA8, GL3.GL_RGBA, GL3.GL_UNSIGNED_BYTE);
    this.ldrFbo = framebuffer(gl, 0, this.ldrColor);
    this.bloomLevels = 0;
    int bw = w;
    int bh = h;
    for (int i = 0; i < MAX_BLOOM_LEVELS; i++) {
      bw = Math.max(1, bw / 2);
      bh = Math.max(1, bh / 2);
      if (bw < 4 || bh < 4) {
        break;
      }
      this.bloomW[i] = bw;
      this.bloomH[i] = bh;
      // R11F_G11F_B10F would do, but RGBA16F is renderable everywhere GL 3.3 runs
      this.bloomTex[i] = texture(gl, bw, bh, GL3.GL_RGBA16F, GL3.GL_RGBA, GL3.GL_FLOAT);
      this.bloomFbo[i] = framebuffer(gl, 0, this.bloomTex[i]);
      this.bloomLevels++;
    }
    this.allocated = true;
  }

  private static int texture(GL3 gl, int w, int h, int internal, int format, int type) {
    final int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, ids[0]);
    gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
    return ids[0];
  }

  private static int framebuffer(GL3 gl, int depthTexture, int... colors) {
    final int[] ids = new int[1];
    gl.glGenFramebuffers(1, ids, 0);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, ids[0]);
    final int[] buffers = new int[colors.length];
    for (int i = 0; i < colors.length; i++) {
      gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0 + i, GL3.GL_TEXTURE_2D, colors[i], 0);
      buffers[i] = GL3.GL_COLOR_ATTACHMENT0 + i;
    }
    if (depthTexture != 0) {
      gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_2D, depthTexture, 0);
    }
    gl.glDrawBuffers(buffers.length, buffers, 0);
    final int status = gl.glCheckFramebufferStatus(GL3.GL_FRAMEBUFFER);
    if (status != GL3.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
    return ids[0];
  }

  private void release(GL3 gl) {
    if (!this.allocated) {
      return;
    }
    gl.glDeleteFramebuffers(6, new int[] { this.sceneFbo, this.oitFbo, this.ldrFbo, this.applyFbo, this.aoFbo,
        this.aoBlurFbo }, 0);
    gl.glDeleteTextures(8, new int[] { this.sceneColor, this.sceneAmbient, this.depth, this.oitAccum, this.oitWeights,
        this.ldrColor, this.aoTex, this.aoBlurTex }, 0);
    for (int i = 0; i < this.bloomLevels; i++) {
      gl.glDeleteFramebuffers(1, new int[] { this.bloomFbo[i] }, 0);
      gl.glDeleteTextures(1, new int[] { this.bloomTex[i] }, 0);
    }
    if (this.depthCopyFbo != 0) { // made on first use, at the current size
      gl.glDeleteFramebuffers(1, new int[] { this.depthCopyFbo }, 0);
      gl.glDeleteTextures(1, new int[] { this.depthCopy }, 0);
      this.depthCopyFbo = 0;
      this.depthCopy = 0;
    }
    this.bloomLevels = 0;
    this.allocated = false;
  }

  void dispose(GL3 gl) {
    if (this.down != null) {
      release(gl);
      this.down.dispose(gl);
      this.up.dispose(gl);
      this.tonemap.dispose(gl);
      this.fxaa.dispose(gl);
      this.composite.dispose(gl);
      gl.glDeleteVertexArrays(1, new int[] { this.emptyVao }, 0);
    }
  }
}
