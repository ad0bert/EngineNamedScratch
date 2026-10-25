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
 * {@link RenderQueue}; this class only issues GL calls: cascaded shadow maps for the first shadow-casting directional
 * light, then opaque geometry batched by (mesh, material) with GPU instancing, then transparent geometry back to
 * front. Per-frame work reuses its lists, buffers and matrices.
 *
 * <p>
 * Texture units: 0 albedo, 1 shadow cascades (array texture), 2 normal map.
 */
final class GlSceneRenderer {
  private static final int SHADOW_SIZE = 2048;
  private static final boolean DEBUG_CASCADES = Boolean.getBoolean("engine.debugCascades");

  private final RenderStats stats;
  private final RenderQueue queue = new RenderQueue();
  private ShaderProgram basic;
  private ShaderProgram depth;
  private CascadedShadowMap shadowMap;
  private GlTexture white;

  private final List<MeshRenderer> visible = new ArrayList<>();
  private final List<MeshRenderer> casters = new ArrayList<>();
  private final List<MeshRenderer> opaque = new ArrayList<>();
  private final List<MeshRenderer> transparent = new ArrayList<>();
  private FloatBuffer instanceBuffer = Buffers.newDirectFloatBuffer(RenderQueue.FLOATS_PER_INSTANCE * 256);

  private final Matrix4f view = new Matrix4f();
  private final Matrix4f proj = new Matrix4f();
  private final Matrix4f viewProj = new Matrix4f();
  private final Matrix4f world = new Matrix4f();
  private final Matrix4f[] cascadeMatrix = new Matrix4f[Light.MAX_CASCADES];
  private final float[] cascadeSplit = new float[Light.MAX_CASCADES];
  private final float[] cascadeTexel = new float[Light.MAX_CASCADES];
  private final Vector3f cameraPos = new Vector3f();
  private final Vector3f cameraForward = new Vector3f();
  private final Vector3f cameraRight = new Vector3f();
  private final Vector3f cameraUp = new Vector3f();
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
    for (int i = 0; i < this.cascadeMatrix.length; i++) {
      this.cascadeMatrix[i] = new Matrix4f();
    }
  }

  void init(GL3 gl, GlTexture white) {
    this.white = white;
    this.basic = new ShaderProgram(gl, "shaders/basic.vert", "shaders/basic.frag");
    this.depth = new ShaderProgram(gl, "shaders/depth.vert", "shaders/depth.frag");
    this.shadowMap = new CascadedShadowMap(gl, SHADOW_SIZE, Light.MAX_CASCADES);
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
    final float aspect = (float) width / Math.max(height, 1);
    camera.viewMatrix(alpha, this.view);
    camera.projectionMatrix(aspect, this.proj);
    this.proj.mul(this.view, this.viewProj);
    camera.entity().transform().interpolatedWorldMatrix(alpha, this.world);
    this.world.getTranslation(this.cameraPos);
    this.world.transformDirection(this.cameraForward.set(0f, 0f, -1f)).normalize();
    this.world.transformDirection(this.cameraRight.set(1f, 0f, 0f)).normalize();
    this.world.transformDirection(this.cameraUp.set(0f, 1f, 0f)).normalize();

    this.queue.collect(scene, alpha, this.cameraPos);
    prepareLights(scene, alpha);

    int cascades = 0;
    if (this.shadowLight != null) {
      cascades = shadowPasses(gl, camera, aspect, alpha, width, height);
    }
    mainPass(gl, cascades);
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

  /** Renders every cascade into its layer; returns the number of cascades (0 if the range is empty). */
  private int shadowPasses(GL3 gl, Camera camera, float aspect, float alpha, int width, int height) {
    final Light light = this.shadowLight;
    final int count = Math.max(1, Math.min(Light.MAX_CASCADES, light.shadowCascades));
    final float near = camera.near;
    final float far = Math.min(camera.far, light.shadowDistance);
    if (far <= near * 1.001f) {
      return 0;
    }
    final float[] splits = ShadowMath.cascadeSplits(near, far, count, light.cascadeLambda);
    light.entity().transform().interpolatedWorldMatrix(alpha, this.world);
    this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize();
    final float fovY = (float) Math.toRadians(camera.fovDegrees);

    gl.glEnable(GL3.GL_DEPTH_CLAMP); // casters closer to the light than the near plane still shadow the cascade
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(1.5f, 2f);
    this.depth.use(gl);
    int totalCasters = 0;
    for (int i = 0; i < count; i++) {
      final float d0 = i == 0 ? near : splits[i - 1];
      final float radius = ShadowMath.sliceBoundingSphere(this.cameraPos, this.cameraRight, this.cameraUp,
          this.cameraForward, fovY, aspect, d0, splits[i], this.focus);
      ShadowMath.cascadeViewProj(this.direction, this.focus, radius, SHADOW_SIZE, this.cascadeMatrix[i]);
      this.cascadeSplit[i] = splits[i];
      this.cascadeTexel[i] = 2f * radius / SHADOW_SIZE;

      this.queue.cull(this.cascadeMatrix[i], RenderQueue.Pass.SHADOW, this.casters);
      totalCasters += this.casters.size();
      RenderQueue.sortForBatching(this.casters, false);
      this.shadowMap.beginLayer(gl, i);
      this.depth.set(gl, "uLightViewProj", this.cascadeMatrix[i]);
      drawList(gl, this.casters, false, true);
    }
    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glDisable(GL3.GL_DEPTH_CLAMP);
    this.shadowMap.end(gl, width, height);
    this.stats.shadowCasters = totalCasters;
    this.stats.shadowCascades = count;
    return count;
  }

  private void mainPass(GL3 gl, int cascades) {
    this.basic.use(gl);
    this.basic.set(gl, "uViewProj", this.viewProj);
    this.basic.set(gl, "uCameraPos", this.cameraPos);
    this.basic.set(gl, "uAmbient", this.ambient);
    this.basic.set(gl, "uLightCount", this.lightCount);
    this.basic.setVec4Array(gl, "uLightPos", this.lightPos, this.lightCount);
    this.basic.setVec4Array(gl, "uLightColor", this.lightColor, this.lightCount);
    this.basic.set(gl, "uCascadeCount", cascades);
    if (cascades > 0) {
      this.basic.setMat4Array(gl, "uCascadeMatrix", this.cascadeMatrix, cascades);
      this.basic.setFloatArray(gl, "uCascadeSplit", this.cascadeSplit, cascades);
      this.basic.setFloatArray(gl, "uCascadeTexel", this.cascadeTexel, cascades);
    }
    this.basic.set(gl, "uShadowTexel", 1f / SHADOW_SIZE);
    this.basic.set(gl, "uDebugCascades", DEBUG_CASCADES ? 1 : 0);
    this.basic.set(gl, "uTexture", 0);
    this.basic.set(gl, "uShadowMap", 1);
    this.basic.set(gl, "uNormalMap", 2);
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
    if (m.normalMap != null) {
      ((GlTexture) m.normalMap).bind(gl, 2);
      this.basic.set(gl, "uHasNormalMap", 1);
      this.basic.set(gl, "uNormalScale", m.normalScale);
      this.basic.set(gl, "uNormalFlipY", m.normalMapFlipY ? -1f : 1f);
    } else {
      this.white.bind(gl, 2); // keep the sampler valid even though it is not read
      this.basic.set(gl, "uHasNormalMap", 0);
    }
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
