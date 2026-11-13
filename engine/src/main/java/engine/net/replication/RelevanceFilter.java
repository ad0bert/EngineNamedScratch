package engine.net.replication;

import java.util.List;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.net.Connection;

/**
 * Decides which entities a client gets ("interest management"): an entity that stops being relevant is despawned on
 * that client, and spawned again as it is then when it becomes relevant again. Entities the client owns and entities
 * marked {@link ServerReplication#setAlwaysRelevant always relevant} are not asked about. Checked every
 * {@link ServerReplication#setRelevanceInterval few steps}.
 */
@FunctionalInterface
public interface RelevanceFilter {
  /** Everything for everyone (the default). */
  RelevanceFilter ALL = (client, entity, owned, known) -> true;

  /**
   * Whether {@code client} should have {@code entity}. {@code owned} are the client's own replicated entities (its
   * player), {@code known} whether it has the entity now (to decide with some hysteresis).
   */
  boolean isRelevant(Connection client, Entity entity, List<Entity> owned, boolean known);

  /**
   * Entities within {@code radius} metres of any entity the client owns; once known, they stay until 10 % further
   * away, so that something on the edge does not come and go. A client that owns nothing gets everything.
   */
  static RelevanceFilter byDistance(float radius) {
    return byDistance(radius, null);
  }

  /** Like {@link #byDistance(float)}, measuring the short way in a world that wraps around (null: straight). */
  static RelevanceFilter byDistance(float radius, Wraparound wraparound) {
    final float enter = radius * radius;
    final float leave = radius * 1.1f * radius * 1.1f;
    final var a = new Vector3f();
    final var b = new Vector3f();
    return (client, entity, owned, known) -> {
      if (owned.isEmpty()) {
        return true;
      }
      final float limit = known ? leave : enter;
      entity.transform().worldPosition(a);
      for (Entity e : owned) {
        e.transform().worldPosition(b);
        if ((wraparound == null ? b.distanceSquared(a) : wraparound.distanceSquared(a, b)) <= limit) {
          return true;
        }
      }
      return false;
    };
  }
}
