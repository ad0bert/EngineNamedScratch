package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.joml.Matrix4f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.FontAtlas;
import engine.render.UiCanvas;
import engine.render.UiFont;

/**
 * Batched 2D renderer. Every font (and size) has its own atlas texture, which also holds a white block for rectangles;
 * quads are collected in call order and drawn with one draw call per run of the same texture and clip rectangle (a
 * scissor test), all from one vertex buffer. Atlases that gained glyphs during the frame are uploaded again before
 * drawing.
 */
final class GlUi implements UiCanvas {
  private static final int FLOATS_PER_VERTEX = 8; // x y u v r g b a

  /** A font's atlas and its texture. */
  private static final class Face {
    final FontAtlas atlas;
    final GlTexture texture;
    int uploadedVersion;

    Face(FontAtlas atlas, GlTexture texture) {
      this.atlas = atlas;
      this.texture = texture;
      this.uploadedVersion = atlas.version();
    }
  }

  private final Map<UiFont, Face> faces = new HashMap<>();
  private final List<Face> batchFace = new ArrayList<>(); // texture of each batch
  private int[] batchStart = new int[16]; // first vertex of each batch
  private float[] batchClip = new float[16 * 4]; // x, y, w, h of each batch; w < 0: no clip
  private final float[] clipStack = new float[64 * 4];
  private int clipDepth;
  private final float[] uv = new float[4];
  private final Matrix4f proj = new Matrix4f();
  private Supplier<GL3> glSupplier;
  private ShaderProgram shader;
  private Face current;
  private int vao;
  private int vbo;
  private float[] verts = new float[FLOATS_PER_VERTEX * 6 * 512];
  private int used;
  private FloatBuffer upload = Buffers.newDirectFloatBuffer(this.verts.length);

