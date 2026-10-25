package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.ecs.Scene;
import engine.render.Camera;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshRenderer;
import engine.render.RenderStats;
import engine.render.ShadowMath;

/**
 * Forward scene renderer. Per frame: collect renderables at the interpolated state, frustum-cull, render a shadow map
 * for the first shadow-casting directional light, then draw opaque geometry batched by (mesh, material) with GPU
 * instancing, then transparent geometry sorted back to front.
 */
final class GlSceneRenderer {
  private static final int SHADOW_SIZE = 2048;

  private static final class Item {
    MeshRenderer renderer;
    GlMesh mesh;
    final Matrix4f model = new Matrix4f();
    final Vector3f center = new Vector3f();
    float radius;
    float distSq;
  }

  private record BatchKey(GlMesh mesh, Material material) {
  }

  private final RenderStats stats;
  private ShaderProgram basic;
  private ShaderProgram depth;
  private ShadowMap shadowMap;
  private GlTexture white;

  private final List<Item> pool = new ArrayList<>();
  private int itemCount;
  private FloatBuffer instanceBuffer = Buffers.newDirectFloatBuffer(16 * 256);

  private final Matrix4f view = new Matrix4f();
  private final Matrix4f proj = new Matrix4f();
  private final Matrix4f viewProj = new Matrix4f();
  private final Matrix4f lightViewProj = new Matrix4f();
  private final Matrix4f identity = new Matrix4f();
  private final FrustumIntersection cameraFrustum = new FrustumIntersection();
  private final FrustumIntersection lightFrustum = new FrustumIntersection();
  private final Vector3f cameraPos = new Vector3f();
  private final Vector3f ambient = new Vector3f(0.25f, 0.25f, 0.28f);

  private final float[] lightPos = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightColor = new float[Light.MAX_LIGHTS * 4];
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
    this.cameraFrustum.set(this.viewProj);
    final Matrix4f camWorld = camera.entity().transform().interpolatedWorldMatrix(alpha, new Matrix4f());
    camWorld.getTranslation(this.cameraPos);
    final Vector3f camForward = camWorld.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();

    collectItems(scene, alpha);
    prepareLights(scene, alpha);

    final boolean shadows = this.shadowLight != null;
    if (shadows) {
      final float half = this.shadowLight.shadowDistance;
      final Vector3f focus = this.cameraPos.fma(half * 0.4f, camForward, new Vector3f());
      final Vector3f dir = this.shadowLight.entity().transform().interpolatedWorldMatrix(alpha, new Matrix4f())
          .transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
      ShadowMath.lightViewProj(dir, focus, half, SHADOW_SIZE, this.lightViewProj);
      shadowPass(gl, width, height);
    }

