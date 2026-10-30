package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;

class CharacterControllerTest {
  private static final double DT = 1.0 / 60.0;
  /** Capsule: radius 0.3, straight part 1.2, so the feet are 0.9 below the entity origin. */
  private static final float FEET = 0.9f;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();

  private Entity box(String name, float x, float y, float z, float hx, float hy, float hz) {
    final var e = new Entity(name);
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(hx, hy, hz));
    this.scene.add(e);
    return e;
  }

  /** A 6 m ramp whose lower end lies on the floor at x = 1. */
  private void ramp(String name, float degrees, float z) {
    final double a = Math.toRadians(degrees);
    final var e = box(name, 1f + 3f * (float) Math.cos(a), 3f * (float) Math.sin(a), z, 3f, 0.05f, 1f);
    e.transform().setEulerDegrees(0f, 0f, degrees);
  }

  private Entity floor() {
    return box("floor", 0f, -0.5f, 0f, 30f, 0.5f, 30f); // top at y = 0
  }

  private CharacterController character(float x, float y, float z) {
    final var e = new Entity("player");
    e.transform().position().set(x, y, z);
    e.add(new CapsuleCollider(0.3f, 0.6f));
    final var c = e.add(new CharacterController());
    this.scene.add(e);
    return c;
  }

  private void simulate(double seconds) {
    this.scene.flush();
    for (int i = 0; i < (int) Math.round(seconds / DT); i++) {
      this.physics.update(this.scene, DT);
    }
  }

  private static Vector3f pos(CharacterController c) {
    return c.entity().transform().position();
  }

  private static float feet(CharacterController c) {
    return pos(c).y - FEET;
  }

  @Test
  void fallsAndStandsOnTheGround() {
    floor();
    final var c = character(0f, 3f, 0f);
    simulate(0.3);
    assertFalse(c.isGrounded());
    simulate(1.5);
    assertTrue(c.isGrounded());
    assertEquals(0f, feet(c), 3 * c.skinWidth);
    assertEquals(0f, c.velocity.y, 1e-6f);
    assertTrue((c.collisionFlags() & CharacterController.BELOW) != 0);
    assertSame(this.scene.entities().get(0).get(BoxCollider.class), c.ground());
    final float y = pos(c).y;
    simulate(1);
    assertEquals(y, pos(c).y, 1e-5f, "no creep while standing");
  }

  @Test
  void walksAndSlidesAlongAWall() {
    floor();
    box("wall", 0f, 1f, 2f, 5f, 1f, 0.25f); // its face at z = 1.75
    final var c = character(0f, FEET + 0.01f, 0f);
    c.walk(new Vector3f(2f, 5f, 2f)); // diagonally into the wall; the vertical part is ignored
    simulate(1);
    assertEquals(1.75f - 0.3f, pos(c).z, 3 * c.skinWidth, "stopped at the wall");
    assertTrue(pos(c).x > 1.5f, "kept going along it: " + pos(c).x);
    assertTrue((c.collisionFlags() & CharacterController.SIDES) != 0);
    assertTrue(c.isGrounded());
  }

  @Test
  void stepsUpLowLedgesButNotHighOnes() {
    floor();
    box("step", 2f, 0.15f, 0f, 0.5f, 0.15f, 3f); // 0.3 high, starting at x = 1.5
    box("block", 2f, 0.3f, 4f, 0.5f, 0.3f, 1f); // 0.6 high, at z = 3..5
    final var low = character(0f, FEET + 0.01f, 0f);
    low.walk(3f, 0f);
    final var high = character(0f, FEET + 0.01f, 4f);
    high.walk(3f, 0f);
    simulate(0.6);
    assertTrue(pos(low).x > 1.5f, "climbed the step: " + pos(low).x);
    assertEquals(0.3f, feet(low), 3 * low.skinWidth);
    assertEquals(1.5f - 0.3f, pos(high).x, 3 * high.skinWidth, "blocked by the high one");
    assertEquals(0f, feet(high), 3 * high.skinWidth);
  }

  @Test
  void walksUpGentleSlopesButNotSteepOnes() {
    floor();
    ramp("gentle", 25f, 0f);
    ramp("steep", 65f, 6f);
    final var a = character(0f, FEET + 0.01f, 0f);
    a.walk(2f, 0f);
    final var b = character(0f, FEET + 0.01f, 6f);
    b.walk(2f, 0f);
    simulate(2);
    assertTrue(feet(a) > 0.8f, "up the 25 degree ramp: " + feet(a));
    assertTrue(a.isGrounded());
    assertTrue(feet(b) < 0.4f, "not up the 65 degree one: " + feet(b));
    assertTrue(pos(b).x < 4f);
  }

  @Test
  void followsTheGroundDownStepsAndFallsOffLedges() {
    box("upper", 0f, 0.5f, 0f, 2f, 0.5f, 2f); // top at y = 1, edge at x = 2
    box("stair", 2.5f, 0.4f, 0f, 0.5f, 0.4f, 2f); // top at 0.8
    box("lower", 10f, -0.5f, 0f, 7f, 0.5f, 2f); // top at 0, from x = 3
    final var c = character(0f, 1f + FEET + 0.01f, 0f);
    c.walk(2f, 0f);
    simulate(0.1);
    assertTrue(c.isGrounded());
    // down the 0.2 step: stays on the ground every step
    boolean airborne = false;
    for (int i = 0; i < 40; i++) {
      simulate(DT);
      airborne |= !c.isGrounded() && pos(c).x < 2.9f;
    }
    assertFalse(airborne, "snapped down the small step");
    // off the 0.8 ledge at x = 3: falls, lands below
    simulate(0.5);
    simulate(1);
    assertTrue(c.isGrounded());
    assertEquals(0f, feet(c), 3 * c.skinWidth);
  }

  @Test
  void jumpsOncePerTakeOff() {
    floor();
    final var c = character(0f, FEET + 0.01f, 0f);
    simulate(0.1);
    assertTrue(c.jump(5f));
    simulate(DT);
    assertFalse(c.isGrounded());
    assertFalse(c.jump(5f), "no jump in mid-air");
    double t = 0;
    float top = 0f;
    while (!c.isGrounded() && t < 3) {
      simulate(DT);
      t += DT;
      top = Math.max(top, feet(c));
    }
    assertEquals(25f / (2f * 9.81f), top, 0.1f, "v^2 / 2g");
    assertTrue(c.isGrounded());
  }

  @Test
  void canStillJumpJustAfterWalkingOffAnEdge() {
    box("ledge", 0f, -0.5f, 0f, 1f, 0.5f, 1f);
    final var c = character(0.5f, FEET + 0.01f, 0f);
    simulate(0.1);
    c.walk(3f, 0f);
    while (c.isGrounded()) {
      simulate(DT);
    }
    simulate(DT);
    assertTrue(c.jump(4f), "within the coyote time");
    simulate(DT);
    assertTrue(c.velocity.y > 3f);
  }

  @Test
  void hitsItsHeadAndFallsBack() {
    floor();
    box("ceiling", 0f, 2.5f, 0f, 3f, 0.25f, 3f); // underside at 2.25
    final var c = character(0f, FEET + 0.01f, 0f);
    simulate(0.1);
    c.jump(8f);
    double t = 0;
    float top = 0f;
    boolean head = false;
    do {
      simulate(DT);
      t += DT;
      top = Math.max(top, pos(c).y + FEET);
      head |= (c.collisionFlags() & CharacterController.ABOVE) != 0;
    } while (!c.isGrounded() && t < 3);
    assertTrue(head);
    assertTrue(top <= 2.25f + 1e-3f, "the top of the capsule stayed under the ceiling: " + top);
    assertTrue(c.isGrounded());
  }

  @Test
  void ridesAMovingPlatform() {
    final var platform = box("platform", 0f, -0.25f, 0f, 2f, 0.25f, 2f);
    final var c = character(0f, FEET + 0.01f, 0f);
    simulate(0.2);
    assertTrue(c.isGrounded());
    final float x0 = pos(c).x;
    for (int i = 0; i < 60; i++) { // 1 m/s sideways and 0.5 m/s up, moved by code
      platform.transform().position().add(1f / 60f, 0.5f / 60f, 0f);
      simulate(DT);
    }
    assertEquals(x0 + 1f, pos(c).x, 0.05f);
    assertEquals(0.5f, feet(c), 0.05f);
    assertTrue(c.isGrounded());
    for (int i = 0; i < 60; i++) { // turns a quarter about its centre: the character is carried round
      platform.transform().rotation().rotateY((float) Math.toRadians(90.0 / 60.0));
      simulate(DT);
    }
    final var centre = platform.transform().position();
    final float dx = pos(c).x - centre.x;
    final float dz = pos(c).z - centre.z;
    assertEquals(0f, dx, 0.1f);
    assertEquals(0f, dz, 0.1f, "stood at the centre, stays there");
  }

  @Test
  void ridesAPlatformAwayFromItsCentre() {
    final var platform = box("platform", 0f, -0.25f, 0f, 3f, 0.25f, 3f);
    final var c = character(2f, FEET + 0.01f, 0f);
    simulate(0.2);
    for (int i = 0; i < 60; i++) {
      platform.transform().rotation().rotateY((float) Math.toRadians(90.0 / 60.0));
      simulate(DT);
    }
    // a quarter turn about +Y takes (2, 0) to (0, -2)
    assertEquals(0f, pos(c).x, 0.15f);
    assertEquals(-2f, pos(c).z, 0.15f);
  }

  @Test
  void pushesCratesAndIsBlockedByLayersItCollidesWith() {
    floor();
    final var crate = box("crate", 2f, 0.5f, 0f, 0.5f, 0.5f, 0.5f);
    crate.add(new Rigidbody()).mass = 20f;
    final var c = character(0f, FEET + 0.01f, 0f);
    c.walk(2f, 0f);
    simulate(2);
    assertTrue(crate.transform().position().x > 3f, "pushed the crate: " + crate.transform().position().x);
    assertTrue(pos(c).x > 1.5f);

    final var ghost = box("ghost", 0f, 1f, 4f, 0.5f, 1f, 0.5f);
    ghost.get(BoxCollider.class).layer = 7;
    final var d = character(0f, FEET + 0.01f, 2.5f);
    d.layerMask = ~PhysicsSystem.layers(7);
    d.walk(0f, 2f);
    simulate(1.5);
    assertTrue(pos(d).z > 5f, "walked through layer 7: " + pos(d).z);
  }

  @Test
  void isPushedOutOfSomethingThatMovesIntoIt() {
    floor();
    final var c = character(0f, FEET + 0.01f, 0f);
    simulate(0.1);
    box("door", 0.2f, 1f, 0f, 0.25f, 1f, 1f); // appears overlapping the capsule from the side
    simulate(DT * 3);
    assertTrue(pos(c).x < 0.2f - 0.25f - 0.3f + 1e-3f, "pushed out: " + pos(c).x);
    assertTrue(c.isGrounded());
  }

  @Test
  void bodiesLandOnTheCharacter() {
    floor();
    final var c = character(0f, FEET + 0.01f, 0f);
    final var crate = box("crate", 0f, 3f, 0f, 0.25f, 0.25f, 0.25f);
    crate.add(new Rigidbody());
    simulate(2);
    assertEquals(FEET + 0.9f + 0.25f, crate.transform().position().y, 0.05f, "rests on its head");
    assertTrue(c.isGrounded());
  }

  @Test
  void walksOnAMeshCollider() {
    final var ground = new Entity("terrain");
    // a ramp mesh: flat for x < 0, rising 0.3 per metre after (17 degrees)
    ground.add(new MeshCollider(new float[] { -5, 0, -2, 0, 0, -2, 0, 0, 2, -5, 0, 2, 5, 1.5f, -2, 5, 1.5f, 2 },
        new int[] { 0, 2, 1, 0, 3, 2, 1, 5, 4, 1, 2, 5 }));
    this.scene.add(ground);
    final var c = character(-3f, FEET + 0.01f, 0f);
    c.walk(2f, 0f);
    simulate(3);
    assertTrue(c.isGrounded());
    assertEquals(0.3f * pos(c).x, feet(c), 0.05f);
  }
}
