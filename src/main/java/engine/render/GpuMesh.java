package engine.render;

/** Opaque handle to a mesh living in a {@link RenderBackend}. */
public interface GpuMesh {
  MeshData data();

  void dispose();
}
