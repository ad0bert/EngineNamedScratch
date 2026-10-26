package engine.physics;

import org.joml.Vector3f;

import engine.ecs.Entity;

/**
 * Result of a raycast. {@code normal} points back against the ray; if the ray starts inside the shape, distance is 0.
 */
public record RayHit(Collider collider, Vector3f point, Vector3f normal, float distance) {
  public Entity entity() {
    return this.collider.entity();
  }
}
