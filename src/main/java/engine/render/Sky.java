package engine.render;

import org.joml.Vector3f;

/**
 * What surrounds the scene: drawn behind everything, and the source of image-based lighting (set it as
 * {@link RenderSettings#sky}). Surfaces then take their ambient light from it (diffuse irradiance) and physically
 * based materials reflect it (blurrier the rougher they are), instead of the flat ambient colour of the first light.
 *
 * <p>
 * Either a procedural sky (a gradient from {@link #ground} through {@link #horizon} to {@link #zenith}, plus a sun
 * disc drawn towards the first directional light), or an equirectangular high dynamic range image (for example a
 * Radiance {@code .hdr} file read with {@code engine.resources.HdrLoader}). Colours are linear radiance; 1 is as
 * bright as a white surface in full light.
 *
 * <p>
 * The backend bakes the lighting once (on the GPU, a fraction of a second even on a software renderer) and again
 * only when the image or the gradient colours change; {@link RenderSettings#skyIntensity} and
 * {@link RenderSettings#skyRotation} cost nothing to change. The sun disc is only drawn, not baked: the directional
 * light already lights the scene, so moving the sun never re-bakes.
 */
public final class Sky {
  private final HdrImage image;
  /** Procedural: straight up. */
  public final Vector3f zenith = new Vector3f(0.09f, 0.19f, 0.5f);
  /** Procedural: at the horizon. */
  public final Vector3f horizon = new Vector3f(0.42f, 0.5f, 0.62f);
  /** Procedural: below the horizon (what the lower half of the scene sees, the light bounced off the ground). */
  public final Vector3f ground = new Vector3f(0.13f, 0.12f, 0.1f);
  /** Draw a sun disc towards the first directional light, in its colour. */
  public boolean sunDisc = true;
  /** Angular radius of the sun disc, degrees. */
  public float sunSize = 0.8f;
  /** Brightness of the sun disc relative to the light's colour (so it glows through bloom). */
  public float sunBrightness = 30f;

  private Sky(HdrImage image) {
    this.image = image;
  }

  /** A blue gradient sky with a sun; change the colours freely. */
  public static Sky procedural() {
    return new Sky(null);
  }

  /** An equirectangular image (2:1, longitude across, the top row straight up); no sun disc is added. */
  public static Sky image(HdrImage image) {
    final var sky = new Sky(image);
    sky.sunDisc = false;
    return sky;
  }

  /** The image, or null for a procedural sky. */
  public HdrImage image() {
    return this.image;
  }

  /** Changes whenever the baked lighting would change: the image, or the gradient colours. */
  public long bakeKey() {
    final long h = System.identityHashCode(this) * 31L
        + (this.image != null ? System.identityHashCode(this.image) : 0);
    return hash(hash(hash(h, this.zenith), this.horizon), this.ground);
  }

  private static long hash(long h, Vector3f v) {
    return ((h * 31 + Float.floatToIntBits(v.x)) * 31 + Float.floatToIntBits(v.y)) * 31 + Float.floatToIntBits(v.z);
  }
}
