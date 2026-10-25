package engine.render;

import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector4f;

/** Surface description. A {@code null} texture renders the plain colour. */
public class Material {
  private static final AtomicInteger NEXT_KEY = new AtomicInteger();

  /** Unique per material; used to group draws that share a material. */
  public final int sortKey = NEXT_KEY.incrementAndGet();
  public GpuTexture texture;
  /** Tangent-space normal map (OpenGL convention, +Y up); needs the mesh to have texture coordinates. */
  public GpuTexture normalMap;
  /** Strength of the normal map: 0 flattens it, 1 is as authored, above 1 exaggerates. */
  public float normalScale = 1f;
  /** Set for normal maps authored with the DirectX convention (green channel pointing down). */
  public boolean normalMapFlipY;
  public final Vector4f color = new Vector4f(1f, 1f, 1f, 1f);
  public float specular = 0.3f;
  public float shininess = 32f;
  /** Skip lighting and output texture * colour directly. */
  public boolean unlit;
  /** Alpha-blended; drawn after all opaque geometry, sorted back to front, and never casts shadows. */
  public boolean transparent;
  public float pointSize = 6f;

  public Material() {
  }

  public Material(GpuTexture texture) {
    this.texture = texture;
  }

  public Material color(float r, float g, float b, float a) {
    this.color.set(r, g, b, a);
    if (a < 1f) {
      this.transparent = true;
    }
    return this;
  }

  public Material unlit() {
    this.unlit = true;
    return this;
  }

  public Material transparent() {
    this.transparent = true;
    return this;
  }
}
