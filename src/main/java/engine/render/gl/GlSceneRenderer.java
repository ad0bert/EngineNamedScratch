package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
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
import engine.render.RenderSettings;
import engine.render.RenderStats;
import engine.render.ShadowMath;

/**
 * Forward scene renderer. All scene bookkeeping (interpolated matrices, caching, culling) lives in
 * {@link RenderQueue}; this class only issues GL calls, per frame:
 * <ol>
 * <li>cascaded shadow maps for the first shadow-casting directional light, and a cube of six shadow maps for each
 * shadow-casting point light (up to {@link RenderSettings#maxPointShadows});</li>
 * <li>opaque geometry batched by (mesh, material) with GPU instancing, skipping static cells that were occluded in the
 * last frame;</li>
 * <li>occlusion queries for the visible static cells (read back in the next frame, so the GPU is never waited on);</li>
 * <li>transparent geometry back to front, still instanced where neighbours in that order share mesh and material.</li>
 * </ol>
 * Per-frame work reuses its lists, buffers and matrices.
 *
 * <p>
 * Texture units: 0 albedo, 1 shadow cascades, 2 normal map, 3 specular map, 4 emissive map, 5 height map, 6 point
 * light shadow cubes.
 */
final class GlSceneRenderer {
  private static final int UNIT_ALBEDO = 0;
  private static final int UNIT_CASCADES = 1;
  private static final int UNIT_NORMAL = 2;
  private static final int UNIT_SPECULAR = 3;
  private static final int UNIT_EMISSIVE = 4;
  private static final int UNIT_HEIGHT = 5;
  private static final int UNIT_POINT_SHADOWS = 6;
  /** Mirrors MAX_POINT_SHADOWS in basic.frag. */
  private static final int MAX_POINT_SHADOWS = 4;
  /** Casters up to this far towards the light from a cascade still shadow it. */
  private static final float CASTER_REACH = 500f;

  /** Shadow casters: by mesh, and only cut-out materials need to be told apart (they sample their texture). */
  private static final Comparator<MeshRenderer> SHADOW_ORDER = Comparator
      .comparingInt((MeshRenderer r) -> r.mesh.id()).thenComparingInt(GlSceneRenderer::shadowMaterialKey);

  private final RenderStats stats;
  private final RenderSettings settings;
  private final RenderQueue queue = new RenderQueue();
  private ShaderProgram basic;
  private ShaderProgram depth;
  private ShaderProgram cutout;
  private ShaderProgram box;
  private ShadowMapArray shadowMap;
  private ShadowMapArray pointShadowMap;
  private GlTexture white;
  private GlTexture flatNormal;

  private final List<MeshRenderer> visible = new ArrayList<>();
  private final List<MeshRenderer> casters = new ArrayList<>();
  private final List<MeshRenderer> opaque = new ArrayList<>();
  private final List<MeshRenderer> transparent = new ArrayList<>();
  private FloatBuffer instanceBuffer = Buffers.newDirectFloatBuffer(RenderQueue.FLOATS_PER_INSTANCE * 256);

  private final Matrix4f view = new Matrix4f();
  private final Matrix4f proj = new Matrix4f();
  private final Matrix4f viewProj = new Matrix4f();
  private final Matrix4f world = new Matrix4f();
  private final Matrix4f cullVolume = new Matrix4f();
  private final Matrix4f faceMatrix = new Matrix4f();
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
  private float cameraNear;

  private final float[] lightPos = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightColor = new float[Light.MAX_LIGHTS * 4];
  private final int[] lightShadow = new int[Light.MAX_LIGHTS];
  private final float[] pointShadowRange = new float[MAX_POINT_SHADOWS * 2];
  private final List<Light> orderedLights = new ArrayList<>();
  private final List<Light> pointShadowLights = new ArrayList<>();
  private int lightCount;
  private Light shadowLight;

