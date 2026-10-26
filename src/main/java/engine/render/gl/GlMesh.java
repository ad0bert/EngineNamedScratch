package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.GpuMesh;
import engine.render.MeshData;
import engine.render.RenderQueue;
import lombok.Getter;

/**
 * VAO with static vertex/index buffers (position 0, normal 1, uv 2, tangent 10; joints 11 and weights 12 for skinned
 * meshes) plus a dynamic per-instance buffer holding a model and a normal matrix per instance (attribute locations
 * 3-9), so every draw is an instanced draw.
 */
final class GlMesh implements GpuMesh {
  private static final int INSTANCE_STRIDE = RenderQueue.FLOATS_PER_INSTANCE * Float.BYTES;
  private static final AtomicInteger NEXT_ID = new AtomicInteger();

  @Getter private final int id = NEXT_ID.incrementAndGet();
  private final Supplier<GL3> glSupplier;
  @Getter private final MeshData data;
  private final int vao;
  private final int vbo;
  private final int ebo;
  private final int skinVbo;
  private final int instanceVbo;
  private final int mode;

  GlMesh(Supplier<GL3> glSupplier, MeshData data) {
    this.glSupplier = glSupplier;
    this.data = data;
    this.mode = data.drawMode() == MeshData.DrawMode.POINTS ? GL3.GL_POINTS : GL3.GL_TRIANGLES;
    final var gl = glSupplier.get();

    final int[] ids = new int[1];
    gl.glGenVertexArrays(1, ids, 0);
    this.vao = ids[0];
    gl.glBindVertexArray(this.vao);

    gl.glGenBuffers(1, ids, 0);
    this.vbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.vbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) data.vertices().length * Float.BYTES,
        Buffers.newDirectFloatBuffer(data.vertices()), GL3.GL_STATIC_DRAW);
    final int stride = MeshData.STRIDE_BYTES;
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, stride, 0L);
    gl.glEnableVertexAttribArray(1);
    gl.glVertexAttribPointer(1, 3, GL3.GL_FLOAT, false, stride, 3L * Float.BYTES);
    gl.glEnableVertexAttribArray(2);
    gl.glVertexAttribPointer(2, 2, GL3.GL_FLOAT, false, stride, 6L * Float.BYTES);
    gl.glEnableVertexAttribArray(10);
    gl.glVertexAttribPointer(10, 4, GL3.GL_FLOAT, false, stride, (long) MeshData.TANGENT_OFFSET * Float.BYTES);

    if (data.skin() != null) {
      gl.glGenBuffers(1, ids, 0);
      this.skinVbo = ids[0];
      gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.skinVbo);
      gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) data.skin().length * Float.BYTES,
          Buffers.newDirectFloatBuffer(data.skin()), GL3.GL_STATIC_DRAW);
      final int skinStride = MeshData.SKIN_FLOATS_PER_VERTEX * Float.BYTES;
      gl.glEnableVertexAttribArray(11);
      gl.glVertexAttribPointer(11, 4, GL3.GL_FLOAT, false, skinStride, 0L);
      gl.glEnableVertexAttribArray(12);
      gl.glVertexAttribPointer(12, 4, GL3.GL_FLOAT, false, skinStride, 4L * Float.BYTES);
    } else {
      this.skinVbo = 0;
    }

    if (data.indices() != null) {
      gl.glGenBuffers(1, ids, 0);
      this.ebo = ids[0];
      gl.glBindBuffer(GL3.GL_ELEMENT_ARRAY_BUFFER, this.ebo);
      gl.glBufferData(GL3.GL_ELEMENT_ARRAY_BUFFER, (long) data.indices().length * Integer.BYTES,
          Buffers.newDirectIntBuffer(data.indices()), GL3.GL_STATIC_DRAW);
    } else {
      this.ebo = 0;
    }

    // per instance: mat4 model (locations 3-6) followed by mat3 normal matrix (locations 7-9)
    gl.glGenBuffers(1, ids, 0);
    this.instanceVbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.instanceVbo);
    for (int col = 0; col < 4; col++) {
      gl.glEnableVertexAttribArray(3 + col);
      gl.glVertexAttribPointer(3 + col, 4, GL3.GL_FLOAT, false, INSTANCE_STRIDE, (long) col * 16);
      gl.glVertexAttribDivisor(3 + col, 1);
    }
    for (int col = 0; col < 3; col++) {
      gl.glEnableVertexAttribArray(7 + col);
      gl.glVertexAttribPointer(7 + col, 3, GL3.GL_FLOAT, false, INSTANCE_STRIDE, 64L + col * 12L);
      gl.glVertexAttribDivisor(7 + col, 1);
    }
    gl.glBindVertexArray(0);
  }

  /**
   * Draws {@code count} instances; {@code instances} holds count * {@link RenderQueue#FLOATS_PER_INSTANCE} floats
   * (position 0, limit set accordingly).
   */
  void draw(GL3 gl, FloatBuffer instances, int count) {
    gl.glBindVertexArray(this.vao);
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.instanceVbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) count * INSTANCE_STRIDE, instances, GL3.GL_STREAM_DRAW);
    if (this.data.indices() != null) {
      gl.glDrawElementsInstanced(this.mode, this.data.indices().length, GL3.GL_UNSIGNED_INT, 0L, count);
    } else {
      gl.glDrawArraysInstanced(this.mode, 0, this.data.vertexCount(), count);
    }
    gl.glBindVertexArray(0);
  }

  @Override
  public void dispose() {
    final var gl = this.glSupplier.get();
    gl.glDeleteVertexArrays(1, new int[] { this.vao }, 0);
    gl.glDeleteBuffers(1, new int[] { this.vbo }, 0);
    gl.glDeleteBuffers(1, new int[] { this.instanceVbo }, 0);
    if (this.ebo != 0) {
      gl.glDeleteBuffers(1, new int[] { this.ebo }, 0);
    }
    if (this.skinVbo != 0) {
      gl.glDeleteBuffers(1, new int[] { this.skinVbo }, 0);
    }
  }
}
