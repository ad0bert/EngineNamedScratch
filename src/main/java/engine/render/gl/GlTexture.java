package engine.render.gl;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import com.jogamp.opengl.GL3;

import engine.render.GpuTexture;
import engine.render.ImageData;

final class GlTexture implements GpuTexture {
  private final Supplier<GL3> glSupplier;
  private final int id;

  GlTexture(Supplier<GL3> glSupplier, ImageData image) {
    this.glSupplier = glSupplier;
    final var gl = glSupplier.get();
    final int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    this.id = ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_2D, this.id);
    gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 1);
    gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_RGBA8, image.width(), image.height(), 0, GL3.GL_RGBA,
        GL3.GL_UNSIGNED_BYTE, ByteBuffer.wrap(image.rgba()));
    gl.glGenerateMipmap(GL3.GL_TEXTURE_2D);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_REPEAT);
  }

  void bind(GL3 gl, int unit) {
    gl.glActiveTexture(GL3.GL_TEXTURE0 + unit);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, this.id);
  }

  @Override
  public void dispose() {
    this.glSupplier.get().glDeleteTextures(1, new int[] { this.id }, 0);
  }
}