  // occlusion queries issued last frame: the cell each one tested and the query object
  private int boxVao;
  private int boxVbo;
  private int boxEbo;
  private int[] queryPool = new int[0];
  private int queryPoolUsed;
  private Object[] pendingKeys = new Object[64];
  private int[] pendingQueries = new int[64];
  private int pendingCount;
  private final int[] result = new int[1];

  GlSceneRenderer(RenderStats stats, RenderSettings settings) {
    this.stats = stats;
    this.settings = settings;
    for (int i = 0; i < this.cascadeMatrix.length; i++) {
      this.cascadeMatrix[i] = new Matrix4f();
    }
  }

  void init(GL3 gl, GlTexture white, GlTexture flatNormal) {
    this.white = white;
    this.flatNormal = flatNormal;
    this.basic = new ShaderProgram(gl, "shaders/basic.vert", "shaders/basic.frag");
    this.depth = new ShaderProgram(gl, "shaders/depth.vert", "shaders/depth.frag");
    this.cutout = new ShaderProgram(gl, "shaders/cutout.vert", "shaders/cutout.frag");
    this.box = new ShaderProgram(gl, "shaders/box.vert", "shaders/box.frag");
    // placeholders until a light needs them; the samplers must always have a texture of their type bound
    this.shadowMap = new ShadowMapArray(gl, 1, 1, false);
    this.pointShadowMap = new ShadowMapArray(gl, 1, 6, true);
    createUnitBox(gl);
    gl.glClearColor(0.53f, 0.7f, 0.9f, 1f);
    gl.glClearDepth(1.0);
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glDepthFunc(GL3.GL_LEQUAL);
    gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
  }