  void init(GL3 gl, Supplier<GL3> glSupplier) {
    this.glSupplier = glSupplier;
    this.shader = new ShaderProgram(gl, "shaders/ui.vert", "shaders/ui.frag");
    face(UiFont.DEFAULT);
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

  private Face face(UiFont font) {
    var f = this.faces.get(font);
    if (f == null) {
      final var atlas = new FontAtlas(font);
      f = new Face(atlas, new GlTexture(this.glSupplier, atlas.image(), false)); // the UI draws after tone mapping
      this.faces.put(font, f);
    }
    return f;
  }

  void begin() {
    this.used = 0;
    this.batchFace.clear();
    this.current = null;
    this.clipDepth = 0;
  }

  /** Switches to the font's texture, starting a new batch if it differs from the current one. */
  private Face use(UiFont font) {
    final var f = face(font);
    if (f != this.current) {
      startBatch(f);
    }
    return f;
  }

  private void startBatch(Face f) {
    final int n = this.batchFace.size();
    if (n == this.batchStart.length) {
      this.batchStart = Arrays.copyOf(this.batchStart, n * 2);
      this.batchClip = Arrays.copyOf(this.batchClip, n * 8);
    }
    this.batchStart[n] = this.used / FLOATS_PER_VERTEX;
    if (this.clipDepth > 0) {
      System.arraycopy(this.clipStack, (this.clipDepth - 1) * 4, this.batchClip, n * 4, 4);
    } else {
      this.batchClip[n * 4 + 2] = -1f;
    }
    this.batchFace.add(f);
    this.current = f;
  }

  @Override
  public void pushClip(float x, float y, float w, float h) {
    float x0 = x;
    float y0 = y;
    float x1 = x + Math.max(0f, w);
    float y1 = y + Math.max(0f, h);
    if (this.clipDepth > 0) { // intersect with the enclosing clip
      final int o = (this.clipDepth - 1) * 4;
      x0 = Math.max(x0, this.clipStack[o]);
      y0 = Math.max(y0, this.clipStack[o + 1]);
      x1 = Math.min(x1, this.clipStack[o] + this.clipStack[o + 2]);
      y1 = Math.min(y1, this.clipStack[o + 1] + this.clipStack[o + 3]);
    }
    if (this.clipDepth * 4 >= this.clipStack.length) {
      throw new IllegalStateException("UI clips nested too deeply");
    }
    final int o = this.clipDepth * 4;
    this.clipStack[o] = x0;
    this.clipStack[o + 1] = y0;
    this.clipStack[o + 2] = Math.max(0f, x1 - x0);
    this.clipStack[o + 3] = Math.max(0f, y1 - y0);
    this.clipDepth++;
    startBatch(this.current != null ? this.current : face(UiFont.DEFAULT));
  }

  @Override
  public void popClip() {
    if (this.clipDepth > 0) {
      this.clipDepth--;
      startBatch(this.current != null ? this.current : face(UiFont.DEFAULT));
    }
  }

  /** Draws everything queued since {@link #begin()}. */
  void flush(GL3 gl, int width, int height) {
    if (this.used == 0) {
      return;
    }
    for (Face f : this.faces.values()) {
      if (f.uploadedVersion != f.atlas.version()) {
        f.texture.upload(gl, f.atlas.image());
        f.uploadedVersion = f.atlas.version();
      }
    }
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glEnable(GL3.GL_BLEND);
    gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);

    this.shader.use(gl);
    this.proj.setOrtho(0f, width, height, 0f, -1f, 1f);
    this.shader.set(gl, "uProj", this.proj);
    this.shader.set(gl, "uAtlas", 0);

    if (this.upload.capacity() < this.used) {
      this.upload = Buffers.newDirectFloatBuffer(Math.max(this.used, this.upload.capacity() * 2));
    }
    this.upload.clear();
    this.upload.put(this.verts, 0, this.used).flip();
    gl.glBindVertexArray(this.vao);
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.vbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) this.used * Float.BYTES, this.upload, GL3.GL_STREAM_DRAW);
    final int total = this.used / FLOATS_PER_VERTEX;
    for (int b = 0; b < this.batchFace.size(); b++) {
      final int start = this.batchStart[b];
      final int end = b + 1 < this.batchFace.size() ? this.batchStart[b + 1] : total;
      if (end > start) {
        if (this.batchClip[b * 4 + 2] >= 0f) {
          gl.glEnable(GL3.GL_SCISSOR_TEST);
          final int x = (int) Math.floor(this.batchClip[b * 4]);
          final int y = (int) Math.floor(this.batchClip[b * 4 + 1]);
          final int w = (int) Math.ceil(this.batchClip[b * 4 + 2]);
          final int h = (int) Math.ceil(this.batchClip[b * 4 + 3]);
          gl.glScissor(x, height - y - h, w, h); // GL counts rows from the bottom
        } else {
          gl.glDisable(GL3.GL_SCISSOR_TEST);
        }
        this.batchFace.get(b).texture.bind(gl, 0);
        gl.glDrawArrays(GL3.GL_TRIANGLES, start, end - start);
      }
    }
    gl.glDisable(GL3.GL_SCISSOR_TEST);
    gl.glBindVertexArray(0);

    gl.glDisable(GL3.GL_BLEND);
    gl.glEnable(GL3.GL_DEPTH_TEST);
  }

  void dispose(GL3 gl) {
    if (this.shader != null) {
      this.shader.dispose(gl);
      this.faces.values().forEach(f -> f.texture.dispose());
      this.faces.clear();
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
    // any atlas has the white block: stay in the current batch
    final var f = this.current != null ? this.current : use(UiFont.DEFAULT);
    f.atlas.solidUv(this.uv);
    quad(x, y, w, h, this.uv[0], this.uv[1], this.uv[2], this.uv[3], r, g, b, a);
  }

  /** The font at the size it will appear in, so scaled text is rasterized sharp instead of stretched. */
  private static UiFont sized(UiFont font, float scale) {
    if (scale == 1f) {
      return font;
    }
    return new UiFont(font.family(), font.style(), Math.max(1, Math.round(font.size() * scale)));
  }

  /** What is left of the scale after picking the sized font (close to 1). */
  private static float residual(UiFont font, UiFont sized, float scale) {
    return scale * font.size() / sized.size();
  }

  @Override
  public void drawText(UiFont font, String text, float x, float y, float scale, float r, float g, float b,
      float a) {
    final var sized = sized(font, scale);
    drawSized(sized, text, x, y, residual(font, sized, scale), r, g, b, a);
  }

  private void drawSized(UiFont font, String text, float x, float y, float scale, float r, float g, float b,
      float a) {
    final var atlas = use(font).atlas;
    final float pad = atlas.padding() * scale;
    final float lineHeight = atlas.lineHeight() * scale;
    float cx = x;
    float cy = y;
    for (int i = 0; i < text.length();) {
      final int c = text.codePointAt(i);
      i += Character.charCount(c);
      if (c == '\n') {
        cx = x;
        cy += lineHeight;
        continue;
      }
      final var glyph = atlas.glyph(c);
      if (c != ' ') {
        atlas.glyphUv(glyph, this.uv);
        quad(cx - pad, cy - pad, glyph.width() * scale, glyph.height() * scale, this.uv[0], this.uv[1], this.uv[2],
            this.uv[3], r, g, b, a);
      }
      cx += glyph.advance() * scale;
    }
  }

  @Override
  public float textWidth(UiFont font, String text, float scale) {
    final var sized = sized(font, scale);
    return measure(face(sized).atlas, text) * residual(font, sized, scale);
  }

  /** Width of the widest line, in pixels at scale 1. */
  static float measure(FontAtlas atlas, String text) {
    float widest = 0f;
    float line = 0f;
    for (int i = 0; i < text.length();) {
      final int c = text.codePointAt(i);
      i += Character.charCount(c);
      if (c == '\n') {
        widest = Math.max(widest, line);
        line = 0f;
      } else {
        line += atlas.advance(c);
      }
    }
    return Math.max(widest, line);
  }

  @Override
  public float lineHeight(UiFont font, float scale) {
    final var sized = sized(font, scale);
    return face(sized).atlas.lineHeight() * residual(font, sized, scale);
  }
}
