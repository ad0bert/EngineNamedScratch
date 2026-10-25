package engine.render;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

/** GPU mesh (VAO + VBO + optional EBO) created from {@link MeshData}. Must be used on the GL thread. */
public class Mesh {
  private final MeshData data;
  private final int vao;
  private final int vbo;
  private final int ebo;

  public Mesh(GL3 gl, MeshData data) {
    this.data = data;
    final int[] ids = new int[2];
    gl.glGenVertexArrays(1, ids, 0);
    this.vao = ids[0];
    gl.glBindVertexArray(this.vao);

    gl.glGenBuffers(2, ids, 0);
    this.vbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.vbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) data.vertices().length * Float.BYTES,
        Buffers.newDirectFloatBuffer(data.vertices()), GL3.GL_STATIC_DRAW);

    if (data.indices() != null) {
      this.ebo = ids[1];
      gl.glBindBuffer(GL3.GL_ELEMENT_ARRAY_BUFFER, this.ebo);
      gl.glBufferData(GL3.GL_ELEMENT_ARRAY_BUFFER, (long) data.indices().length * Integer.BYTES,
          Buffers.newDirectIntBuffer(data.indices()), GL3.GL_STATIC_DRAW);
    } else {
      this.ebo = 0;
      gl.glDeleteBuffers(1, ids, 1);
    }

    final int stride = MeshData.STRIDE_BYTES;
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, stride, 0L);
    gl.glEnableVertexAttribArray(1);
    gl.glVertexAttribPointer(1, 3, GL3.GL_FLOAT, false, stride, 3L * Float.BYTES);
    gl.glEnableVertexAttribArray(2);
    gl.glVertexAttribPointer(2, 2, GL3.GL_FLOAT, false, stride, 6L * Float.BYTES);
    gl.glBindVertexArray(0);
  }

  public MeshData data() {
    return this.data;
  }

  public void draw(GL3 gl) {
    gl.glBindVertexArray(this.vao);
    if (this.data.indices() != null) {
      gl.glDrawElements(this.data.drawMode(), this.data.indices().length, GL3.GL_UNSIGNED_INT, 0L);
    } else {
      gl.glDrawArrays(this.data.drawMode(), 0, this.data.vertexCount());
    }
    gl.glBindVertexArray(0);
  }

  public void dispose(GL3 gl) {
    gl.glDeleteVertexArrays(1, new int[] { this.vao }, 0);
    gl.glDeleteBuffers(1, new int[] { this.vbo }, 0);
    if (this.ebo != 0) {
      gl.glDeleteBuffers(1, new int[] { this.ebo }, 0);
    }
  }
}
