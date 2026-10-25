package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.Arrays;

import org.joml.Matrix4f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.FontAtlas;
import engine.render.ImageData;
import engine.render.UiCanvas;

/** Batched 2D renderer: all quads (glyphs and rectangles) share one atlas texture and one draw call. */
final class GlUi implements UiCanvas {
  private static final int FLOATS_PER_VERTEX = 8; // x y u v r g b a

  private final FontAtlas atlas = new FontAtlas(16);
  private final float[] uv = new float[4];
  private final Matrix4f proj = new Matrix4f();
  private ShaderProgram shader;
  private GlTexture atlasTexture;
  private int vao;
  private int vbo;
  private float[] verts = new float[FLOATS_PER_VERTEX * 6 * 512];
  private int used;
  private FloatBuffer upload = Buffers.newDirectFloatBuffer(verts.length);

  void init(GL3 gl, java.util.function.Supplier<GL3> glSupplier) {
    this.shader = new ShaderProgram(gl, "shaders/ui.vert", "shaders/ui.frag");
    final var img = this.atlas.image();
    this.atlasTexture = new GlTexture(glSupplier, img);
    final int[] ids = new int[1];
    gl.glGenVertexArrays(1, ids, 0);
    this.vao = ids[0];
    gl.glBindVertexArray(this.vao);
    gl.glGenBuffers(1, ids, 0);
    this.vbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.vbo);
    final int stride = FLOATS_PER_VERTEX * Float.BYTES;
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 2, GL3.GL_FLOAT, false, stride, 0L);
    gl.glEnableVertexAttribArray(1);
    gl.glVertexAttribPointer(1, 2, GL3.GL_FLOAT, false, stride, 2L * Float.BYTES);
    gl.glEnableVertexAttribArray(2);
    gl.glVertexAttribPointer(2, 4, GL3.GL_FLOAT, false, stride, 4L * Float.BYTES);
    gl.glBindVertexArray(0);
  }

  void begin() {
    this.used = 0;
  }

  /** Draws everything queued since {@link #begin()}. */
  void flush(GL3 gl, int width, int height) {
    if (this.used == 0) {
      return;
    }
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glEnable(GL3.GL_BLEND);
    gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);

    this.shader.use(gl);
    this.proj.setOrtho(0f, width, height, 0f, -1f, 1f);
    this.shader.set(gl, "uProj", this.proj);
    this.shader.set(gl, "uAtlas", 0);
    this.atlasTexture.bind(gl, 0);

    if (this.upload.capacity() < this.used) {
      this.upload = Buffers.newDirectFloatBuffer(Math.max(this.used, this.upload.capacity() * 2));
    }
    this.upload.clear();
    this.upload.put(this.verts, 0, this.used).flip();
    gl.glBindVertexArray(this.vao);
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.vbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) this.used * Float.BYTES, this.upload, GL3.GL_STREAM_DRAW);
    gl.glDrawArrays(GL3.GL_TRIANGLES, 0, this.used / FLOATS_PER_VERTEX);
    gl.glBindVertexArray(0);

    gl.glDisable(GL3.GL_BLEND);
    gl.glEnable(GL3.GL_DEPTH_TEST);
  }

  void dispose(GL3 gl) {
    if (this.shader != null) {
      this.shader.dispose(gl);
      this.atlasTexture.dispose();
      gl.glDeleteVertexArrays(1, new int[] { this.vao }, 0);
      gl.glDeleteBuffers(1, new int[] { this.vbo }, 0);
    }
  }

  private void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, float r, float g,
      float b, float a) {
    if (this.used + 6 * FLOATS_PER_VERTEX > this.verts.length) {
      this.verts = Arrays.copyOf(this.verts, this.verts.length * 2);
    }
    vertex(x, y, u0, v0, r, g, b, a);
    vertex(x + w, y, u1, v0, r, g, b, a);
    vertex(x + w, y + h, u1, v1, r, g, b, a);
    vertex(x, y, u0, v0, r, g, b, a);
    vertex(x + w, y + h, u1, v1, r, g, b, a);
    vertex(x, y + h, u0, v1, r, g, b, a);
  }

  private void vertex(float x, float y, float u, float v, float r, float g, float b, float a) {
    final float[] d = this.verts;
    int i = this.used;
    d[i++] = x;
    d[i++] = y;
    d[i++] = u;
    d[i++] = v;
    d[i++] = r;
    d[i++] = g;
    d[i++] = b;
    d[i++] = a;
    this.used = i;
  }

  @Override
  public void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
    this.atlas.solidUv(this.uv);
    quad(x, y, w, h, this.uv[0], this.uv[1], this.uv[2], this.uv[3], r, g, b, a);
  }

  @Override
  public void drawText(String text, float x, float y, float scale, float r, float g, float b, float a) {
    final float pad = this.atlas.cellPad() * scale;
    final float cw = this.atlas.cellWidth() * scale;
    final float ch = this.atlas.cellHeight() * scale;
    float cx = x;
    float cy = y;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '\n') {
        cx = x;
        cy += lineHeight(scale);
        continue;
      }
      if (c != ' ') {
        this.atlas.glyphUv(c, this.uv);
        quad(cx - pad, cy - pad, cw, ch, this.uv[0], this.uv[1], this.uv[2], this.uv[3], r, g, b, a);
      }
      cx += this.atlas.advance() * scale;
    }
  }

  @Override
  public float textWidth(String text, float scale) {
    float widest = 0;
    for (String line : text.split("\n", -1)) {
      widest = Math.max(widest, line.length() * this.atlas.advance() * scale);
    }
    return widest;
  }

  @Override
  public float lineHeight(float scale) {
    return this.atlas.lineHeight() * scale;
  }
}
