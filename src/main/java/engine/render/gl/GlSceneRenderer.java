package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.ecs.Scene;
import engine.render.Camera;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshRenderer;
import engine.render.RenderQueue;
import engine.render.RenderStats;
import engine.render.ShadowMath;

/**
 * Forward scene renderer. All scene bookkeeping (interpolated matrices, caching, culling) lives in
 * {@link RenderQueue}; this class only issues GL calls: a shadow map for the first shadow-casting directional light,
 * then opaque geometry batched by (mesh, material) with GPU instancing, then transparent geometry back to front.
 * Per-frame work reuses its lists, buffers and matrices.
 */
final class GlSceneRenderer {
  private static final int SHADOW_SIZE = 2048;

  private final RenderStats stats;
  private final RenderQueue queue = new RenderQueue();
  private ShaderProgram basic;
  private ShaderProgram depth;
  private ShadowMap shadowMap;
  private GlTexture white;

  private final List<MeshRenderer> visible = new ArrayList<>();
  private final List<MeshRenderer> casters = new ArrayList<>();
  private final List<MeshRenderer> opaque = new ArrayList<>();
  private final List<MeshRenderer> transparent = new ArrayList<>();
  private FloatBuffer instanceBuffer = Buffers.newDirectFloatBuffer(RenderQueue.FLOATS_PER_INSTANCE * 256);

  private final Matrix4f view = new Matrix4f();
  private final Matrix4f proj = new Matrix4f();
  private final Matrix4f viewProj = new Matrix4f();
  private final Matrix4f lightViewProj = new Matrix4f();
  private final Matrix4f identity = new Matrix4f();
  private final Matrix4f world = new Matrix4f();
  private final Vector3f cameraPos = new Vector3f();
  private final Vector3f cameraForward = new Vector3f();
  private final Vector3f focus = new Vector3f();
  private final Vector3f direction = new Vector3f();
  private final Vector3f ambient = new Vector3f(0.25f, 0.25f, 0.28f);

  private final float[] lightPos = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightColor = new float[Light.MAX_LIGHTS * 4];
  private final List<Light> orderedLights = new ArrayList<>();
  private int lightCount;
  private Light shadowLight;

  GlSceneRenderer(RenderStats stats) {
    this.stats = stats;
  }

  void init(GL3 gl, GlTexture white) {
    this.white = white;
    this.basic = new ShaderProgram(gl, "shaders/basic.vert", "shaders/basic.frag");
    this.depth = new ShaderProgram(gl, "shaders/depth.vert", "shaders/depth.frag");
    this.shadowMap = new ShadowMap(gl, SHADOW_SIZE);
    gl.glClearColor(0.53f, 0.7f, 0.9f, 1f);
    gl.glClearDepth(1.0);
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glDepthFunc(GL3.GL_LEQUAL);
    gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
  }

  void render(GL3 gl, Scene scene, float alpha, int width, int height) {
    gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
    final var activeCamera = scene.activeCamera();
    if (activeCamera.isEmpty()) {
      return;
    }
    final Camera camera = activeCamera.get();
    camera.viewMatrix(alpha, this.view);
    camera.projectionMatrix((float) width / Math.max(height, 1), this.proj);
    this.proj.mul(this.view, this.viewProj);
    camera.entity().transform().interpolatedWorldMatrix(alpha, this.world);
    this.world.getTranslation(this.cameraPos);
    this.world.transformDirection(this.cameraForward.set(0f, 0f, -1f)).normalize();

    this.queue.collect(scene, alpha, this.cameraPos);
    prepareLights(scene, alpha);

    final boolean shadows = this.shadowLight != null;
    if (shadows) {
      final float half = this.shadowLight.shadowDistance;
      this.focus.set(this.cameraPos).fma(half * 0.4f, this.cameraForward);
      this.shadowLight.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize();
      ShadowMath.lightViewProj(this.direction, this.focus, half, SHADOW_SIZE, this.lightViewProj);
      shadowPass(gl, width, height);
    }
    mainPass(gl, shadows);
  }

