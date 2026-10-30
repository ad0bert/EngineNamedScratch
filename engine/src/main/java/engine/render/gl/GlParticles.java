package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.ecs.Scene;
import engine.render.ParticleEmitter;
import engine.render.RenderStats;

/**
 * Draws every {@link ParticleEmitter} in view, after the transparent geometry: the emitters back to front, each one
 * instanced call of camera-facing quads. All particles of a frame go into one buffer upload; blending is premultiplied
 * (additive particles carry an alpha of 0), depth is tested but not written. Soft particles read a copy of the opaque
 * depth, made only when an emitter in view needs it.
 */
final class GlParticles {
  private static final int STRIDE = ParticleEmitter.FLOATS_PER_INSTANCE * Float.BYTES;

  private ShaderProgram program;
  private int vao;
  private int quadVbo;
  private int instanceVbo;
  private FloatBuffer buffer = Buffers.newDirectFloatBuffer(ParticleEmitter.FLOATS_PER_INSTANCE * 1024);

  private final FrustumIntersection frustum = new FrustumIntersection();
  private final List<ParticleEmitter> visible = new ArrayList<>();
  private final Matrix4f world = new Matrix4f();
  private final Vector3f center = new Vector3f();
  private long[] order = new long[16];
  private int[] first = new int[16];
  private int[] counts = new int[16];

