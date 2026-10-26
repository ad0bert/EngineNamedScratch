package engine.render;

/** Opaque handle to a mesh living in a {@link RenderBackend}. */
public interface GpuMesh {
  MeshData data();

  /** Unique per mesh; used to group draws of the same mesh together. */
  int id();

  void dispose();
}
