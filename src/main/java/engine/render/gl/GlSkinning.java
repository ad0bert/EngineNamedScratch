package engine.render.gl;

import java.nio.FloatBuffer;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.Skin;

/**
 * Joint matrices for GPU skinning, in a texture buffer (RGBA32F, four texels per matrix) that the vertex shaders read
 * with {@code texelFetch}: unlike a uniform array it has no size limit worth worrying about. The buffer stays bound
 * to its own texture unit; each skinned draw re-specifies its contents (orphaning the old storage, so the GPU never
 * waits) and switches the shader's {@code uSkinned} flag.
 */
final class GlSkinning {
  /** Kept away from the material and shadow units. */
  static final int UNIT = 15;

  private int buffer;
  private int texture;
  private FloatBuffer data = Buffers.newDirectFloatBuffer(16 * 64);

  /** Creates the buffer and points the {@code uJoints} sampler of every skinning-capable program at it. */
  void init(GL3 gl, ShaderProgram... programs) {
    final int[] id = new int[1];
    gl.glGenBuffers(1, id, 0);
    this.buffer = id[0];
    gl.glBindBuffer(GL3.GL_TEXTURE_BUFFER, this.buffer);
    final var identity = Buffers.newDirectFloatBuffer(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 });
    gl.glBufferData(GL3.GL_TEXTURE_BUFFER, 16L * Float.BYTES, identity, GL3.GL_STREAM_DRAW);
    gl.glGenTextures(1, id, 0);
    this.texture = id[0];
    gl.glActiveTexture(GL3.GL_TEXTURE0 + UNIT);
    gl.glBindTexture(GL3.GL_TEXTURE_BUFFER, this.texture);
    gl.glTexBuffer(GL3.GL_TEXTURE_BUFFER, GL3.GL_RGBA32F, this.buffer);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
    gl.glBindBuffer(GL3.GL_TEXTURE_BUFFER, 0);
    for (ShaderProgram p : programs) {
      p.use(gl);
      p.set(gl, "uJoints", UNIT);
      p.set(gl, "uSkinned", 0);
    }
  }

  /** Prepares the bound program for a draw of a renderer with this skin (null: not skinned). */
  void apply(GL3 gl, ShaderProgram program, Skin skin) {
    if (skin == null) {
      program.set(gl, "uSkinned", 0);
      return;
    }
    final float[] palette = skin.palette();
    if (this.data.capacity() < palette.length) {
      this.data = Buffers.newDirectFloatBuffer(Math.max(palette.length, this.data.capacity() * 2));
    }
    this.data.clear();
    this.data.put(palette).flip();
    gl.glBindBuffer(GL3.GL_TEXTURE_BUFFER, this.buffer);
    gl.glBufferData(GL3.GL_TEXTURE_BUFFER, (long) palette.length * Float.BYTES, this.data, GL3.GL_STREAM_DRAW);
    gl.glBindBuffer(GL3.GL_TEXTURE_BUFFER, 0);
    program.set(gl, "uSkinned", 1);
  }

  void dispose(GL3 gl) {
    if (this.buffer != 0) {
      gl.glDeleteBuffers(1, new int[] { this.buffer }, 0);
      gl.glDeleteTextures(1, new int[] { this.texture }, 0);
      this.buffer = 0;
    }
  }
}