  void init(GL3 gl) {
    this.program = new ShaderProgram(gl, "shaders/particle.vert", "shaders/particle.frag");
    final int[] ids = new int[1];
    gl.glGenVertexArrays(1, ids, 0);
    this.vao = ids[0];
    gl.glBindVertexArray(this.vao);
    gl.glGenBuffers(1, ids, 0);
    this.quadVbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.quadVbo);
    final float[] corners = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f }; // a triangle strip, counter-clockwise
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, corners.length * 4L, Buffers.newDirectFloatBuffer(corners),
        GL3.GL_STATIC_DRAW);
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 2, GL3.GL_FLOAT, false, 8, 0L);
    gl.glGenBuffers(1, ids, 0);
    this.instanceVbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.instanceVbo);
    for (int loc = 1; loc <= 3; loc++) {
      gl.glEnableVertexAttribArray(loc);
      gl.glVertexAttribDivisor(loc, 1);
    }
    gl.glBindVertexArray(0);
  }

  /**
   * Draws the particles into the bound HDR target. {@code post} provides the depth copy for soft particles;
   * {@code white} stands in for missing textures.
   */
  void draw(GL3 gl, Scene scene, float alpha, Matrix4f viewProj, Vector3f cameraPos, Vector3f forward,
      Vector3f right, Vector3f up, float near, float far, PostProcessor post, GlTexture white, RenderStats stats,
      GlSceneRenderer lighting) {
    final var all = scene.components(ParticleEmitter.class);
    if (all.isEmpty()) {
      return;
    }
    this.frustum.set(viewProj);
    this.visible.clear();
    boolean soft = false;
    for (int i = 0; i < all.size(); i++) {
      final var e = all.get(i);
      if (e.count() == 0) {
        continue;
      }
      e.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      final float radius = e.bounds(this.world, this.center);
      if (radius < 0f || !this.frustum.testSphere(this.center.x, this.center.y, this.center.z, radius)) {
        continue;
      }
      if (this.order.length == this.visible.size()) {
        this.order = Arrays.copyOf(this.order, this.order.length * 2);
      }
      final float dist = this.center.distance(cameraPos);
      // farthest first (see ParticleEmitter.writeInstances for the key)
      this.order[this.visible.size()] = ((long) (Integer.MAX_VALUE - Float.floatToIntBits(dist)) << 32)
          | this.visible.size();
      this.visible.add(e);
      soft |= e.softness > 0f;
    }
    final int n = this.visible.size();
    if (n == 0) {
      return;
    }
    Arrays.sort(this.order, 0, n);
    if (this.first.length < n) {
      this.first = new int[Math.max(n, this.first.length * 2)];
      this.counts = new int[this.first.length];
    }

    // every particle of the frame into one buffer, emitter after emitter in drawing order
    int total = 0;
    for (int k = 0; k < n; k++) {
      total += this.visible.get((int) this.order[k]).count();
    }
    final int floats = total * ParticleEmitter.FLOATS_PER_INSTANCE;
    if (this.buffer.capacity() < floats) {
      this.buffer = Buffers.newDirectFloatBuffer(Math.max(floats, this.buffer.capacity() * 2));
    }
    this.buffer.clear();
    int written = 0;
    for (int k = 0; k < n; k++) {
      final var e = this.visible.get((int) this.order[k]);
      e.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      this.first[k] = written;
      this.counts[k] = e.writeInstances(alpha, this.world, cameraPos, forward, this.buffer,
          written * ParticleEmitter.FLOATS_PER_INSTANCE);
      written += this.counts[k];
    }
    this.buffer.position(0).limit(written * ParticleEmitter.FLOATS_PER_INSTANCE);

    final int depthCopy = soft ? post.copyDepth(gl) : 0;
    this.program.use(gl);
    this.program.set(gl, "uViewProj", viewProj);
    this.program.set(gl, "uCameraPos", cameraPos);
    this.program.set(gl, "uCameraForward", forward);
    this.program.set(gl, "uCameraRight", right);
    this.program.set(gl, "uCameraUp", up);
    this.program.set(gl, "uTexture", 0);
    this.program.set(gl, "uDepth", 1);
    this.program.set(gl, "uInvScreen", 1f / post.width(), 1f / post.height());
    this.program.set(gl, "uNearFar", near, far);
    gl.glActiveTexture(GL3.GL_TEXTURE1);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, depthCopy);
    lighting.bindSky(gl, this.program); // units 2-4: the sky's light for lit particles

    gl.glBindVertexArray(this.vao);
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.instanceVbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, (long) written * STRIDE, this.buffer, GL3.GL_STREAM_DRAW);
    gl.glEnable(GL3.GL_BLEND);
    gl.glBlendFunc(GL3.GL_ONE, GL3.GL_ONE_MINUS_SRC_ALPHA);
    gl.glDepthMask(false);
    for (int k = 0; k < n; k++) {
      if (this.counts[k] == 0) {
        continue;
      }
      final var e = this.visible.get((int) this.order[k]);
      (e.texture != null ? (GlTexture) e.texture : white).bind(gl, 0);
      this.program.set(gl, "uHasTexture", e.texture != null ? 1 : 0);
      this.program.set(gl, "uAtlas", Math.max(1, e.atlasColumns), Math.max(1, e.atlasRows));
      this.program.set(gl, "uSoftness", soft ? Math.max(0f, e.softness) : 0f);
      final float lit = Math.max(0f, Math.min(1f, e.lit));
      this.program.set(gl, "uLit", lit);
      if (lit > 0f) {
        e.entity().transform().interpolatedWorldMatrix(alpha, this.world);
        e.bounds(this.world, this.center);
        this.program.set(gl, "uLight", lighting.lightAt(this.center, this.light));
      }
      final long base = (long) this.first[k] * STRIDE;
      gl.glVertexAttribPointer(1, 4, GL3.GL_FLOAT, false, STRIDE, base);
      gl.glVertexAttribPointer(2, 4, GL3.GL_FLOAT, false, STRIDE, base + 16L);
      gl.glVertexAttribPointer(3, 2, GL3.GL_FLOAT, false, STRIDE, base + 32L);
      gl.glDrawArraysInstanced(GL3.GL_TRIANGLE_STRIP, 0, 4, this.counts[k]);
      stats.drawCalls++;
    }
    gl.glDepthMask(true);
    gl.glDisable(GL3.GL_BLEND);
    gl.glBindVertexArray(0);
    stats.particleEmitters = n;
    stats.particles = written;
  }

  private final Vector3f light = new Vector3f();

  void dispose(GL3 gl) {
    if (this.program != null) {
      this.program.dispose(gl);
      gl.glDeleteVertexArrays(1, new int[] { this.vao }, 0);
      gl.glDeleteBuffers(2, new int[] { this.quadVbo, this.instanceVbo }, 0);
    }
  }
}
