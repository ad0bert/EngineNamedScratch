package engine.render;

import java.util.List;

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

  GpuTexture createTexture(ImageData image);

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
