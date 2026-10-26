package engine.render.gl;

import java.util.List;
import java.util.function.Supplier;

import com.jogamp.opengl.GL3;

import engine.ecs.Scene;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.ImageData;
import engine.render.MeshData;
import engine.render.Overlay;
import engine.render.RenderBackend;
import engine.render.RenderSettings;
import engine.render.RenderStats;
import lombok.Getter;

/** OpenGL 3.3 core implementation of {@link RenderBackend}. */
public final class GlBackend implements RenderBackend {
  private final Supplier<GL3> glSupplier;
  @Getter private final RenderStats stats = new RenderStats();
  @Getter private final RenderSettings settings = new RenderSettings();
  private final GlSceneRenderer scene = new GlSceneRenderer(this.stats, this.settings);
  private final GlUi ui = new GlUi();
  private GlTexture white;
  private GlTexture flatNormal;

  /** @param glSupplier returns the GL of the current context; called on the GL thread only */
  public GlBackend(Supplier<GL3> glSupplier) {
    this.glSupplier = glSupplier;
  }

  @Override
  public void init() {
    final var gl = this.glSupplier.get();
    this.white = new GlTexture(this.glSupplier, ImageData.solid(255, 255, 255, 255), false);
    this.flatNormal = new GlTexture(this.glSupplier, ImageData.solid(128, 128, 255, 255), false);
    this.scene.init(gl, this.white, this.flatNormal);
    this.ui.init(gl, this.glSupplier);
  }

  @Override
  public void resize(int width, int height) {
    this.glSupplier.get().glViewport(0, 0, width, Math.max(height, 1));
  }

  @Override
  public GpuMesh createMesh(MeshData data) {
    return new GlMesh(this.glSupplier, data);
  }

  @Override
  public GpuTexture createTexture(ImageData image, boolean srgb) {
    return new GlTexture(this.glSupplier, image, srgb);
  }

  @Override
  public GpuTexture whiteTexture() {
    return this.white;
  }

  @Override
  public void render(Scene scene, float alpha, int width, int height, List<Overlay> overlays) {
    final var gl = this.glSupplier.get();
    this.stats.reset();
    this.scene.render(gl, scene, alpha, width, height);
    this.ui.begin();
    for (Overlay o : overlays) {
      o.draw(this.ui, width, height);
    }
    this.ui.flush(gl, width, height);
  }

  @Override
  public ImageData readPixels(int width, int height) {
    final var gl = this.glSupplier.get();
    final var pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4);
    gl.glPixelStorei(GL3.GL_PACK_ALIGNMENT, 1);
    gl.glReadPixels(0, 0, width, height, GL3.GL_RGBA, GL3.GL_UNSIGNED_BYTE, pixels);
    final byte[] rgba = new byte[width * height * 4];
    pixels.get(rgba);
    for (int i = 3; i < rgba.length; i += 4) {
      rgba[i] = (byte) 255; // the window has no meaningful alpha
    }
    return new ImageData(width, height, rgba);
  }

  @Override
  public void dispose() {
    final var gl = this.glSupplier.get();
    this.scene.dispose(gl);
    this.ui.dispose(gl);
    if (this.white != null) {
      this.white.dispose();
      this.flatNormal.dispose();
    }
  }
}
