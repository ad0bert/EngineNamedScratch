package engine.render.gl;

import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.HdrImage;
import engine.render.RenderSettings;
import engine.render.Sky;

/**
 * The sky behind the scene and its image-based lighting. When a {@link Sky} is set (or its bake key changes) the
 * lighting is baked on the GPU, once:
 * <ol>
 * <li>the sky (gradient or equirectangular image) into an environment cube map with mipmaps;</li>
 * <li>diffuse irradiance into a small cube map (a cosine-weighted hemisphere average per texel);</li>
 * <li>specular reflections into a cube map whose mip levels hold rougher and rougher GGX lobes (importance sampled,
 * reading blurrier environment mips for wider samples);</li>
 * <li>the split-sum BRDF table (independent of the sky, made the first time).</li>
 * </ol>
 * Each frame only samples them: the main shader reads irradiance, reflections and the table, and the background is
 * drawn after the opaque geometry where the depth buffer is still clear (straight from the source, so the image stays
 * sharp; the sun disc is drawn there too and never baked). Every target is RGBA16F, renderable and filterable on any
 * GL 3.3 implementation (Mesa's software renderer included); the bake takes a fraction of a second there.
 */
final class GlSky {
  private static final Logger LOG = LoggerFactory.getLogger(GlSky.class);
  static final int ENV_SIZE = 256;
  static final int IRRADIANCE_SIZE = 32;
  static final int PREFILTER_SIZE = 128;
  /** Mip levels of the reflection map: roughness 0, 0.2, ... 1. */
  static final int PREFILTER_LEVELS = 6;
  static final int LUT_SIZE = 128;

  private ShaderProgram background;
  private ShaderProgram irradianceProgram;
  private ShaderProgram prefilterProgram;
  private ShaderProgram brdfProgram;
  private int vao;
  private int fbo;
  private int env;
  private int irradiance;
  private int prefiltered;
  private int lut;
  private int blackCube;
  private int black2d;
  private int equirect;
  private HdrImage equirectImage;
  private Sky bakedSky;
  private long bakedKey;
  private boolean active;
  private final int[] ids = new int[1];
  private final int[] viewport = new int[4];
  private final Matrix4f invProj = new Matrix4f();
  private final Matrix4f viewToWorld = new Matrix4f();

  void init(GL3 gl) {
    this.background = new ShaderProgram(gl, "shaders/sky.vert", "shaders/sky.frag");
    this.irradianceProgram = new ShaderProgram(gl, "shaders/sky.vert", "shaders/ibl_irradiance.frag");
    this.prefilterProgram = new ShaderProgram(gl, "shaders/sky.vert", "shaders/ibl_prefilter.frag");
    this.brdfProgram = new ShaderProgram(gl, "shaders/fullscreen.vert", "shaders/ibl_brdf.frag");
    gl.glGenVertexArrays(1, this.ids, 0);
    this.vao = this.ids[0];
    gl.glGenFramebuffers(1, this.ids, 0);
    this.fbo = this.ids[0];
    gl.glEnable(GL3.GL_TEXTURE_CUBE_MAP_SEAMLESS); // rough reflections filter across cube faces
    // the samplers always need a texture of their type, also without a sky
    this.blackCube = cube(gl, 1, 1);
    this.black2d = texture2d(gl, 1, 1, GL3.GL_RGBA16F, GL3.GL_RGBA, null);
    clearCube(gl, this.blackCube, 1);
  }

  /** Bakes the lighting of the settings' sky if it is new or changed; true if there is a sky. */
  boolean prepare(GL3 gl, RenderSettings settings) {
    final var sky = settings.sky;
    this.active = sky != null;
    if (sky == null) {
      return false;
    }
    final long key = sky.bakeKey();
    if (sky == this.bakedSky && key == this.bakedKey) {
      return true;
    }
    this.bakedSky = sky;
    this.bakedKey = key;
    final long start = System.nanoTime();
    bake(gl, sky);
    gl.glFinish(); // only to time it; this happens once per sky
    LOG.info("Baked the sky's lighting in {} ms", (System.nanoTime() - start) / 1_000_000);
    return true;
  }

  // ---- reflection probes -------------------------------------------------------------------------------------

  /** A black cube, for samplers with nothing to show. */
  int blackCube() {
    return this.blackCube;
  }

  /** An RGBA16F cube with a full mip chain, for a probe's capture. */
  int newEnvironment(GL3 gl, int size) {
    return cubeTexture(gl, size, Integer.numberOfTrailingZeros(Integer.highestOneBit(size)) + 1);
  }

