package engine.render.gl;

import com.jogamp.opengl.GL3;

/**
 * Depth-only 2D texture array with one layer per cascade, rendered through one framebuffer whose depth attachment is
 * switched layer by layer. The depth texture is hardware-compared, so it is read through a {@code sampler2DArrayShadow}
 * (with bilinear filtering that gives 2x2 percentage-closer filtering for free).
 */
final class CascadedShadowMap {
  private final int size;
  private final int fbo;
  private final int texture;

  CascadedShadowMap(GL3 gl, int size, int layers) {
    this.size = size;
    final int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    this.texture = ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, this.texture);
    gl.glTexImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, GL3.GL_DEPTH_COMPONENT24, size, size, layers, 0,
        GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, null);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_BORDER);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_BORDER);
    gl.glTexParameterfv(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_BORDER_COLOR, new float[] { 1f, 1f, 1f, 1f }, 0);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_COMPARE_MODE, GL3.GL_COMPARE_REF_TO_TEXTURE);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_COMPARE_FUNC, GL3.GL_LEQUAL);

    gl.glGenFramebuffers(1, ids, 0);
    this.fbo = ids[0];
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glFramebufferTextureLayer(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, this.texture, 0, 0);
    gl.glDrawBuffers(1, new int[] { GL3.GL_NONE }, 0);
    gl.glReadBuffer(GL3.GL_NONE);
    final int status = gl.glCheckFramebufferStatus(GL3.GL_FRAMEBUFFER);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, gl.getContext().getDefaultDrawFramebuffer());
    if (status != GL3.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Shadow framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
  }

  int size() {
    return this.size;
  }

  /** Starts rendering into one cascade: binds the framebuffer, attaches the layer, clears its depth. */
  void beginLayer(GL3 gl, int layer) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glFramebufferTextureLayer(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, this.texture, 0, layer);
    gl.glViewport(0, 0, this.size, this.size);
    gl.glClear(GL3.GL_DEPTH_BUFFER_BIT);
  }

  void end(GL3 gl, int viewportWidth, int viewportHeight) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, gl.getContext().getDefaultDrawFramebuffer());
    gl.glViewport(0, 0, viewportWidth, viewportHeight);
  }

  void bindTexture(GL3 gl, int unit) {
    gl.glActiveTexture(GL3.GL_TEXTURE0 + unit);
    gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, this.texture);
  }

  void dispose(GL3 gl) {
    gl.glDeleteFramebuffers(1, new int[] { this.fbo }, 0);
    gl.glDeleteTextures(1, new int[] { this.texture }, 0);
  }
}
