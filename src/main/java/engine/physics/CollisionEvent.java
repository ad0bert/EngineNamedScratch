package engine.physics;

import engine.ecs.Entity;

/** Sent on {@code MessageQueue.COLLISION} when two colliders start or stop overlapping. */
public record CollisionEvent(Entity a, Entity b, boolean started) {
}
