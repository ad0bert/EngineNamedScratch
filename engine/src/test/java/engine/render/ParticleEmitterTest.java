package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.jogamp.common.nio.Buffers;

import engine.ecs.Entity;
import engine.serialization.ComponentRegistry;
import engine.serialization.Json;

class ParticleEmitterTest {
  private static final double DT = 1.0 / 60.0;
  private static final int F = ParticleEmitter.FLOATS_PER_INSTANCE;

  private static ParticleEmitter emitter(float x, float y, float z) {
    final var e = new Entity();
    e.transform().position().set(x, y, z);
    e.transform().snapshot();
    return e.add(new ParticleEmitter().seed(42));
  }

  private static void run(ParticleEmitter p, int steps) {
    for (int i = 0; i < steps; i++) {
      p.entity().transform().snapshot();
      p.update(DT);
    }
  }

  private static FloatBuffer write(ParticleEmitter p, Vector3f cameraPos, Vector3f forward) {
    final var out = Buffers.newDirectFloatBuffer(p.count() * F);
    final var world = p.entity().transform().worldMatrix(new Matrix4f());
    assertEquals(p.count(), p.writeInstances(1f, world, cameraPos, forward, out, 0));
    return out;
  }

  @Test
  void spawnsAtTheRateAndDiesAfterItsLifetime() {
    final var p = emitter(0f, 0f, 0f);
    p.rate = 30f;
    p.lifetimeMin = 0.5f;
    p.lifetimeMax = 0.5f;
    run(p, 20); // a third of a second: nobody has died yet
    assertEquals(10, p.count());
    run(p, 120);
    assertEquals(15, p.count(), 1, "steady state: rate * lifetime");
    p.emitting = false;
    assertFalse(p.isFinished());
    run(p, 40);
    assertEquals(0, p.count());
    assertTrue(p.isFinished());
  }

  @Test
  void burstsRespectTheCapacity() {
    final var p = emitter(0f, 0f, 0f);
    p.emitting = false;
    p.maxParticles = 50;
    p.burst(80);
    run(p, 1);
    assertEquals(50, p.count());
    p.clear();
    assertEquals(0, p.count());
    p.maxParticles = 10; // shrinking drops the excess
    p.burst(30);
    run(p, 1);
    assertEquals(10, p.count());
  }

  @Test
  void particlesFallWithGravityAndStayWhereTheyWereBorn() {
    final var p = emitter(0f, 10f, 0f);
    p.emitting = false;
    p.speedMin = 0f;
    p.speedMax = 0f;
    p.lifetimeMin = 10f;
    p.lifetimeMax = 10f;
    p.acceleration.set(0f, -9.81f, 0f);
    p.burst(1);
    run(p, 1);
    p.entity().transform().position().set(5f, 0f, 0f); // the emitter moves away; world-space particles stay
    run(p, 60);
    final var out = write(p, new Vector3f(), new Vector3f(0f, 0f, -1f));
    assertEquals(0f, out.get(0), 1e-4f);
    // semi-implicit Euler over one second of 60 steps: 9.81 * (1 + 2 + ... + 60) / 3600
    assertEquals(10f - 9.81f * 61f / 120f, out.get(1), 1e-3f);
  }

  @Test
  void dragSlowsParticlesDown() {
    final var p = emitter(0f, 0f, 0f);
    p.emitting = false;
    p.spreadDegrees = 0f;
    p.speedMin = 4f;
    p.speedMax = 4f;
    p.lifetimeMin = 10f;
    p.lifetimeMax = 10f;
    p.drag = 2f;
    p.burst(1);
    run(p, 1);
    run(p, 300);
    final float y = write(p, new Vector3f(), new Vector3f(0f, 0f, -1f)).get(1);
    // without drag it would be 20 units up; with drag it approaches speed / drag
    assertTrue(y > 1.5f && y < 2.1f, "y = " + y);
  }

  @Test
  void spreadAndDirectionFollowTheEntity() {
    final var p = emitter(0f, 0f, 0f);
    p.entity().transform().rotation().rotateX((float) Math.toRadians(-90)); // local up now points to -Z
    p.emitting = false;
    p.spreadDegrees = 10f;
    p.lifetimeMin = 5f;
    p.lifetimeMax = 5f;
    p.burst(200);
    run(p, 31);
    final var out = write(p, new Vector3f(), new Vector3f(0f, 0f, -1f));
    final var v = new Vector3f();
    for (int i = 0; i < p.count(); i++) {
      v.set(out.get(i * F), out.get(i * F + 1), out.get(i * F + 2)).normalize();
      assertTrue(v.dot(0f, 0f, -1f) >= Math.cos(Math.toRadians(10.01)), "inside the cone: " + v);
    }
  }

