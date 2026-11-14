package engine.kits.toon;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import engine.render.Material;
import engine.render.PostPass;
import engine.render.RenderSettings;

/**
 * Cel shading built on the engine's shader library: materials lit in a few flat bands ({@link #apply}), and ink
 * outlines where the depth jumps ({@link #addOutline}, or {@link ToonPlugin} to have it installed with the engine).
 * The shaders are in this jar, under {@code shaders/kits/}.
 */
public final class ToonShading {
  /** The material shader: the standard material with banded light. */
  public static final String SHADER = "shaders/kits/toon.frag";
  /** The outline post pass. */
  public static final String OUTLINE = "shaders/kits/outline.frag";

  private ToonShading() {
  }

  /** Makes {@code material} cel-shaded with {@code bands} steps of light (hard edges between them). */
  public static Material apply(Material material, int bands) {
    return apply(material, bands, 0.02f);
  }

  /** Like {@link #apply(Material, int)}; {@code softness} 0..0.5 blurs the edges between the bands. */
  public static Material apply(Material material, int bands, float softness) {
    if (bands < 1) {
      throw new IllegalArgumentException("at least one band");
    }
    material.shader(SHADER);
    material.params.set("uBands", (float) bands).set("uSoftness", Math.clamp(softness, 0f, 0.5f));
    return material;
  }

  /** Adds black outlines of {@code width} pixels after tone mapping and returns the pass (to change or remove it). */
  public static PostPass addOutline(RenderSettings settings, float width) {
    return addOutline(settings, width, new Vector3f(), 0.05f);
  }

  /**
   * Adds outlines of {@code color} (sRGB, 0..1) and {@code width} pixels where the distance jumps by more than
   * {@code threshold} of itself.
   */
  public static PostPass addOutline(RenderSettings settings, float width, Vector3fc color, float threshold) {
    final var pass = settings.addPostPass(OUTLINE, PostPass.Stage.DISPLAY);
    pass.params.set("uColor", color).set("uWidth", width).set("uThreshold", threshold);
    return pass;
  }
}