  /** An RGBA16F cube with one level per roughness ({@link #PREFILTER_LEVELS}), for a probe's reflections. */
  int newPrefiltered(GL3 gl, int size) {
    return cubeTexture(gl, size, PREFILTER_LEVELS);
  }

  /**
   * Blurs a captured environment (mipmapped, bound to nothing) into {@code target}: level k for roughness
   * k / (levels - 1), like the sky's reflections. Restores the framebuffer binding (0) and the viewport.
   */
  void prefilter(GL3 gl, int env, int envSize, int target, int size) {
    gl.glGetIntegerv(GL3.GL_VIEWPORT, this.viewport, 0);
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(false);
    gl.glDisable(GL3.GL_BLEND);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glBindVertexArray(this.vao);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, env);
    this.prefilterProgram.use(gl);
    this.prefilterProgram.set(gl, "uEnv", 0);
    this.prefilterProgram.set(gl, "uEnvSize", (float) envSize);
    this.prefilterProgram.set(gl, "uMaxLod", (float) (Math.log(envSize) / Math.log(2)));
    for (int level = 0; level < PREFILTER_LEVELS; level++) {
      final int s = Math.max(1, size >> level);
      this.prefilterProgram.set(gl, "uRoughness", (float) level / (PREFILTER_LEVELS - 1));
      for (int face = 0; face < 6; face++) {
        attach(gl, GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, target, level, s);
        this.prefilterProgram.set(gl, "uFace", face);
        gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
      }
    }
    gl.glBindVertexArray(0);
    gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_TEXTURE_2D, 0, 0);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
    gl.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
    gl.glDepthMask(true);
    gl.glEnable(GL3.GL_DEPTH_TEST);
  }

  private int cubeTexture(GL3 gl, int size, int levels) {
    gl.glGenTextures(1, this.ids, 0);
    final int id = this.ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, id);
    for (int level = 0; level < levels; level++) {
      final int s = Math.max(1, size >> level);
      for (int face = 0; face < 6; face++) {
        gl.glTexImage2D(GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, GL3.GL_RGBA16F, s, s, 0, GL3.GL_RGBA,
            GL3.GL_FLOAT, null);
      }
    }
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAX_LEVEL, levels - 1);
    for (final int wrap : new int[] { GL3.GL_TEXTURE_WRAP_S, GL3.GL_TEXTURE_WRAP_T, GL3.GL_TEXTURE_WRAP_R }) {
      gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, wrap, GL3.GL_CLAMP_TO_EDGE);
    }
    return id;
  }

  // ---- baking ------------------------------------------------------------------------------------------------

  private void bake(GL3 gl, Sky sky) {
    gl.glGetIntegerv(GL3.GL_VIEWPORT, this.viewport, 0);
    gl.glDisable(GL3.GL_DEPTH_TEST);
    gl.glDepthMask(false);
    gl.glDisable(GL3.GL_BLEND);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glBindVertexArray(this.vao);
    if (this.lut == 0) {
      this.lut = texture2d(gl, LUT_SIZE, LUT_SIZE, GL3.GL_RG16F, GL3.GL_RG, null);
      attach(gl, GL3.GL_TEXTURE_2D, this.lut, 0, LUT_SIZE);
      this.brdfProgram.use(gl);
      gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
    }
    if (this.env == 0) {
      this.env = cube(gl, ENV_SIZE, 1);
      this.irradiance = cube(gl, IRRADIANCE_SIZE, 1);
      this.prefiltered = cube(gl, PREFILTER_SIZE, PREFILTER_LEVELS);
    }
    // 1. the source into the environment cube
    this.background.use(gl);
    setSource(gl, this.background, sky, ENV_SIZE);
    this.background.set(gl, "uRotation", 1f, 0f);
    this.background.set(gl, "uIntensity", 1f);
    this.background.set(gl, "uSun", 0);
    for (int face = 0; face < 6; face++) {
      attach(gl, GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, this.env, 0, ENV_SIZE);
      this.background.set(gl, "uFace", face);
      gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
    }
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, this.env);
    gl.glGenerateMipmap(GL3.GL_TEXTURE_CUBE_MAP);
    final float maxLod = (float) (Math.log(ENV_SIZE) / Math.log(2));
    // 2. diffuse irradiance, from a 16 x 16 mip
    this.irradianceProgram.use(gl);
    this.irradianceProgram.set(gl, "uEnv", 0);
    this.irradianceProgram.set(gl, "uLod", maxLod - 4f);
    for (int face = 0; face < 6; face++) {
      attach(gl, GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, this.irradiance, 0, IRRADIANCE_SIZE);
      this.irradianceProgram.set(gl, "uFace", face);
      gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
    }
    // 3. reflections, one roughness per mip level
    this.prefilterProgram.use(gl);
    this.prefilterProgram.set(gl, "uEnv", 0);
    this.prefilterProgram.set(gl, "uEnvSize", (float) ENV_SIZE);
    this.prefilterProgram.set(gl, "uMaxLod", maxLod);
    for (int level = 0; level < PREFILTER_LEVELS; level++) {
      final int size = Math.max(1, PREFILTER_SIZE >> level);
      this.prefilterProgram.set(gl, "uRoughness", (float) level / (PREFILTER_LEVELS - 1));
      for (int face = 0; face < 6; face++) {
        attach(gl, GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, this.prefiltered, level, size);
        this.prefilterProgram.set(gl, "uFace", face);
        gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
      }
    }
    gl.glBindVertexArray(0);
    gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_TEXTURE_2D, 0, 0);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
    gl.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
    gl.glDepthMask(true);
    gl.glEnable(GL3.GL_DEPTH_TEST);
  }

  /** The sky's source uniforms (and the image texture) for a target of {@code targetSize} texels per cube face. */
  private void setSource(GL3 gl, ShaderProgram p, Sky sky, int targetSize) {
    final var image = sky.image();
    p.set(gl, "uSource", image != null ? 1 : 0);
    p.set(gl, "uZenith", sky.zenith);
    p.set(gl, "uHorizon", sky.horizon);
    p.set(gl, "uGround", sky.ground);
    p.set(gl, "uEquirect", 0);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
    if (image != null) {
      uploadImage(gl, image);
      gl.glBindTexture(GL3.GL_TEXTURE_2D, this.equirect);
      // four cube faces span the image's width
      p.set(gl, "uEquirectLod", (float) Math.max(0.0, Math.log(image.width() / (4.0 * targetSize)) / Math.log(2)));
    } else {
      gl.glBindTexture(GL3.GL_TEXTURE_2D, this.black2d);
      p.set(gl, "uEquirectLod", 0f);
    }
  }

  private void uploadImage(GL3 gl, HdrImage image) {
    if (image == this.equirectImage) {
      return;
    }
    if (this.equirect != 0) {
      gl.glDeleteTextures(1, new int[] { this.equirect }, 0);
    }
    final FloatBuffer data = Buffers.newDirectFloatBuffer(image.rgb());
    this.equirect = texture2d(gl, image.width(), image.height(), GL3.GL_RGB16F, GL3.GL_RGB, data);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
    gl.glGenerateMipmap(GL3.GL_TEXTURE_2D);
    this.equirectImage = image;
  }

  private void attach(GL3 gl, int target, int texture, int level, int size) {
    gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, target, texture, level);
    final int status = gl.glCheckFramebufferStatus(GL3.GL_FRAMEBUFFER);
    if (status != GL3.GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("Sky framebuffer incomplete: 0x" + Integer.toHexString(status));
    }
    gl.glViewport(0, 0, size, size);
  }

  private int cube(GL3 gl, int size, int levels) {
    gl.glGenTextures(1, this.ids, 0);
    final int id = this.ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, id);
    for (int level = 0; level < levels; level++) {
      final int s = Math.max(1, size >> level);
      for (int face = 0; face < 6; face++) {
        gl.glTexImage2D(GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, GL3.GL_RGBA16F, s, s, 0, GL3.GL_RGBA,
            GL3.GL_FLOAT, null);
      }
    }
    final boolean mipmapped = levels > 1 || size == ENV_SIZE;
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MIN_FILTER,
        mipmapped ? GL3.GL_LINEAR_MIPMAP_LINEAR : GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, GL3.GL_TEXTURE_MAX_LEVEL,
        size == ENV_SIZE ? Integer.numberOfTrailingZeros(ENV_SIZE) : levels - 1);
    for (final int wrap : new int[] { GL3.GL_TEXTURE_WRAP_S, GL3.GL_TEXTURE_WRAP_T, GL3.GL_TEXTURE_WRAP_R }) {
      gl.glTexParameteri(GL3.GL_TEXTURE_CUBE_MAP, wrap, GL3.GL_CLAMP_TO_EDGE);
    }
    return id;
  }

  private void clearCube(GL3 gl, int cube, int size) {
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, this.fbo);
    gl.glGetIntegerv(GL3.GL_VIEWPORT, this.viewport, 0);
    gl.glClearColor(0f, 0f, 0f, 1f);
    for (int face = 0; face < 6; face++) {
      attach(gl, GL3.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cube, 0, size);
      gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
    }
    attach(gl, GL3.GL_TEXTURE_2D, this.black2d, 0, 1);
    gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
    gl.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_TEXTURE_2D, 0, 0);
    gl.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
    gl.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
  }

  private int texture2d(GL3 gl, int w, int h, int internal, int format, FloatBuffer data) {
    gl.glGenTextures(1, this.ids, 0);
    final int id = this.ids[0];
    gl.glBindTexture(GL3.GL_TEXTURE_2D, id);
    gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 4);
    gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL3.GL_FLOAT, data);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
    return id;
  }

  // ---- per frame ---------------------------------------------------------------------------------------------

  /**
   * The lighting uniforms of the main shader, and its three textures on the given units (black stand-ins without a
   * sky, so every sampler always has a texture of its type).
   */
  void bindLighting(GL3 gl, ShaderProgram basic, RenderSettings settings, int irradianceUnit, int prefilteredUnit,
      int lutUnit) {
    basic.set(gl, "uSky", this.active ? 1 : 0);
    basic.set(gl, "uIrradianceMap", irradianceUnit);
    basic.set(gl, "uPrefilteredMap", prefilteredUnit);
    basic.set(gl, "uBrdfLut", lutUnit);
    basic.set(gl, "uSkyIntensity", settings.skyIntensity);
    final double turn = Math.toRadians(settings.skyRotation);
    basic.set(gl, "uSkyRotation", (float) Math.cos(turn), (float) Math.sin(turn));
    basic.set(gl, "uPrefilteredLevels", (float) (PREFILTER_LEVELS - 1));
    gl.glActiveTexture(GL3.GL_TEXTURE0 + irradianceUnit);
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, this.active ? this.irradiance : this.blackCube);
    gl.glActiveTexture(GL3.GL_TEXTURE0 + prefilteredUnit);
    gl.glBindTexture(GL3.GL_TEXTURE_CUBE_MAP, this.active ? this.prefiltered : this.blackCube);
    gl.glActiveTexture(GL3.GL_TEXTURE0 + lutUnit);
    gl.glBindTexture(GL3.GL_TEXTURE_2D, this.active && this.lut != 0 ? this.lut : this.black2d);
    gl.glActiveTexture(GL3.GL_TEXTURE0);
  }

  /**
   * Draws the sky where nothing was drawn yet (depth still at the far plane). {@code sunDir} (towards the sun) and
   * {@code sunColor} are those of the first directional light, or null for none.
   */
  void drawBackground(GL3 gl, Matrix4f proj, Matrix4f view, RenderSettings settings, Vector3f sunDir,
      Vector3f sunColor) {
    final var sky = this.bakedSky;
    final var p = this.background;
    p.use(gl);
    setSource(gl, p, sky, 1024);
    p.set(gl, "uEquirectLod", 0f); // the image at full detail behind the scene
    p.set(gl, "uFace", -1);
    p.set(gl, "uInvProj", proj.invert(this.invProj));
    p.set(gl, "uViewToWorld", view.invert(this.viewToWorld));
    final double turn = Math.toRadians(settings.skyRotation);
    p.set(gl, "uRotation", (float) Math.cos(turn), (float) Math.sin(turn));
    p.set(gl, "uIntensity", settings.skyIntensity);
    final boolean sun = sky.sunDisc && sunDir != null;
    p.set(gl, "uSun", sun ? 1 : 0);
    if (sun) {
      p.set(gl, "uSunDir", sunDir);
      p.set(gl, "uSunColor", sunColor);
      p.set(gl, "uSunCos", (float) Math.cos(Math.toRadians(sky.sunSize)));
      p.set(gl, "uSunBrightness", sky.sunBrightness);
    }
    gl.glDepthMask(false);
    gl.glBindVertexArray(this.vao);
    gl.glDrawArrays(GL3.GL_TRIANGLES, 0, 3);
    gl.glBindVertexArray(0);
    gl.glDepthMask(true);
  }

  void dispose(GL3 gl) {
    if (this.background == null) {
      return;
    }
    this.background.dispose(gl);
    this.irradianceProgram.dispose(gl);
    this.prefilterProgram.dispose(gl);
    this.brdfProgram.dispose(gl);
    gl.glDeleteVertexArrays(1, new int[] { this.vao }, 0);
    gl.glDeleteFramebuffers(1, new int[] { this.fbo }, 0);
    gl.glDeleteTextures(7, new int[] { this.env, this.irradiance, this.prefiltered, this.lut, this.blackCube,
        this.black2d, this.equirect }, 0);
  }
}