  private void createUnitBox(GL3 gl) {
    final float[] corners = new float[24];
    for (int i = 0; i < 8; i++) {
      corners[i * 3] = i & 1;
      corners[i * 3 + 1] = (i >> 1) & 1;
      corners[i * 3 + 2] = (i >> 2) & 1;
    }
    final int[] faces = { 0, 2, 3, 0, 3, 1, 4, 5, 7, 4, 7, 6, 0, 1, 5, 0, 5, 4, 2, 6, 7, 2, 7, 3, 0, 4, 6, 0, 6, 2, 1,
        3, 7, 1, 7, 5 };
    final int[] ids = new int[1];
    gl.glGenVertexArrays(1, ids, 0);
    this.boxVao = ids[0];
    gl.glBindVertexArray(this.boxVao);
    gl.glGenBuffers(1, ids, 0);
    this.boxVbo = ids[0];
    gl.glBindBuffer(GL3.GL_ARRAY_BUFFER, this.boxVbo);
    gl.glBufferData(GL3.GL_ARRAY_BUFFER, corners.length * 4L, Buffers.newDirectFloatBuffer(corners),
        GL3.GL_STATIC_DRAW);
    gl.glEnableVertexAttribArray(0);
    gl.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, 12, 0L);
    gl.glGenBuffers(1, ids, 0);
    this.boxEbo = ids[0];
    gl.glBindBuffer(GL3.GL_ELEMENT_ARRAY_BUFFER, this.boxEbo);
    gl.glBufferData(GL3.GL_ELEMENT_ARRAY_BUFFER, faces.length * 4L, Buffers.newDirectIntBuffer(faces),
        GL3.GL_STATIC_DRAW);
    gl.glBindVertexArray(0);
  }

  void render(GL3 gl, Scene scene, float alpha, int width, int height) {
    readOcclusionResults(gl);
    gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
    final var activeCamera = scene.activeCamera();
    if (activeCamera.isEmpty()) {
      return;
    }
    final var camera = activeCamera.get();
    final float aspect = (float) width / Math.max(height, 1);
    camera.viewMatrix(alpha, this.view);
    camera.projectionMatrix(aspect, this.proj);
    this.proj.mul(this.view, this.viewProj);
    camera.entity().transform().interpolatedWorldMatrix(alpha, this.world);
    this.world.getTranslation(this.cameraPos);
    this.world.transformDirection(this.cameraForward.set(0f, 0f, -1f)).normalize();
    this.world.transformDirection(this.cameraRight.set(1f, 0f, 0f)).normalize();
    this.world.transformDirection(this.cameraUp.set(0f, 1f, 0f)).normalize();
    this.cameraNear = camera.near;

    this.queue.collect(scene, alpha, this.cameraPos);
    prepareLights(scene, alpha);

    int cascades = 0;
    if (this.shadowLight != null) {
      cascades = shadowPasses(gl, camera, aspect, alpha, width, height);
    }
    if (!this.pointShadowLights.isEmpty()) {
      pointShadowPasses(gl, width, height);
    }
    mainPass(gl, cascades);
  }

  private void prepareLights(Scene scene, float alpha) {
    final var all = scene.components(Light.class);
    this.shadowLight = null;
    for (int i = 0; i < all.size(); i++) {
      final var l = all.get(i);
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
    this.pointShadowLights.clear();
    final int maxPointShadows = Math.max(0, Math.min(MAX_POINT_SHADOWS, this.settings.maxPointShadows));
    for (int i = 0; i < this.lightCount; i++) {
      final var l = this.orderedLights.get(i);
      l.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      this.lightShadow[i] = -1;
      if (l.type == Light.Type.DIRECTIONAL) {
        this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize().negate();
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, 0f);
      } else {
        this.world.getTranslation(this.direction);
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, 1f);
        if (l.castShadows && this.pointShadowLights.size() < maxPointShadows && l.range > l.shadowNear) {
          final int s = this.pointShadowLights.size();
          this.lightShadow[i] = s;
          this.pointShadowRange[s * 2] = Math.max(l.shadowNear, 1e-3f);
          this.pointShadowRange[s * 2 + 1] = l.range;
          this.pointShadowLights.add(l);
        }
      }
      put(this.lightColor, i, l.color.x * l.intensity, l.color.y * l.intensity, l.color.z * l.intensity,
          Math.max(l.range, 0.01f));
    }
    if (this.lightCount == 0) { // no lights in the scene: fall back to a default sun so nothing renders black
      this.lightCount = 1;
      this.lightShadow[0] = -1;
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

  /** (Re)creates a shadow map array when the settings or the number of layers needed changed. */
  private static ShadowMapArray ensure(GL3 gl, ShadowMapArray current, int size, int layers, boolean clampToEdge) {
    if (current.size() == size && current.layers() >= layers) {
      return current;
    }
    current.dispose(gl);
    return new ShadowMapArray(gl, size, layers, clampToEdge);
  }

  /** Renders every cascade into its layer; returns the number of cascades (0 if the range is empty). */
  private int shadowPasses(GL3 gl, Camera camera, float aspect, float alpha, int width, int height) {
    final var light = this.shadowLight;
    final int count = Math.max(1, Math.min(Light.MAX_CASCADES, light.shadowCascades));
    final float near = camera.near;
    final float far = Math.min(camera.far, light.shadowDistance);
    if (far <= near * 1.001f) {
      return 0;
    }
    final int size = Math.max(16, this.settings.shadowMapSize);
    this.shadowMap = ensure(gl, this.shadowMap, size, count, false);
    final float[] splits = ShadowMath.cascadeSplits(near, far, count, light.cascadeLambda);
    light.entity().transform().interpolatedWorldMatrix(alpha, this.world);
    this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize();
    final float fovY = (float) Math.toRadians(camera.fovDegrees);

    gl.glEnable(GL3.GL_DEPTH_CLAMP); // casters closer to the light than the near plane still shadow the cascade
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(1.5f, 2f);
    int totalCasters = 0;
    for (int i = 0; i < count; i++) {
      final float d0 = i == 0 ? near : splits[i - 1];
      final float radius = ShadowMath.sliceBoundingSphere(this.cameraPos, this.cameraRight, this.cameraUp,
          this.cameraForward, fovY, aspect, d0, splits[i], this.focus);
      ShadowMath.cascadeViewProj(this.direction, this.focus, radius, size, this.cascadeMatrix[i]);
      ShadowMath.cascadeCullingVolume(this.direction, this.focus, radius, size, CASTER_REACH, this.cullVolume);
      this.cascadeSplit[i] = splits[i];
      this.cascadeTexel[i] = 2f * radius / size;

      this.queue.cull(this.cullVolume, RenderQueue.Pass.SHADOW, this.casters);
      totalCasters += this.casters.size();
      this.casters.sort(SHADOW_ORDER);
      this.shadowMap.beginLayer(gl, i);
      drawShadowCasters(gl, this.cascadeMatrix[i]);
    }
    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glDisable(GL3.GL_DEPTH_CLAMP);
    this.shadowMap.end(gl, width, height);
    this.stats.shadowCasters = totalCasters;
    this.stats.shadowCascades = count;
    return count;
  }

  /** Six 90 degree depth passes per shadowed point light, one per cube face. */
  private void pointShadowPasses(GL3 gl, int width, int height) {
    final int lights = this.pointShadowLights.size();
    final int size = Math.max(16, this.settings.pointShadowMapSize);
    this.pointShadowMap = ensure(gl, this.pointShadowMap, size, lights * 6, true);
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(1.5f, 2f);
    int totalCasters = 0;
    for (int s = 0; s < lights; s++) {
      final int light = indexOf(this.pointShadowLights.get(s));
      this.direction.set(this.lightPos[light * 4], this.lightPos[light * 4 + 1], this.lightPos[light * 4 + 2]);
      for (int face = 0; face < 6; face++) {
        ShadowMath.cubeFaceViewProj(this.direction, face, this.pointShadowRange[s * 2],
            this.pointShadowRange[s * 2 + 1], this.faceMatrix);
        this.queue.cull(this.faceMatrix, RenderQueue.Pass.SHADOW, this.casters);
        totalCasters += this.casters.size();
        this.casters.sort(SHADOW_ORDER);
        this.pointShadowMap.beginLayer(gl, s * 6 + face);
        drawShadowCasters(gl, this.faceMatrix);
      }
    }
    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
    this.pointShadowMap.end(gl, width, height);
    this.stats.shadowCasters += totalCasters;
    this.stats.pointShadows = lights;
  }

  private int indexOf(Light l) {
    for (int i = 0; i < this.lightCount; i++) {
      if (this.orderedLights.get(i) == l) {
        return i;
      }
    }
    return 0;
  }

  private void mainPass(GL3 gl, int cascades) {
    this.basic.use(gl);
    this.basic.set(gl, "uViewProj", this.viewProj);
    this.basic.set(gl, "uCameraPos", this.cameraPos);
    this.basic.set(gl, "uAmbient", this.ambient);
    this.basic.set(gl, "uLightCount", this.lightCount);
    this.basic.setVec4Array(gl, "uLightPos", this.lightPos, this.lightCount);
    this.basic.setVec4Array(gl, "uLightColor", this.lightColor, this.lightCount);
    this.basic.setIntArray(gl, "uLightShadow", this.lightShadow, this.lightCount);
    this.basic.set(gl, "uCascadeCount", cascades);
    if (cascades > 0) {
      this.basic.setMat4Array(gl, "uCascadeMatrix", this.cascadeMatrix, cascades);
      this.basic.setFloatArray(gl, "uCascadeSplit", this.cascadeSplit, cascades);
      this.basic.setFloatArray(gl, "uCascadeTexel", this.cascadeTexel, cascades);
    }
    this.basic.set(gl, "uShadowTexel", 1f / this.shadowMap.size());
    this.basic.setVec2Array(gl, "uPointShadowRange", this.pointShadowRange, this.pointShadowLights.size());
    this.basic.set(gl, "uPointShadowTexel", 1f / this.pointShadowMap.size());
    this.basic.set(gl, "uDebugCascades", this.settings.debugCascades ? 1 : 0);
    this.basic.set(gl, "uTexture", UNIT_ALBEDO);
    this.basic.set(gl, "uShadowMap", UNIT_CASCADES);
    this.basic.set(gl, "uNormalMap", UNIT_NORMAL);
    this.basic.set(gl, "uSpecularMap", UNIT_SPECULAR);
    this.basic.set(gl, "uEmissiveMap", UNIT_EMISSIVE);
    this.basic.set(gl, "uHeightMap", UNIT_HEIGHT);
    this.basic.set(gl, "uPointShadowMap", UNIT_POINT_SHADOWS);
    this.shadowMap.bindTexture(gl, UNIT_CASCADES);
    this.pointShadowMap.bindTexture(gl, UNIT_POINT_SHADOWS);

    final boolean occlusion = this.settings.occlusionCulling;
    this.queue.cull(this.viewProj, RenderQueue.Pass.MAIN, this.visible, occlusion);
    this.stats.culled = this.queue.candidates() - this.visible.size() - (occlusion ? this.queue.occluded() : 0);
    this.opaque.clear();
    this.transparent.clear();
    for (int i = 0; i < this.visible.size(); i++) {
      final var r = this.visible.get(i);
      (r.material.transparent ? this.transparent : this.opaque).add(r);
    }
    RenderQueue.sortForBatching(this.opaque, true);
    drawList(gl, this.opaque);

    if (occlusion) {
      this.stats.occluded = this.queue.occluded();
      issueOcclusionQueries(gl);
      this.basic.use(gl);
    }

    if (!this.transparent.isEmpty()) {
      RenderQueue.sortBackToFront(this.transparent);
      gl.glEnable(GL3.GL_BLEND);
      gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);
      gl.glDepthMask(false);
      // neighbours in back-to-front order that share mesh and material are still one instanced draw: instances are
      // drawn in buffer order, so blending stays correct
      drawList(gl, this.transparent);
      gl.glDepthMask(true);
      gl.glDisable(GL3.GL_BLEND);
    }
  }

  /** Draws a sorted list with the main shader; runs of the same mesh and material become one instanced call. */
  private void drawList(GL3 gl, List<MeshRenderer> list) {
    final int n = list.size();
    int i = 0;
    while (i < n) {
      final var first = list.get(i);
      int j = i + 1;
      while (j < n && RenderQueue.sameBatch(first, list.get(j), true)) {
        j++;
      }
      applyMaterial(gl, first.material);
      drawInstances(gl, list, i, j);
      i = j;
    }
  }

  /**
   * Draws the sorted shadow casters; materials only matter for cut-outs, whose texture decides the holes and which use
   * their own program, so the others keep early depth testing.
   */
  private void drawShadowCasters(GL3 gl, Matrix4f lightViewProj) {
    final var list = this.casters;
    final int n = list.size();
    ShaderProgram current = null;
    int i = 0;
    while (i < n) {
      final var first = list.get(i);
      int j = i + 1;
      while (j < n && first.mesh == list.get(j).mesh
          && shadowMaterialKey(first) == shadowMaterialKey(list.get(j))) {
        j++;
      }
      final var m = first.material;
      final var program = m.alphaCutoff > 0f ? this.cutout : this.depth;
      if (program != current) {
        current = program;
        program.use(gl);
        program.set(gl, "uLightViewProj", lightViewProj);
      }
      if (program == this.cutout) {
        this.cutout.set(gl, "uTexture", UNIT_ALBEDO);
        (m.texture != null ? (GlTexture) m.texture : this.white).bind(gl, UNIT_ALBEDO);
        this.cutout.set(gl, "uAlpha", m.color.w);
        this.cutout.set(gl, "uAlphaCutoff", m.alphaCutoff);
      }
      drawInstances(gl, list, i, j);
      i = j;
    }
  }

  private static int shadowMaterialKey(MeshRenderer r) {
    return r.material.alphaCutoff > 0f ? r.material.sortKey : 0;
  }

  /** One instanced draw of {@code list[from, to)}, which all share the first renderer's mesh. */
  private void drawInstances(GL3 gl, List<MeshRenderer> list, int from, int to) {
    final int count = to - from;
    final int floats = count * RenderQueue.FLOATS_PER_INSTANCE;
    if (this.instanceBuffer.capacity() < floats) {
      this.instanceBuffer = Buffers.newDirectFloatBuffer(Math.max(floats, this.instanceBuffer.capacity() * 2));
    }
    this.instanceBuffer.clear();
    for (int k = 0; k < count; k++) {
      RenderQueue.writeInstance(list.get(from + k), this.instanceBuffer, k);
    }
    this.instanceBuffer.position(0).limit(floats);
    ((GlMesh) list.get(from).mesh).draw(gl, this.instanceBuffer, count);
    this.stats.drawCalls++;
    this.stats.instances += count;
  }

  private void applyMaterial(GL3 gl, Material m) {
    final var tex = m.texture != null ? (GlTexture) m.texture : this.white;
    tex.bind(gl, UNIT_ALBEDO);
    // unused samplers still get a texture bound, so every sampler stays valid
    ((GlTexture) (m.normalMap != null ? m.normalMap : this.flatNormal)).bind(gl, UNIT_NORMAL);
    ((GlTexture) (m.specularMap != null ? m.specularMap : this.white)).bind(gl, UNIT_SPECULAR);
    ((GlTexture) (m.emissiveMap != null ? m.emissiveMap : this.white)).bind(gl, UNIT_EMISSIVE);
    ((GlTexture) (m.heightMap != null ? m.heightMap : this.white)).bind(gl, UNIT_HEIGHT);
    this.basic.set(gl, "uHasNormalMap", m.normalMap != null ? 1 : 0);
    if (m.normalMap != null) {
      this.basic.set(gl, "uNormalScale", m.normalScale);
      this.basic.set(gl, "uNormalFlipY", m.normalMapFlipY ? -1f : 1f);
    }
    this.basic.set(gl, "uHasSpecularMap", m.specularMap != null ? 1 : 0);
    this.basic.set(gl, "uHasEmissiveMap", m.emissiveMap != null ? 1 : 0);
    this.basic.set(gl, "uHasHeightMap", m.heightMap != null && m.heightScale != 0f ? 1 : 0);
    this.basic.set(gl, "uHeightScale", m.heightScale);
    this.basic.set(gl, "uEmissive", m.emissive);
    this.basic.set(gl, "uColor", m.color);
    this.basic.set(gl, "uSpecular", m.specular);
    this.basic.set(gl, "uShininess", m.shininess);
    this.basic.set(gl, "uUnlit", m.unlit ? 1 : 0);
    this.basic.set(gl, "uAlphaCutoff", m.transparent ? 0f : m.alphaCutoff);
    this.basic.set(gl, "uPointSize", m.pointSize);
  }

  /**
   * Draws the box of every static cell inside the frustum with an occlusion query against the depth buffer of the
   * opaque pass. Cells that hold the camera are always visible (their box would be clipped by the near plane).
   */
  private void issueOcclusionQueries(GL3 gl) {
    final var cells = this.queue.occlusionCandidates();
    if (cells.isEmpty()) {
      return;
    }
    this.box.use(gl);
    this.box.set(gl, "uViewProj", this.viewProj);
    gl.glColorMask(false, false, false, false);
    gl.glDepthMask(false);
    // a box that reaches through the near plane must not be clipped there: it would look hidden while it is not
    gl.glEnable(GL3.GL_DEPTH_CLAMP);
    gl.glBindVertexArray(this.boxVao);
    final float margin = this.cameraNear * 2f + 0.01f;
    final var cam = this.cameraPos;
    for (int i = 0; i < cells.size(); i++) {
      final var c = cells.get(i);
      if (cam.x > c.minX() - margin && cam.x < c.maxX() + margin && cam.y > c.minY() - margin
          && cam.y < c.maxY() + margin && cam.z > c.minZ() - margin && cam.z < c.maxZ() + margin) {
        c.setOccluded(false);
        continue;
      }
      final int query = nextQuery(gl);
      gl.glBeginQuery(GL3.GL_ANY_SAMPLES_PASSED, query);
      this.box.set(gl, "uMin", c.minX(), c.minY(), c.minZ());
      this.box.set(gl, "uMax", c.maxX(), c.maxY(), c.maxZ());
      gl.glDrawElements(GL3.GL_TRIANGLES, 36, GL3.GL_UNSIGNED_INT, 0L);
      gl.glEndQuery(GL3.GL_ANY_SAMPLES_PASSED);
      addPending(c.key(), query);
    }
    gl.glBindVertexArray(0);
    gl.glDisable(GL3.GL_DEPTH_CLAMP);
    gl.glDepthMask(true);
    gl.glColorMask(true, true, true, true);
    this.stats.occlusionQueries = this.pendingCount;
  }

  private int nextQuery(GL3 gl) {
    if (this.queryPoolUsed == this.queryPool.length) {
      final int grow = Math.max(64, this.queryPool.length);
      final int[] more = new int[grow];
      gl.glGenQueries(grow, more, 0);
      final int[] pool = new int[this.queryPool.length + grow];
      System.arraycopy(this.queryPool, 0, pool, 0, this.queryPool.length);
      System.arraycopy(more, 0, pool, this.queryPool.length, grow);
      this.queryPool = pool;
    }
    return this.queryPool[this.queryPoolUsed++];
  }

  private void addPending(Object key, int query) {
    if (this.pendingCount == this.pendingKeys.length) {
      this.pendingKeys = java.util.Arrays.copyOf(this.pendingKeys, this.pendingCount * 2);
      this.pendingQueries = java.util.Arrays.copyOf(this.pendingQueries, this.pendingCount * 2);
    }
    this.pendingKeys[this.pendingCount] = key;
    this.pendingQueries[this.pendingCount] = query;
    this.pendingCount++;
  }

  /**
   * Hands last frame's query results to the queue. A result that is not ready yet counts as visible: drawing too much
   * is safe, waiting for the GPU is not.
   */
  private void readOcclusionResults(GL3 gl) {
    for (int i = 0; i < this.pendingCount; i++) {
      final int q = this.pendingQueries[i];
      gl.glGetQueryObjectiv(q, GL3.GL_QUERY_RESULT_AVAILABLE, this.result, 0);
      boolean hidden = false;
      if (this.result[0] != 0) {
        gl.glGetQueryObjectiv(q, GL3.GL_QUERY_RESULT, this.result, 0);
        hidden = this.result[0] == 0;
      }
      RenderQueue.OcclusionCell.setOccluded(this.pendingKeys[i], hidden);
      this.pendingKeys[i] = null;
    }
    this.pendingCount = 0;
    this.queryPoolUsed = 0;
  }

  void dispose(GL3 gl) {
    if (this.basic != null) {
      this.basic.dispose(gl);
      this.depth.dispose(gl);
      this.cutout.dispose(gl);
      this.box.dispose(gl);
      this.shadowMap.dispose(gl);
      this.pointShadowMap.dispose(gl);
      gl.glDeleteVertexArrays(1, new int[] { this.boxVao }, 0);
      gl.glDeleteBuffers(2, new int[] { this.boxVbo, this.boxEbo }, 0);
      if (this.queryPool.length > 0) {
        gl.glDeleteQueries(this.queryPool.length, this.queryPool, 0);
      }
    }
  }
}