    mainPass(gl, shadows);
  }

  private Item obtain() {
    if (this.itemCount == this.pool.size()) {
      this.pool.add(new Item());
    }
    return this.pool.get(this.itemCount++);
  }

  private void collectItems(Scene scene, float alpha) {
    this.itemCount = 0;
    for (MeshRenderer r : scene.components(MeshRenderer.class)) {
      final Item it = obtain();
      it.renderer = r;
      it.mesh = (GlMesh) r.mesh;
      r.entity().transform().interpolatedWorldMatrix(alpha, it.model);
      it.radius = ShadowMath.worldSphere(r.mesh.data(), it.model, it.center);
      it.distSq = it.center.distanceSquared(this.cameraPos);
    }
  }

  private void prepareLights(Scene scene, float alpha) {
    final List<Light> all = scene.components(Light.class);
    this.shadowLight = null;
    for (Light l : all) {
      if (l.type == Light.Type.DIRECTIONAL && l.castShadows) {
        this.shadowLight = l;
        break;
      }
    }
    final List<Light> ordered = new ArrayList<>();
    if (this.shadowLight != null) {
      ordered.add(this.shadowLight); // index 0 is the shadowed one
    }
    for (Light l : all) {
      if (l != this.shadowLight && ordered.size() < Light.MAX_LIGHTS) {
        ordered.add(l);
      }
    }
    this.lightCount = ordered.size();
    final Matrix4f m = new Matrix4f();
    final Vector3f v = new Vector3f();
    for (int i = 0; i < ordered.size(); i++) {
      final Light l = ordered.get(i);
      l.entity().transform().interpolatedWorldMatrix(alpha, m);
      if (l.type == Light.Type.DIRECTIONAL) {
        m.transformDirection(v.set(0f, 0f, -1f)).normalize().negate();
        put(this.lightPos, i, v.x, v.y, v.z, 0f);
      } else {
        m.getTranslation(v);
        put(this.lightPos, i, v.x, v.y, v.z, 1f);
      }
      put(this.lightColor, i, l.color.x * l.intensity, l.color.y * l.intensity, l.color.z * l.intensity,
          Math.max(l.range, 0.01f));
    }
    if (ordered.isEmpty()) { // no lights in the scene: fall back to a default sun so nothing renders black
      this.lightCount = 1;
      v.set(0.4f, 0.8f, 0.5f).normalize();
      put(this.lightPos, 0, v.x, v.y, v.z, 0f);
      put(this.lightColor, 0, 1f, 1f, 1f, 1f);
      this.ambient.set(0.25f, 0.25f, 0.28f);
    } else {
      this.ambient.set(ordered.get(0).ambient);
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
    this.lightFrustum.set(this.lightViewProj);
    final List<Item> casters = new ArrayList<>();
    for (int i = 0; i < this.itemCount; i++) {
      final Item it = this.pool.get(i);
      final MeshRenderer r = it.renderer;
      if (r.castShadows && !r.material.transparent && it.mesh.data().drawMode() == MeshData.DrawMode.TRIANGLES
          && this.lightFrustum.testSphere(it.center.x, it.center.y, it.center.z, it.radius)) {
        casters.add(it);
      }
    }
    this.stats.shadowCasters = casters.size();

    this.shadowMap.beginPass(gl);
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(2f, 4f);
    this.depth.use(gl);
    this.depth.set(gl, "uLightViewProj", this.lightViewProj);
    drawBatches(gl, casters, false);
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

    final List<Item> opaque = new ArrayList<>();
    final List<Item> transparent = new ArrayList<>();
    for (int i = 0; i < this.itemCount; i++) {
      final Item it = this.pool.get(i);
      if (!this.cameraFrustum.testSphere(it.center.x, it.center.y, it.center.z, it.radius)) {
        this.stats.culled++;
      } else if (it.renderer.material.transparent) {
        transparent.add(it);
      } else {
        opaque.add(it);
      }
    }
    drawBatches(gl, opaque, true);

    if (!transparent.isEmpty()) {
      transparent.sort(Comparator.comparingDouble((Item it) -> it.distSq).reversed());
      gl.glEnable(GL3.GL_BLEND);
      gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);
      gl.glDepthMask(false);
      drawBatches(gl, transparent, true, false); // sorted individually: no batching
      gl.glDepthMask(true);
      gl.glDisable(GL3.GL_BLEND);
    }
  }

  private void drawBatches(GL3 gl, List<Item> items, boolean byMaterial) {
    drawBatches(gl, items, byMaterial, true);
  }

  private void drawBatches(GL3 gl, List<Item> items, boolean byMaterial, boolean batch) {
    final Map<Object, List<Item>> groups = new LinkedHashMap<>();
    int unique = 0;
    for (Item it : items) {
      final Object key = !batch ? Integer.valueOf(unique++)
          : new BatchKey(it.mesh, byMaterial ? it.renderer.material : null);
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
    }
    for (List<Item> group : groups.values()) {
      final Item first = group.get(0);
      if (byMaterial) {
        applyMaterial(gl, first.renderer.material);
      }
      final int count = group.size();
      if (this.instanceBuffer.capacity() < count * 16) {
        this.instanceBuffer = Buffers.newDirectFloatBuffer(Math.max(count * 16, this.instanceBuffer.capacity() * 2));
      }
      this.instanceBuffer.clear();
      for (int i = 0; i < count; i++) {
        group.get(i).model.get(i * 16, this.instanceBuffer);
      }
      this.instanceBuffer.position(0).limit(count * 16);
      first.mesh.draw(gl, this.instanceBuffer, count);
      this.stats.drawCalls++;
      this.stats.instances += count;
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
