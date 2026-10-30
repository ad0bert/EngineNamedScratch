package engine.render.gl;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

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
 * <li>cascaded shadow maps for the first shadow-casting directional light; six shadow maps (a cube) for each
 * shadow-casting point light and one for each spot light (up to {@link RenderSettings#maxPointShadows} lights),
 * re-rendered only when the light or one of its casters moved;</li>
 * <li>into the HDR target of {@link PostProcessor}: opaque geometry batched by (mesh, material) with GPU instancing,
 * skipping static cells that were occluded in the last frame;</li>
 * <li>occlusion queries for the visible static cells (read back in the next frame, so the GPU is never waited on);</li>
 * <li>the sky ({@link GlSky}) where nothing opaque was drawn;</li>
 * <li>transparent geometry: weighted blended order-independent transparency (batched like opaque geometry), or sorted
 * back to front;</li>
 * <li>particles ({@link GlParticles}), emitters back to front;</li>
 * <li>bloom, tone mapping and FXAA into the window.</li>
 * </ol>
 * Lighting happens in linear space: colour textures are sRGB-decoded by the GPU, material colours are converted on
 * upload. Per-frame work reuses its lists, buffers and matrices.
 *
 * <p>
 * Texture units: 0 albedo, 1 shadow cascades, 2 normal map, 3 specular map, 4 emissive map, 5 height map, 6 point and
 * spot light shadow maps, 7 metallic-roughness map, 13 to 15 the sky's irradiance, reflection and BRDF maps.
 */
final class GlSceneRenderer {
  private static final int UNIT_ALBEDO = 0;
  private static final int UNIT_CASCADES = 1;
  private static final int UNIT_NORMAL = 2;
  private static final int UNIT_SPECULAR = 3;
  private static final int UNIT_EMISSIVE = 4;
  private static final int UNIT_HEIGHT = 5;
  private static final int UNIT_LOCAL_SHADOWS = 6;
  private static final int UNIT_METAL_ROUGH = 7;
  private static final int UNIT_IRRADIANCE = 13;
  private static final int UNIT_PREFILTERED = 14;
  private static final int UNIT_BRDF = 15;
  /** Point and spot lights that may cast shadows in one frame. */
  private static final int MAX_LOCAL_SHADOWS = 4;
  /** Casters up to this far towards the light from a cascade still shadow it. */
  private static final float CASTER_REACH = 500f;

  /** Shadow casters: by mesh, and only cut-out materials need to be told apart (they sample their texture). */
  private static final Comparator<MeshRenderer> SHADOW_ORDER = Comparator
      .comparingInt((MeshRenderer r) -> r.drawnMesh().id()).thenComparingInt(GlSceneRenderer::shadowMaterialKey);

  private final RenderStats stats;
  private final RenderSettings settings;
  private final RenderQueue queue = new RenderQueue();
  private final PostProcessor post = new PostProcessor();
  private final GlSky sky = new GlSky();
  private final GlParticles particles = new GlParticles();
  private ShaderProgram basic;
  private ShaderProgram depth;
  private ShaderProgram cutout;
  private ShaderProgram box;
  private final GlSkinning skinning = new GlSkinning();
  private ShadowMapArray shadowMap;
  private ShadowMapArray localShadowMap;
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
  private final Vector3f spotDir = new Vector3f();
  private final Vector3f ambient = new Vector3f(0.25f, 0.25f, 0.28f);
  private final Vector3f linear = new Vector3f();
  private final Vector4f linearColor = new Vector4f();
  private float cameraNear;

  private final float[] lightPos = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightColor = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightSpot = new float[Light.MAX_LIGHTS * 4];
  private final float[] lightInner = new float[Light.MAX_LIGHTS];
  private final int[] lightShadow = new int[Light.MAX_LIGHTS];
  private final float[] shadowRange = new float[Light.MAX_LIGHTS * 2];
  private final Matrix4f[] spotMatrix = new Matrix4f[Light.MAX_LIGHTS];
  private final List<Light> orderedLights = new ArrayList<>();
  private int localLayers;
  private int lightCount;
  private Light shadowLight;
  // what each local shadow layer holds, to skip re-rendering it while nothing moved
  private long[] layerSignature = new long[0];
  private boolean[] layerValid = new boolean[0];

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
    for (int i = 0; i < this.spotMatrix.length; i++) {
      this.spotMatrix[i] = new Matrix4f();
    }
  }

  void init(GL3 gl, GlTexture white, GlTexture flatNormal) {
    this.white = white;
    this.flatNormal = flatNormal;
    this.basic = new ShaderProgram(gl, "shaders/basic.vert", "shaders/basic.frag");
    this.depth = new ShaderProgram(gl, "shaders/depth.vert", "shaders/depth.frag");
    this.cutout = new ShaderProgram(gl, "shaders/cutout.vert", "shaders/cutout.frag");
    this.box = new ShaderProgram(gl, "shaders/box.vert", "shaders/box.frag");
    this.skinning.init(gl, this.basic, this.depth, this.cutout);
    this.post.init(gl);
    this.sky.init(gl);
    this.particles.init(gl);
    // placeholders until a light needs them; the samplers must always have a texture of their type bound
    this.shadowMap = new ShadowMapArray(gl, 1, 1, false);
    this.localShadowMap = new ShadowMapArray(gl, 1, 6, true);
    createUnitBox(gl);
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
    final int screen = gl.getContext().getDefaultDrawFramebuffer();
    final var sky = srgbToLinear(this.settings.clearColor, this.linear);
    final var activeCamera = scene.activeCamera();
    if (activeCamera.isEmpty()) {
      this.post.beginScene(gl, width, height, sky.x, sky.y, sky.z, false);
      this.post.finish(gl, this.settings, screen);
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

    this.queue.setLodBias(this.settings.lodBias);
    this.queue.collect(scene, alpha, this.cameraPos);
    prepareLights(scene, alpha);
    this.sky.prepare(gl, this.settings); // bakes the sky's lighting when it is new or changed

    int cascades = 0;
    if (this.shadowLight != null) {
      cascades = shadowPasses(gl, camera, aspect, alpha);
    }
    if (this.localLayers > 0) {
      localShadowPasses(gl);
    }
    this.post.beginScene(gl, width, height, sky.x, sky.y, sky.z, this.settings.ambientOcclusion);
    mainPass(gl, cascades);
    this.particles.draw(gl, scene, alpha, this.viewProj, this.cameraPos, this.cameraForward, this.cameraRight,
        this.cameraUp, camera.near, camera.far, this.post, this.white, this.stats, this);
    this.post.finish(gl, this.settings, screen);
  }

  /**
   * The light this frame's lights bring to point {@code p} (with their falloff and spot cones, without shadows) at
   * half strength, as a surface turned half-way to each light gets it, plus the flat ambient light when there is no
   * sky (the sky's own light is sampled on the GPU): for lit particles.
   */
  Vector3f lightAt(Vector3f p, Vector3f dest) {
    if (this.settings.sky == null) {
      dest.set(this.ambient);
    } else {
      dest.zero();
    }
    for (int i = 0; i < this.lightCount; i++) {
      float atten = 1f;
      if (this.lightPos[i * 4 + 3] != 0f) {
        final float dx = this.lightPos[i * 4] - p.x;
        final float dy = this.lightPos[i * 4 + 1] - p.y;
        final float dz = this.lightPos[i * 4 + 2] - p.z;
        final float distSq = dx * dx + dy * dy + dz * dz;
        final float range = this.lightColor[i * 4 + 3];
        final float x = Math.max(0f, Math.min(1f, 1f - distSq / (range * range)));
        atten = x * x;
        if (this.lightPos[i * 4 + 3] > 1.5f && atten > 0f) {
          final float dist = (float) Math.sqrt(distSq);
          final float c = dist > 1e-4f ? -(dx * this.lightSpot[i * 4] + dy * this.lightSpot[i * 4 + 1]
              + dz * this.lightSpot[i * 4 + 2]) / dist : 1f;
          final float outer = this.lightSpot[i * 4 + 3];
          final float inner = this.lightInner[i];
          final float t = Math.max(0f, Math.min(1f, (c - outer) / Math.max(1e-4f, inner - outer)));
          atten *= t * t * (3f - 2f * t);
        }
      }
      final float k = 0.5f * atten;
      dest.add(this.lightColor[i * 4] * k, this.lightColor[i * 4 + 1] * k, this.lightColor[i * 4 + 2] * k);
    }
    return dest;
  }

  /** Binds the sky's lighting to another program (its irradiance on unit 2). */
  void bindSky(GL3 gl, ShaderProgram program) {
    this.sky.bindLighting(gl, program, this.settings, 2, 3, 4);
  }

  /** sRGB to linear, per channel (the exact curve, not a gamma of 2.2). */
  static float srgbToLinear(float c) {
    return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
  }

  private static Vector3f srgbToLinear(Vector3f c, Vector3f dest) {
    return dest.set(srgbToLinear(c.x), srgbToLinear(c.y), srgbToLinear(c.z));
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
    this.localLayers = 0;
    int shadowed = 0;
    final int maxShadowed = Math.max(0, Math.min(MAX_LOCAL_SHADOWS, this.settings.maxPointShadows));
    for (int i = 0; i < this.lightCount; i++) {
      final var l = this.orderedLights.get(i);
      l.entity().transform().interpolatedWorldMatrix(alpha, this.world);
      this.lightShadow[i] = -1;
      if (l.type == Light.Type.DIRECTIONAL) {
        this.world.transformDirection(this.direction.set(0f, 0f, -1f)).normalize().negate();
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, 0f);
      } else {
        this.world.getTranslation(this.direction);
        final boolean spot = l.type == Light.Type.SPOT;
        put(this.lightPos, i, this.direction.x, this.direction.y, this.direction.z, spot ? 2f : 1f);
        if (spot) {
          this.world.transformDirection(this.spotDir.set(0f, 0f, -1f)).normalize();
          final float outer = (float) Math.toRadians(Math.max(1f, Math.min(89f, l.spotAngle)));
          final float inner = outer * (1f - Math.max(0f, Math.min(1f, l.spotSoftness)));
          put(this.lightSpot, i, this.spotDir.x, this.spotDir.y, this.spotDir.z, (float) Math.cos(outer));
          this.lightInner[i] = (float) Math.cos(inner);
        }
        if (l.castShadows && shadowed < maxShadowed && l.range > l.shadowNear) {
          shadowed++;
          this.lightShadow[i] = this.localLayers;
          this.localLayers += spot ? 1 : 6;
          this.shadowRange[i * 2] = Math.max(l.shadowNear, 1e-3f);
          this.shadowRange[i * 2 + 1] = l.range;
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
  private int shadowPasses(GL3 gl, Camera camera, float aspect, float alpha) {
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
    this.shadowMap.end(gl);
    this.stats.shadowCasters = totalCasters;
    this.stats.shadowCascades = count;
    return count;
  }

  /**
   * Shadow maps of point lights (six 90 degree faces) and spot lights (one perspective view covering the cone). A
   * layer is rendered again only when its content would differ: when the light moved, when its casters changed, or
   * when one of them is moving.
   */
  private void localShadowPasses(GL3 gl) {
    final int size = Math.max(16, this.settings.pointShadowMapSize);
    final var before = this.localShadowMap;
    this.localShadowMap = ensure(gl, this.localShadowMap, size, this.localLayers, true);
    if (this.localShadowMap != before || this.layerValid.length < this.localShadowMap.layers()) {
      this.layerSignature = new long[this.localShadowMap.layers()];
      this.layerValid = new boolean[this.localShadowMap.layers()];
    }
    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
    gl.glPolygonOffset(1.5f, 2f);
    int totalCasters = 0;
    int lights = 0;
    int reused = 0;
    for (int i = 0; i < this.lightCount; i++) {
      final int layer = this.lightShadow[i];
      if (layer < 0 || this.orderedLights.get(i).type == Light.Type.DIRECTIONAL) {
        continue;
      }
      lights++;
      final var l = this.orderedLights.get(i);
      this.direction.set(this.lightPos[i * 4], this.lightPos[i * 4 + 1], this.lightPos[i * 4 + 2]);
      final float near = this.shadowRange[i * 2];
      final float far = this.shadowRange[i * 2 + 1];
      final boolean spot = l.type == Light.Type.SPOT;
      for (int face = 0; face < (spot ? 1 : 6); face++) {
        final var m = spot ? this.spotMatrix[i] : this.faceMatrix;
        if (spot) {
          this.spotDir.set(this.lightSpot[i * 4], this.lightSpot[i * 4 + 1], this.lightSpot[i * 4 + 2]);
          ShadowMath.spotViewProj(this.direction, this.spotDir, l.spotAngle, near, far, m);
        } else {
          ShadowMath.cubeFaceViewProj(this.direction, face, near, far, m);
        }
        this.queue.cull(m, RenderQueue.Pass.SHADOW, this.casters);
        totalCasters += this.casters.size();
        final long signature = signature(m, this.casters);
        if (signature != 0L && this.layerValid[layer + face] && this.layerSignature[layer + face] == signature) {
          reused++;
          continue; // nothing in view of this layer changed
        }
        this.layerSignature[layer + face] = signature;
        this.layerValid[layer + face] = signature != 0L;
        this.casters.sort(SHADOW_ORDER);
        this.localShadowMap.beginLayer(gl, layer + face);
        drawShadowCasters(gl, m);
      }
    }
    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
    this.localShadowMap.end(gl);
    this.stats.shadowCasters += totalCasters;
    this.stats.pointShadows = lights;
    this.stats.cachedShadowMaps = reused;
  }

  /**
   * What a shadow layer would show: the light's matrix and every caster with its version; 0 (never equal) while a
   * caster is moving.
   */
  private static long signature(Matrix4f lightViewProj, List<MeshRenderer> casters) {
    long h = 0xcbf29ce484222325L;
    for (int c = 0; c < 4; c++) {
      for (int r = 0; r < 4; r++) {
        h = (h ^ Float.floatToIntBits(lightViewProj.get(c, r))) * 0x100000001b3L;
      }
    }
    for (int k = 0; k < casters.size(); k++) {
      final var r = casters.get(k);
      if (!RenderQueue.isSettled(r)) {
        return 0L;
      }
      h = (h ^ System.identityHashCode(r)) * 0x100000001b3L;
      h = (h ^ RenderQueue.version(r)) * 0x100000001b3L;
    }
    return h == 0L ? 1L : h;
  }

  private void mainPass(GL3 gl, int cascades) {
    this.basic.use(gl);
    this.basic.set(gl, "uViewProj", this.viewProj);
    this.basic.set(gl, "uCameraPos", this.cameraPos);
    this.basic.set(gl, "uAmbient", this.ambient);
    this.basic.set(gl, "uLightCount", this.lightCount);
    this.basic.setVec4Array(gl, "uLightPos", this.lightPos, this.lightCount);
    this.basic.setVec4Array(gl, "uLightColor", this.lightColor, this.lightCount);
    this.basic.setVec4Array(gl, "uLightSpot", this.lightSpot, this.lightCount);
    this.basic.setFloatArray(gl, "uLightInner", this.lightInner, this.lightCount);
    this.basic.setIntArray(gl, "uLightShadow", this.lightShadow, this.lightCount);
    this.basic.setVec2Array(gl, "uShadowRange", this.shadowRange, this.lightCount);
    this.basic.setMat4Array(gl, "uSpotShadow", this.spotMatrix, this.lightCount);
    this.basic.set(gl, "uCascadeCount", cascades);
    if (cascades > 0) {
      this.basic.setMat4Array(gl, "uCascadeMatrix", this.cascadeMatrix, cascades);
      this.basic.setFloatArray(gl, "uCascadeSplit", this.cascadeSplit, cascades);
      this.basic.setFloatArray(gl, "uCascadeTexel", this.cascadeTexel, cascades);
    }
    this.basic.set(gl, "uShadowTexel", 1f / this.shadowMap.size());
    this.basic.set(gl, "uPointShadowTexel", 1f / this.localShadowMap.size());
    this.basic.set(gl, "uDebugCascades", this.settings.debugCascades ? 1 : 0);
    this.basic.set(gl, "uTexture", UNIT_ALBEDO);
    this.basic.set(gl, "uShadowMap", UNIT_CASCADES);
    this.basic.set(gl, "uNormalMap", UNIT_NORMAL);
    this.basic.set(gl, "uSpecularMap", UNIT_SPECULAR);
    this.basic.set(gl, "uEmissiveMap", UNIT_EMISSIVE);
    this.basic.set(gl, "uHeightMap", UNIT_HEIGHT);
    this.basic.set(gl, "uPointShadowMap", UNIT_LOCAL_SHADOWS);
    this.basic.set(gl, "uMetalRoughMap", UNIT_METAL_ROUGH);
    this.basic.set(gl, "uOit", 0);
    this.sky.bindLighting(gl, this.basic, this.settings, UNIT_IRRADIANCE, UNIT_PREFILTERED, UNIT_BRDF);
    this.shadowMap.bindTexture(gl, UNIT_CASCADES);
    this.localShadowMap.bindTexture(gl, UNIT_LOCAL_SHADOWS);

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
    this.post.ambientOcclusion(gl, this.settings, this.proj);
    this.basic.use(gl);

    if (occlusion) {
      this.stats.occluded = this.queue.occluded();
      issueOcclusionQueries(gl);
      this.basic.use(gl);
    }
    if (this.settings.sky != null) {
      drawSky(gl);
      this.basic.use(gl);
    }

    if (this.transparent.isEmpty()) {
      return;
    }
    if (this.settings.orderIndependentTransparency) {
      // the order does not matter: batch them like opaque geometry
      RenderQueue.sortForBatching(this.transparent, true);
      this.post.beginTransparent(gl);
      this.basic.set(gl, "uOit", 1);
      drawList(gl, this.transparent);
      this.basic.set(gl, "uOit", 0);
      this.post.endTransparent(gl);
    } else {
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

  /** The sky behind the opaque geometry, with the sun disc towards the first directional light. */
  private void drawSky(GL3 gl) {
    int sun = -1;
    for (int i = 0; i < this.lightCount && sun < 0; i++) {
      if (this.lightPos[i * 4 + 3] == 0f) {
        sun = i;
      }
    }
    if (sun >= 0) {
      this.direction.set(this.lightPos[sun * 4], this.lightPos[sun * 4 + 1], this.lightPos[sun * 4 + 2]);
      this.linear.set(this.lightColor[sun * 4], this.lightColor[sun * 4 + 1], this.lightColor[sun * 4 + 2]);
    }
    this.sky.drawBackground(gl, this.proj, this.view, this.settings, sun >= 0 ? this.direction : null,
        sun >= 0 ? this.linear : null);
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
      drawInstances(gl, this.basic, list, i, j);
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
      while (j < n && RenderQueue.sameBatch(first, list.get(j), false)
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
      drawInstances(gl, current, list, i, j);
      i = j;
    }
  }

  private static int shadowMaterialKey(MeshRenderer r) {
    return r.material.alphaCutoff > 0f ? r.material.sortKey : 0;
  }

  /**
   * One instanced draw of {@code list[from, to)}, which all share the first renderer's mesh (and skin, for a skinned
   * one, which is always alone), with {@code program} bound.
   */
  private void drawInstances(GL3 gl, ShaderProgram program, List<MeshRenderer> list, int from, int to) {
    this.skinning.apply(gl, program, list.get(from).skin);
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
    ((GlMesh) list.get(from).drawnMesh()).draw(gl, this.instanceBuffer, count);
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
    ((GlTexture) (m.metalRoughMap != null ? m.metalRoughMap : this.white)).bind(gl, UNIT_METAL_ROUGH);
    this.basic.set(gl, "uHasNormalMap", m.normalMap != null ? 1 : 0);
    if (m.normalMap != null) {
      this.basic.set(gl, "uNormalScale", m.normalScale);
      this.basic.set(gl, "uNormalFlipY", m.normalMapFlipY ? -1f : 1f);
    }
    this.basic.set(gl, "uHasSpecularMap", m.specularMap != null ? 1 : 0);
    this.basic.set(gl, "uHasEmissiveMap", m.emissiveMap != null ? 1 : 0);
    this.basic.set(gl, "uHasHeightMap", m.heightMap != null && m.heightScale != 0f ? 1 : 0);
    this.basic.set(gl, "uHeightScale", m.heightScale);
    this.basic.set(gl, "uEmissive", srgbToLinear(m.emissive, this.linear));
    this.linearColor.set(srgbToLinear(m.color.x), srgbToLinear(m.color.y), srgbToLinear(m.color.z), m.color.w);
    this.basic.set(gl, "uColor", this.linearColor);
    this.basic.set(gl, "uSpecular", m.specular);
    this.basic.set(gl, "uShininess", m.shininess);
    this.basic.set(gl, "uPbr", m.pbr ? 1 : 0);
    this.basic.set(gl, "uMetallic", m.metallic);
    this.basic.set(gl, "uRoughness", m.roughness);
    this.basic.set(gl, "uHasMetalRoughMap", m.metalRoughMap != null ? 1 : 0);
    this.basic.set(gl, "uReflectance", m.reflectance);
    this.basic.set(gl, "uMaterialAmbient", srgbToLinear(m.ambient, this.linear));
    this.linearColor.set(m.uvScale.x, m.uvScale.y, m.uvOffset.x, m.uvOffset.y);
    this.basic.set(gl, "uUvTransform", this.linearColor);
    this.basic.set(gl, "uClampUv", m.clampUv ? 1 : 0);
    this.basic.set(gl, "uUnlit", m.unlit ? 1 : 0);
    this.basic.set(gl, "uGlass", m.glass && m.transparent ? 1 : 0);
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
      this.pendingKeys = Arrays.copyOf(this.pendingKeys, this.pendingCount * 2);
      this.pendingQueries = Arrays.copyOf(this.pendingQueries, this.pendingCount * 2);
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
      this.skinning.dispose(gl);
      this.shadowMap.dispose(gl);
      this.localShadowMap.dispose(gl);
      this.post.dispose(gl);
      this.sky.dispose(gl);
      this.particles.dispose(gl);
      gl.glDeleteVertexArrays(1, new int[] { this.boxVao }, 0);
      gl.glDeleteBuffers(2, new int[] { this.boxVbo, this.boxEbo }, 0);
      if (this.queryPool.length > 0) {
        gl.glDeleteQueries(this.queryPool.length, this.queryPool, 0);
      }
    }
  }
}
