package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.render.ParticleEmitter;

class ParticleCollisionTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  /** A burst of particles falling from 2 m onto a floor whose top is at y = 0. */
  private ParticleEmitter fountain() {
    final var floor = new Entity("floor");
    floor.transform().position().set(0f, -0.5f, 0f);
    floor.add(new BoxCollider(10f, 0.5f, 10f));
    this.scene.add(floor);
    final var e = new Entity("rain");
    e.transform().position().set(0f, 2f, 0f);
    final var emitter = e.add(new ParticleEmitter()).seed(3L);
    emitter.emitting = false;
    emitter.lifetimeMin = emitter.lifetimeMax = 5f;
    emitter.speedMin = emitter.speedMax = 1f;
    emitter.direction.set(0f, -1f, 0f);
    emitter.acceleration.set(0f, -9.81f, 0f);
    emitter.burst(50);
    this.scene.add(e);
    this.scene.flush();
    this.physics.update(this.scene, DT); // builds the grid the particle rays use
    return emitter;
  }

  private void run(ParticleEmitter emitter, double seconds) {
    for (int i = 0; i < (int) (seconds / DT); i++) {
      emitter.update(DT);
    }
  }

  /** The lowest particle, from the emitter's bounds (world space: the emitter does not move). */
  private static float lowest(ParticleEmitter emitter) {
    final var center = new Vector3f();
    final float r = emitter.bounds(new Matrix4f(), center);
    return center.y - r;
  }

  @Test
  void particlesPassThroughWithoutACollider() {
    final var emitter = fountain();
    run(emitter, 1.5);
    assertTrue(lowest(emitter) < -2f);
  }

  @Test
  void particlesBounceAndSettleOnTheFloor() {
    final var emitter = fountain();
    emitter.collider = this.physics.particleCollider(this.scene, ~0);
    emitter.bounce = 0.4f;
    run(emitter, 0.7);
    assertEquals(50, emitter.count());
    assertTrue(lowest(emitter) > -0.6f, "none fell through: " + lowest(emitter));
    run(emitter, 3);
    final var center = new Vector3f();
    emitter.bounds(new Matrix4f(), center); // the radius also covers their spread sideways
    assertEquals(0f, center.y, 0.05f, "resting on the floor");
  }

  @Test
  void particlesCanDieWhereTheyHit() {
    final var emitter = fountain();
    emitter.collider = this.physics.particleCollider(this.scene, ~0);
    emitter.dieOnCollision = true;
    run(emitter, 1.5);
    assertEquals(0, emitter.count());
  }

  @Test
  void layersTheColliderDoesNotSeeLetParticlesThrough() {
    final var emitter = fountain();
    this.scene.entities().get(0).get(BoxCollider.class).layer = 4;
    emitter.collider = this.physics.particleCollider(this.scene, ~PhysicsSystem.layers(4));
    run(emitter, 1.5);
    assertTrue(lowest(emitter) < -2f);
  }
}
