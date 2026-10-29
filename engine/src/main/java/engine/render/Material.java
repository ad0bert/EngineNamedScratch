package engine.render;

import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Surface description. A {@code null} texture renders the plain colour. Colours and colour textures are sRGB (as
 * painted); lighting happens in linear space. Optional maps: a tangent-space normal map, a
 * specular map (scales {@link #specular}), an emissive map (scales {@link #emissive}) and a height map for parallax
 * occlusion mapping (white is high). All maps use the mesh's texture coordinates.
 */
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
  /** Greyscale (or coloured, averaged) map that scales {@link #specular} per texel. */
  public GpuTexture specularMap;
  /** Height map (white = high) for parallax occlusion mapping; needs texture coordinates. */
  public GpuTexture heightMap;
  /** Depth of the height map's relief in uv units; 0.02 to 0.08 looks right for most surfaces. */
  public float heightScale = 0.04f;
  public final Vector4f color = new Vector4f(1f, 1f, 1f, 1f);
  public float specular = 0.3f;
  public float shininess = 32f;
  /**
   * Use the physically based metallic-roughness model (GGX) instead of Blinn-Phong: {@link #metallic},
   * {@link #roughness} and {@link #metalRoughMap} replace {@link #specular} and {@link #shininess}.
   */
  public boolean pbr;
  /** 0 for dielectrics (wood, plastic, stone), 1 for bare metal; in between for blends. */
  public float metallic;
  /** 0 mirror-smooth to 1 completely rough. */
  public float roughness = 0.6f;
  /** Scales {@link #roughness} by its green and {@link #metallic} by its blue channel (the glTF layout). */
  public GpuTexture metalRoughMap;
  /** How much a non-metal reflects head-on (Fresnel F0); 0.04 suits most materials, water 0.02, gems more. */
  public float reflectance = 0.04f;
  /** Multiplies the scene's ambient light on this surface (sRGB). */
  public final Vector3f ambient = new Vector3f(1f);
  /** Texture coordinates are multiplied by this and then shifted by {@link #uvOffset} (all maps). */
  public final org.joml.Vector2f uvScale = new org.joml.Vector2f(1f);
  public final org.joml.Vector2f uvOffset = new org.joml.Vector2f();
  /** Clamp texture coordinates to 0..1 instead of repeating the textures. */
  public boolean clampUv;
  /** Light the surface gives off by itself, independent of the lights; black for none. */
  public final Vector3f emissive = new Vector3f();
  /** Multiplies {@link #emissive} per texel. */
  public GpuTexture emissiveMap;
  /**
   * Cut-out transparency: fragments whose alpha is below this are discarded, in the scene and in shadow maps (leaves,
   * fences). 0 disables it. Unlike {@link #transparent} the surface stays opaque, sorted for batching and shadowed.
   */
  public float alphaCutoff;
  /** Skip lighting and output texture * colour directly. */
  public boolean unlit;
  /** Alpha-blended; drawn after all opaque geometry, sorted back to front, and never casts shadows. */
  public boolean transparent;
  /**
   * Glass (implies {@link #transparent}): the surface's own light (reflections, highlights, emissive) is added in full
   * however see-through it is, and it lets through what is behind it scaled by one minus its alpha. The alpha rises
   * with Fresnel from {@link #color}'s alpha where it is looked at head-on to opaque at grazing angles, so a clear pane
   * reflects most towards its edges. Looks best with {@link #pbr}, a low roughness and a dark colour (the tint).
   */
  public boolean glass;
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

  /** Switches to the metallic-roughness model. */
  public Material pbr(float metallic, float roughness) {
    this.pbr = true;
    this.metallic = metallic;
    this.roughness = roughness;
    return this;
  }

  public Material transparent() {
    this.transparent = true;
    return this;
  }

  /** Makes this {@link #glass}: see-through with {@code alpha} where it is looked at head-on, reflections in full. */
  public Material glass(float alpha) {
    this.color.w = alpha;
    this.transparent = true;
    this.glass = true;
    return this;
  }
}