  private void prepareLights(Scene scene, float alpha) {
    final List<Light> all = scene.components(Light.class);
    this.shadowLight = null;
    for (int i = 0; i < all.size(); i++) {
      final Light l = all.get(i);
      if (l.type == Light.Type.DIRECTIONAL && l.castShadows) {
        this.shadowLight = l;
        break;
      }
    }
    this.orderedLights.clear();
    if (this.shadowLight != null) {
      this.orderedLights.add(this.shadowLight); // index 0 is the shadowed one
    }
    for (int i = 0; i < all.size() && this.orderedLights.size() < Light.MAX_LIGHTS; i++) {
      if (all.get(i) != this.shadowLight) {
        this.orderedLights.add(all.get(i));
      }
    }
    this.lightCount = this.orderedLights.size();
    for (int i = 0; i < this.lightCount; i++) {
      final Light l = this.orderedLights.get(i);
      l.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      if (l.type == Light.Type.DIRECTIONAL) {
        this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize().negate();
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, 0f);
      } else {
        this.world.getTranslation(this.direction);
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, 1f);
      }
      put(this.lightColor, i, l.color.x * l.intensity, l.color.y * l.intensity, l.color.z * l.intensity,
          Math.max(l.range, 0.01f));
    }
    if (this.lightCount == 0) { // no lights in the scene: fall back to a default sun so nothing renders black
      this.lightCount = 1;
      this.direction.set(0.4f, 0.8f, 0.5f).normalize();
      put(this.lightPos, 0, this.direction.x, this.direction.y, this.direction.z, 0f);
      put(this.lightColor, 0, 1f, 1f, 1f, 1f);
      this.ambient.set(0.25f, 0.25f, 0.28f);
    } else {
      this.ambient.set(this.orderedLights.get(0).ambient);
    }
    this.stats.lights = this.lightCount;
  }

  private static void put(float[] a, int i, float x, float y, float z, float w) {
    a[i * 4] = x;
    a[i * 4 + 1] = y;
    a[i * 4 + 2] = z;
    a[i * 4 + 3] = w;
  }

  private void shadowPass(GL3 gl, int width, int height) {
    this.queue.cull(this.lightViewProj, RenderQueue.Pass.SHADOW, this.casters);
    this.stats.shadowCasters = this.casters.size();
    RenderQueue.sortForBatching(this.casters, false);

    this.shadowMap.beginPass(gl);
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(2f, 4f);
    this.depth.use(gl);
    this.depth.set(gl, "uLightViewProj", this.lightViewProj);
    drawList(gl, this.casters, false, true);
    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
    this.shadowMap.endPass(gl, width, height);
  }

  private void mainPass(GL3 gl, boolean shadows) {
    this.basic.use(gl);
    this.basic.set(gl, "uViewProj", this.viewProj);
    this.basic.set(gl, "uCameraPos", this.cameraPos);
    this.basic.set(gl, "uAmbient", this.ambient);
    this.basic.set(gl, "uLightCount", this.lightCount);
    this.basic.setVec4Array(gl, "uLightPos", this.lightPos, this.lightCount);
    this.basic.setVec4Array(gl, "uLightColor", this.lightColor, this.lightCount);
    this.basic.set(gl, "uShadowEnabled", shadows ? 1 : 0);
    this.basic.set(gl, "uShadowViewProj", shadows ? this.lightViewProj : this.identity);
    this.basic.set(gl, "uShadowTexel", 1f / SHADOW_SIZE);
    this.basic.set(gl, "uTexture", 0);
    this.basic.set(gl, "uShadowMap", 1);
    this.shadowMap.bindTexture(gl, 1);

    this.queue.cull(this.viewProj, RenderQueue.Pass.MAIN, this.visible);
    this.stats.culled = this.queue.candidates() - this.visible.size();
    this.opaque.clear();
    this.transparent.clear();
    for (int i = 0; i < this.visible.size(); i++) {
      final MeshRenderer r = this.visible.get(i);
      (r.material.transparent ? this.transparent : this.opaque).add(r);
    }
    RenderQueue.sortForBatching(this.opaque, true);
    drawList(gl, this.opaque, true, true);

    if (!this.transparent.isEmpty()) {
      RenderQueue.sortBackToFront(this.transparent);
      gl.glEnable(GL3.GL_BLEND);
      gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);
      gl.glDepthMask(false);
      drawList(gl, this.transparent, true, false); // sorted individually: no batching
      gl.glDepthMask(true);
      gl.glDisable(GL3.GL_BLEND);
    }
  }

  /** Draws a sorted list; runs of the same mesh (and material) become one instanced call when {@code batch}. */
  private void drawList(GL3 gl, List<MeshRenderer> list, boolean byMaterial, boolean batch) {
    final int n = list.size();
    int i = 0;
    while (i < n) {
      final MeshRenderer first = list.get(i);
      int j = i + 1;
      if (batch) {
        while (j < n && RenderQueue.sameBatch(first, list.get(j), byMaterial)) {
          j++;
        }
      }
      final int count = j - i;
      if (byMaterial) {
        applyMaterial(gl, first.material);
      }
      final int floats = count * RenderQueue.FLOATS_PER_INSTANCE;
      if (this.instanceBuffer.capacity() < floats) {
        this.instanceBuffer = Buffers.newDirectFloatBuffer(Math.max(floats, this.instanceBuffer.capacity() * 2));
      }
      this.instanceBuffer.clear();
      for (int k = 0; k < count; k++) {
        RenderQueue.writeInstance(list.get(i + k), this.instanceBuffer, k);
      }
      this.instanceBuffer.position(0).limit(floats);
      ((GlMesh) first.mesh).draw(gl, this.instanceBuffer, count);
      this.stats.drawCalls++;
      this.stats.instances += count;
      i = j;
    }
  }

  private void applyMaterial(GL3 gl, Material m) {
    final GlTexture tex = m.texture != null ? (GlTexture) m.texture : this.white;
    tex.bind(gl, 0);
    this.basic.set(gl, "uColor", m.color);
    this.basic.set(gl, "uSpecular", m.specular);
    this.basic.set(gl, "uShininess", m.shininess);
    this.basic.set(gl, "uUnlit", m.unlit ? 1 : 0);
    this.basic.set(gl, "uPointSize", m.pointSize);
  }

  void dispose(GL3 gl) {
    if (this.basic != null) {
      this.basic.dispose(gl);
      this.depth.dispose(gl);
      this.shadowMap.dispose(gl);
    }
  }
}
