package engine.render;

import java.util.List;
import java.util.function.Function;

import engine.ecs.Scene;

/**
 * Everything the engine needs from a graphics API. The game, ECS, resources and physics only see this interface and
 * the opaque {@link GpuMesh}/{@link GpuTexture} handles; {@code engine.render.gl} is the OpenGL implementation. All
 * methods must be called on the thread that owns the graphics context.
 */
public interface RenderBackend {
  /** Creates shaders and global state. Call once, when the context exists. */
  void init();

  void resize(int width, int height);

  GpuMesh createMesh(MeshData data);

  /**
   * @param srgb true for colour images (albedo, emissive), which are decoded from sRGB when sampled; false for data
   *             (normal, height, specular, roughness maps), which are used as stored
   */
  GpuTexture createTexture(ImageData image, boolean srgb);

  /** A colour texture (sRGB). */
  default GpuTexture createTexture(ImageData image) {
    return createTexture(image, true);
  }

  /**
   * Replaces a texture's pixels in place (hot reloading): everything that uses the handle shows the new image. Keeps
   * whether it is a colour or a data texture.
   */
  default void updateTexture(GpuTexture texture, ImageData image) {
    throw new UnsupportedOperationException("this backend cannot update textures");
  }

  /** Replaces a mesh's vertices and indices in place (hot reloading); renderers keep the handle. */
  default void updateMesh(GpuMesh mesh, MeshData data) {
    throw new UnsupportedOperationException("this backend cannot update meshes");
  }

  /**
   * Where shader sources come from: a function from a resource path such as {@code shaders/basic.frag} to the text,
   * or null to use the built-in shader. Set it before {@link #init()}; games use it to replace shaders, hot
   * reloading to edit them.
   */
  default void setShaderSource(Function<String, String> source) {
  }

  /**
   * Compiles the shaders that use {@code resource} again (all of them for null) from the shader source. A shader
   * that does not compile keeps running its old version (the error is logged). Returns how many failed.
   */
  default int reloadShaders(String resource) {
    return 0;
  }

  /** A 1x1 white texture. */
  GpuTexture whiteTexture();

  /**
   * Draws the scene at the interpolated state {@code alpha} (0..1 between the last two fixed updates), then the
   * overlays on top.
   */
  void render(Scene scene, float alpha, int width, int height, List<Overlay> overlays);

  /** Reads back the last rendered frame (scene and overlays) as RGBA pixels, bottom row first. */
  ImageData readPixels(int width, int height);

  /** Quality and culling switches, read every frame. */
  RenderSettings settings();

  /** Statistics of the last rendered frame. */
  RenderStats stats();

  void dispose();
}