  @Test
  void alphaParticlesAreWrittenBackToFront() {
    final var p = emitter(0f, 0f, -10f);
    p.emitting = false;
    p.spreadDegrees = 180f;
    p.shapeRadius = 3f;
    p.burst(100);
    run(p, 2);
    final var forward = new Vector3f(0f, 0f, -1f);
    final var out = write(p, new Vector3f(), forward);
    for (int i = 1; i < p.count(); i++) {
      assertTrue(-out.get(i * F + 2) <= -out.get((i - 1) * F + 2) + 1e-5f, "sorted far to near");
    }
  }

  @Test
  void coloursAreLinearPremultipliedAndAdditiveHasNoCoverage() {
    final var p = emitter(0f, 0f, 0f);
    p.emitting = false;
    p.colorStart.set(1f, 0.5f, 0f, 0.5f);
    p.colorEnd.set(1f, 0.5f, 0f, 0.5f);
    p.brightness = 2f;
    p.burst(1);
    run(p, 1);
    var out = write(p, new Vector3f(0f, 0f, 5f), new Vector3f(0f, 0f, -1f));
    assertEquals(1f * 0.5f * 2f, out.get(4), 1e-4f);
    assertEquals(0.2140f * 0.5f * 2f, out.get(5), 1e-3f); // sRGB 0.5 is linear 0.214
    assertEquals(0.5f, out.get(7), 1e-6f);
    p.blend = ParticleEmitter.Blend.ADDITIVE;
    out = write(p, new Vector3f(0f, 0f, 5f), new Vector3f(0f, 0f, -1f));
    assertEquals(0f, out.get(7));
  }

  @Test
  void sizeAndAtlasFramesFollowTheLife() {
    final var p = emitter(0f, 0f, 0f);
    p.emitting = false;
    p.lifetimeMin = 1f;
    p.lifetimeMax = 1f;
    p.sizeStart = 1f;
    p.sizeEnd = 3f;
    p.atlasColumns = 2;
    p.atlasRows = 2;
    p.burst(1);
    run(p, 1);
    run(p, 30); // half its life
    final var out = write(p, new Vector3f(0f, 0f, 5f), new Vector3f(0f, 0f, -1f));
    assertEquals(2f, out.get(3), 1e-3f);
    assertEquals(2f, out.get(9)); // frame 2 of 0..3
  }

  @Test
  void localSpaceParticlesMoveWithTheEntity() {
    final var p = emitter(0f, 0f, 0f);
    p.localSpace = true;
    p.emitting = false;
    p.speedMin = 0f;
    p.speedMax = 0f;
    p.burst(1);
    run(p, 1);
    p.entity().transform().position().set(3f, 0f, 0f);
    run(p, 1);
    final var out = write(p, new Vector3f(0f, 0f, 5f), new Vector3f(0f, 0f, -1f));
    assertEquals(3f, out.get(0), 1e-5f);
    final var center = new Vector3f();
    final float radius = p.bounds(p.entity().transform().worldMatrix(new Matrix4f()), center);
    assertEquals(3f, center.x, 1e-5f);
    assertTrue(radius > 0f);
  }

  @Test
  void aMovingEmitterLeavesAContinuousTrail() {
    final var p = emitter(0f, 0f, 0f);
    p.rate = 600f; // ten per step
    p.speedMin = 0f;
    p.speedMax = 0f;
    p.lifetimeMin = 5f;
    p.lifetimeMax = 5f;
    run(p, 1);
    p.clear();
    p.entity().transform().position().set(1f, 0f, 0f);
    run(p, 1);
    final var out = write(p, new Vector3f(0f, 0f, 5f), new Vector3f(0f, 0f, -1f));
    float min = 1f;
    float max = 0f;
    for (int i = 0; i < p.count(); i++) {
      min = Math.min(min, out.get(i * F));
      max = Math.max(max, out.get(i * F));
    }
    assertEquals(10, p.count());
    assertTrue(min < 0.1f && max > 0.9f, "spread along the path: " + min + ".." + max);
  }

  @Test
  void sameSeedSameParticles() {
    final var a = emitter(0f, 0f, 0f);
    final var b = emitter(0f, 0f, 0f);
    a.spreadDegrees = b.spreadDegrees = 90f;
    run(a, 50);
    run(b, 50);
    final var cam = new Vector3f(0f, 0f, 5f);
    final var fwd = new Vector3f(0f, 0f, -1f);
    assertEquals(write(a, cam, fwd), write(b, cam, fwd));
  }

  @Test
  void isSavedWithItsSettings() {
    final var registry = ComponentRegistry.defaults();
    final var serializer = registry.forClass(ParticleEmitter.class);
    final var p = new ParticleEmitter();
    p.rate = 7f;
    p.blend = ParticleEmitter.Blend.ADDITIVE;
    p.acceleration.set(0f, -2f, 0f);
    final var data = Json.asObject(Json.parse(Json.write(serializer.write(p, null))));
    final var copy = serializer.read(data, null);
    assertEquals(7f, copy.rate);
    assertEquals(ParticleEmitter.Blend.ADDITIVE, copy.blend);
    assertEquals(-2f, copy.acceleration.y);
  }
}
