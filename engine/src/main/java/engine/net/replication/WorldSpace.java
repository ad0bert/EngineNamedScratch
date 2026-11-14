package engine.net.replication;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * How distances and directions work in the game's world, for the replication: how copies blend between two snapshots,
 * how far a prediction was off, how lag compensation rewinds and how {@link RelevanceFilter#byDistance(float,
 * WorldSpace)} measures. {@link #EUCLIDEAN}, the default, is ordinary space. A game whose world is shaped otherwise
 * (one that wraps around at its edges, say) implements this and sets it on the replication on the host and on clients
 * ({@link Replication#setWorldSpace}).
 *
 * <p>
 * The methods must agree with each other: {@code delta(a, b)} is the way from {@code a} to {@code b}, its length the
 * distance, and {@code lerp} walks along it, ending inside the world ({@link #normalize}).
 */
public interface WorldSpace {
  /** Ordinary, open space. */
  WorldSpace EUCLIDEAN = new WorldSpace() {
    @Override
    public String toString() {
      return "WorldSpace.EUCLIDEAN";
    }
  };

  /** The offset from {@code from} to {@code to}. */
  default Vector3f delta(Vector3fc from, Vector3fc to, Vector3f dest) {
    return dest.set(to).sub(from);
  }

  /** The distance between two points, squared. */
  default float distanceSquared(Vector3fc a, Vector3fc b) {
    return a.distanceSquared(b);
  }

  /** The point {@code t} of the way from {@code a} to {@code b}. */
  default Vector3f lerp(Vector3fc a, Vector3fc b, float t, Vector3f dest) {
    final var d = delta(a, b, new Vector3f());
    return normalize(dest.set(a).fma(t, d));
  }

  /** Puts {@code point} where it belongs in the world (in place) and returns it; nothing to do in open space. */
  default Vector3f normalize(Vector3f point) {
    return point;
  }

  /**
   * Whether points can jump while moving a short way (the world wraps around): then render interpolation is kept
   * from sweeping across it. False for open space.
   */
  default boolean wraps() {
    return false;
  }
}
