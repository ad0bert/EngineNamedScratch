package engine.render.gl;

import com.jogamp.opengl.GL3;

/**
 * Depth-only 2D texture array (one layer per shadow cascade, or six per point light cube), rendered through one
 * framebuffer whose depth attachment is switched layer by layer. The depth texture is hardware-compared, so it is read
 * through a {@code sampler2DArrayShadow} (with bilinear filtering that gives 2x2 percentage-closer filtering for free).
 */
final class ShadowMapArray {
  private final int size;
  private final int layers;
  private final int fbo;
  private final int texture;

  /**
   * @param clampToEdge repeat the edge texels outside the map (cube faces, whose neighbours continue the scene)
   *                    instead of reading "lit" there (cascades, outside which nothing was rendered)
   */
  ShadowMapArray(GL3 gl, int size, int layers, boolean clampToEdge) {
    this.size = size;
    this.layers = layers;
    final int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    this.texture = ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_2D_ARRAY, this.texture);
    gl.glTexImage3D(GL3.GL_TEXTURE_2D_ARRAY, 0, GL3.GL_DEPTH_COMPONENT24, size, size, layers, 0,
        GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, null);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    final int wrap = clampToEdge ? GL3.GL_CLAMP_TO_EDGE : GL3.GL_CLAMP_TO_BORDER;
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_S, wrap);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D_ARRAY, GL3.GL_TEXTURE_WRAP_T, wrap);
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

  int layers() {
    return this.layers;
  }

  /** Starts rendering into one cascade: binds the framebuffer, attaches the layer, clears its depth. */
  void beginLayer(GL3 gl, int layer) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glFramebufferTextureLayer(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, this.texture, 0, layer);
    gl.glViewport(0, 0, this.size, this.size);
    gl.glClear(GL3.GL_DEPTH_BUFFER_BIT);
  }

  /** Leaves the framebuffer bound; the caller binds its own target next. */
  void end(GL3 gl) {
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
