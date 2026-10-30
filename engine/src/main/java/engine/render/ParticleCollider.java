package engine.render;

/**
 * What {@link ParticleEmitter} particles collide with: answers whether a short move hits a surface, and where.
 * {@code engine.physics.PhysicsSystem#particleCollider} answers with the physics world's colliders.
 */
@FunctionalInterface
public interface ParticleCollider {
  /**
   * True if the segment from (x0, y0, z0) to (x1, y1, z1) hits something; then {@code hit} holds the point (0..2) and
   * the surface normal facing the segment's start (3..5).
   */
  boolean collide(float x0, float y0, float z0, float x1, float y1, float z1, float[] hit);
}
