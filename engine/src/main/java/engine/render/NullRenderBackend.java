package engine.render;

import java.util.List;

import engine.ecs.Scene;

/**
 * A render backend that draws nothing, for an engine without a window ({@link engine.core.Engine#runHeadless}).
 * Meshes keep their {@link MeshData}, so everything that reads geometry on the CPU (mesh colliders, navigation, bounds,
 * levels of detail) works as with a real backend; textures are empty handles.
 */
public final class NullRenderBackend implements RenderBackend {
  private static final GpuTexture TEXTURE = () -> {
  };

  private final RenderSettings settings = new RenderSettings();
  private final RenderStats stats = new RenderStats();
  private int nextMeshId;

  @Override
  public void init() {
  }

  @Override
  public void resize(int width, int height) {
  }

  @Override
  public GpuMesh createMesh(MeshData data) {
    return new NullMesh(++this.nextMeshId, data);
  }

  @Override
  public GpuTexture createTexture(ImageData image, boolean srgb) {
    return TEXTURE;
  }

  @Override
  public void updateTexture(GpuTexture texture, ImageData image) {
  }

  @Override
  public void updateMesh(GpuMesh mesh, MeshData data) {
    if (mesh instanceof NullMesh m) {
      m.data = data;
    }
  }

  @Override
  public GpuTexture whiteTexture() {
    return TEXTURE;
  }

  @Override
  public void render(Scene scene, float alpha, int width, int height, List<Overlay> overlays) {
  }

  /** A black image: there is no frame. */
  @Override
  public ImageData readPixels(int width, int height) {
    return new ImageData(width, height, new byte[width * height * 4]);
  }

  @Override
  public RenderSettings settings() {
    return this.settings;
  }

  @Override
  public RenderStats stats() {
    return this.stats;
  }

  @Override
  public void dispose() {
  }

  private static final class NullMesh implements GpuMesh {
    private final int id;
    private MeshData data;

    NullMesh(int id, MeshData data) {
      this.id = id;
      this.data = data;
    }

    @Override
    public MeshData data() {
      return this.data;
    }

    @Override
    public int id() {
      return this.id;
    }

    @Override
    public void dispose() {
    }
  }
}
