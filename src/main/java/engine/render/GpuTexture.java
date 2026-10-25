package engine.render;

/** Opaque handle to a texture living in a {@link RenderBackend}. */
public interface GpuTexture {
  void dispose();
}
