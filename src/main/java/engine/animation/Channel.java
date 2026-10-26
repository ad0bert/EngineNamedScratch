package engine.animation;

/**
 * One animated value of a clip: a {@link Track} driving either a transform property of an entity (the animator's own,
 * or a descendant found by name) or anything else through a {@link Target}.
 *
 * @param node     name of the entity whose transform is animated; empty for the animator's entity
 * @param property the transform property, or null for a custom target
 * @param target   receives the values of a custom channel, null for transform channels
 */
public record Channel(String node, Property property, Target target, Track track) {
  /** What a transform channel animates, and how many floats it takes. */
  public enum Property {
    POSITION(3), ROTATION(4), SCALE(3), EULER_DEGREES(3);

    public final int width;

    Property(int width) {
      this.width = width;
    }
  }

  /** Receives the animated values of a custom channel ({@code values.length} may exceed the track width). */
  @FunctionalInterface
  public interface Target {
    void apply(float[] values);
  }

  public Channel {
    if (property != null && track.width() != property.width) {
      throw new IllegalArgumentException(property + " needs " + property.width + " values per key");
    }
    if ((property == null) == (target == null)) {
      throw new IllegalArgumentException("a channel animates either a transform property or a target");
    }
    node = node != null ? node : "";
  }

  /** Animates a transform property of the animator's entity ({@code node} empty) or of a named descendant. */
  public static Channel transform(String node, Property property, Track track) {
    return new Channel(node, property, null, track);
  }

  /** Animates anything: material colours, light intensities, audio volumes... */
  public static Channel custom(Target target, Track track) {
    return new Channel("", null, target, track);
  }
}
