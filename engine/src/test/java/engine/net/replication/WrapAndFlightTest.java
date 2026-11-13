package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.net.LoopbackNetwork.Conditions;

/** Worlds that wrap around, entities that jump, and predicted players that fly. */
class WrapAndFlightTest {
  private static final Wraparound SPACE = ReplicationBed.SPACE;

  // ---- Wraparound ----

  @Test
  void wraparoundGoesTheShortWay() {
    final var w = Wraparound.cube(10f);
    assertEquals(new Vector3f(-9f, 0f, 9.5f), w.wrap(new Vector3f(11f, 20f, -10.5f)));
    assertEquals(new Vector3f(-10f, 0f, 0f), w.wrap(new Vector3f(10f, 0f, 0f)), "the far face is the near one");
    assertEquals(new Vector3f(2f, 0f, 0f), w.delta(new Vector3f(9f, 0, 0), new Vector3f(-9f, 0, 0), new Vector3f()));
    assertEquals(new Vector3f(-2f, 0f, 0f), w.delta(new Vector3f(-9f, 0, 0), new Vector3f(9f, 0, 0), new Vector3f()));
    assertEquals(4f, w.distanceSquared(new Vector3f(0, 9f, 0), new Vector3f(0, -9f, 0)), 1e-5f);
    final var half = w.lerp(new Vector3f(9f, 0, 0), new Vector3f(-9f, 0, 0), 0.5f, new Vector3f());
    assertEquals(-10f, half.x, 1e-5f, "half way is on the edge, not in the middle");
    final var quarter = w.lerp(new Vector3f(9f, 0, 0), new Vector3f(-9f, 0, 0), 0.25f, new Vector3f());
    assertEquals(9.5f, quarter.x, 1e-5f);
  }

  @Test
  void relevanceMeasuresAcrossTheEdge() {
    final var mine = new Entity("mine");
    mine.transform().position().set(19f, 0, 0);
    final var other = new Entity("other");
    other.transform().position().set(-19f, 0, 0);
    assertTrue(RelevanceFilter.byDistance(5f, SPACE).isRelevant(null, other, List.of(mine), false));
    assertFalse(RelevanceFilter.byDistance(5f).isRelevant(null, other, List.of(mine), false));
  }

  // ---- copies ----

  private static ReplicationBed wrappedBed(int guests) {
    final var bed = new ReplicationBed();
    bed.server.setWraparound(SPACE);
    for (int i = 0; i < guests; i++) {
      bed.addGuest().setWraparound(SPACE);
    }
    bed.connectAll();
    return bed;
  }

  @Test
  void copiesCrossTheEdgeTheShortWay() {
    final var bed = wrappedBed(1);
    final var marker = bed.server.spawn("marker", new Vector3f(15f, 0, 0));
    bed.step(30);
    final var copy = bed.copy(0, marker);
    final var previous = new Vector3f(copy.transform().position());
    final var drawn = new Vector3f();
    final var matrix = new Matrix4f();
    float longest = 0f;
    float widestDraw = 0f;
    for (int i = 0; i < 60; i++) {
      final var p = marker.transform().position();
      p.x += 0.2f; // 12 m/s to the right, through the face at x = 20
      SPACE.wrap(p);
      bed.step(1);
      final var now = copy.transform().position();
      longest = Math.max(longest, SPACE.distanceSquared(previous, now));
      previous.set(now);
      for (float alpha : new float[] { 0.25f, 0.5f, 0.75f }) { // what is drawn between steps
        copy.transform().interpolatedWorldMatrix(alpha, matrix).getTranslation(drawn);
        widestDraw = Math.max(widestDraw, SPACE.distanceSquared(drawn, now));
      }
    }
    assertTrue(marker.transform().position().x < 0f, "the host's marker wrapped: " + marker.transform().position());
    assertTrue(Math.sqrt(longest) < 0.5f, "the copy never jumped, longest step " + Math.sqrt(longest));
    assertTrue(Math.sqrt(widestDraw) < 0.5f, "drawn next to where it is, never across the cube: "
        + Math.sqrt(widestDraw));
    bed.step(20);
    assertTrue(SPACE.distanceSquared(copy.transform().position(), marker.transform().position()) < 1e-4f);
  }

  @Test
  void teleportedCopiesJumpInsteadOfSliding() {
    final var bed = wrappedBed(1);
    final var marker = bed.server.spawn("marker", new Vector3f(0, 0, 0));
    bed.step(30);
    final var copy = bed.copy(0, marker);
    marker.transform().position().set(8f, 0, 0);
    bed.server.teleported(marker);
    final List<Float> seen = new ArrayList<>();
    final var matrix = new Matrix4f();
    final var drawn = new Vector3f();
    for (int i = 0; i < 40; i++) {
      bed.step(1);
      seen.add(copy.transform().position().x);
      copy.transform().interpolatedWorldMatrix(0.5f, matrix).getTranslation(drawn);
      seen.add(drawn.x);
    }
    assertEquals(8f, copy.transform().position().x, 1e-3f, "arrived");
    assertTrue(seen.stream().allMatch(x -> x < 0.01f || x > 7.99f), "never in between: " + seen);

    // without the word from the host it slides, as before
    marker.transform().position().set(0f, 0, 0);
    final List<Float> slid = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      bed.step(1);
      slid.add(copy.transform().position().x);
    }
    assertTrue(slid.stream().anyMatch(x -> x > 1f && x < 7f), "slides when not told: " + slid);
  }

  @Test
  void lateJoinersGetTheJumpCount() {
    final var bed = wrappedBed(0);
    final var marker = bed.server.spawn("marker", new Vector3f(0, 0, 0));
    bed.step(5);
    marker.transform().position().set(5f, 0, 0);
    bed.server.teleported(marker);
    bed.step(5);
    bed.addGuest().setWraparound(SPACE);
    bed.connectAll();
    bed.step(30);
    final var copy = bed.copy(0, marker);
    marker.transform().position().set(6f, 0, 0); // an ordinary move after joining slides
    final List<Float> xs = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      bed.step(1);
      xs.add(copy.transform().position().x);
    }
    assertTrue(xs.stream().anyMatch(x -> x > 5.05f && x < 5.95f), "slid: " + xs);
  }

  // ---- flying, predicted ----

  /** Plays {@code script} by sample number. */
  private record Script(IntFunction<InputCommand> script, int[] samples) implements CommandSource {
    Script(IntFunction<InputCommand> script) {
      this(script, new int[1]);
    }

    @Override
    public InputCommand sample(long tick, double dt) {
      return this.script.apply(this.samples[0]++).withTickAndView(tick, -1);
    }
  }

  /** Full thrust while pulling up in a loop (past straight up and over), then a roll, drifting, braking to a stop. */
  private static Script loopTheLoop() {
    return new Script(n -> {
      final var aim = new Quaternionf();
      if (n < 180) {
        aim.rotateX((float) Math.toRadians(n * 2f)); // a whole loop in three seconds
      } else if (n < 240) {
        aim.rotateZ((float) Math.toRadians((n - 180) * 6f));
      }
      final float push = n < 240 ? 1f : 0f;
      final int buttons = n >= 300 ? InputCommand.button(1) : 0; // brake to a stop
      return InputCommand.flying(0, 0f, n < 60 ? 0.5f : 0f, push, aim, buttons);
    });
  }

  private static Entity hostShip(ReplicationBed bed) {
    return bed.server.entities().stream().filter(e -> e.has(FlightMotor.class)).findFirst().orElseThrow();
  }

  @Test
  void aFlyingPlayerIsPredictedThroughLoopsAndAcrossTheEdge() {
    final var bed = wrappedBed(2);
    bed.network.conditions(new Conditions(0.04, 0.01, 0.05, 0.01));
    final var client = bed.clients.get(0);
    bed.server.spawn("ship", new Vector3f(0, 0, 0), client.client().id());
    bed.step(30);
    final var mine = client.predictedEntities().getFirst();
    final var onHost = hostShip(bed);
    assertTrue(mine.get(FlightMotor.class).isPredictedHere());
    client.setCommandSource(loopTheLoop());
    bed.step(2);
    assertTrue(mine.transform().position().lengthSquared() > 0f, "moves at once");
    assertEquals(0f, onHost.transform().position().length(), 1e-5f, "before the host has heard of it");

    boolean wrapped = false;
    final var last = new Vector3f(mine.transform().position());
    final var drawn = new Vector3f();
    final var matrix = new Matrix4f();
    float widestDraw = 0f;
    for (int i = 0; i < 480; i++) {
      bed.step(1);
      final var p = mine.transform().position();
      wrapped |= last.distance(p) > 10f; // came in on the other side
      last.set(p);
      mine.transform().interpolatedWorldMatrix(0.5f, matrix).getTranslation(drawn);
      widestDraw = Math.max(widestDraw, SPACE.distanceSquared(drawn, p));
    }
    assertTrue(wrapped, "flew out of the cube and back in");
    assertTrue(Math.sqrt(widestDraw) < 1f, "drawn smoothly across the edge: " + Math.sqrt(widestDraw));
    final var a = mine.transform().position();
    final var b = onHost.transform().position();
    assertTrue(SPACE.distanceSquared(a, b) < 1e-6f, "client " + a + " host " + b);
    assertEquals(0f, onHost.get(FlightMotor.class).velocity().length(), 1e-6f, "braked to a stop");
    assertEquals(0f, mine.get(FlightMotor.class).velocity().length(), 1e-6f);
    assertTrue(mine.transform().rotation().equals(onHost.transform().rotation(), 1e-4f), "same orientation");
    assertTrue(client.corrections() <= 3, client.corrections() + " corrections");

    final var copy = bed.copy(1, onHost);
    assertNotNull(copy);
    assertTrue(bed.clients.get(1).predictedEntities().isEmpty(), "the other guest only follows");
    assertTrue(SPACE.distanceSquared(copy.transform().position(), b) < 1e-4f, "the other guest's copy");
  }

  @Test
  void aHyperspaceJumpOfTheOwnShipIsShownAtOnce() {
    final var bed = wrappedBed(1);
    bed.network.conditions(new Conditions(0.04, 0.0, 0.0, 0));
    final var client = bed.clients.get(0);
    bed.server.spawn("ship", new Vector3f(0, 0, 0), client.client().id());
    bed.step(30);
    final var mine = client.predictedEntities().getFirst();
    final var visual = new Entity("visual");
    mine.get(FlightMotor.class).visual = visual;
    bed.guests.get(0).scene().add(visual);
    client.setCommandSource(new Script(n -> InputCommand.flying(0, 0f, 0f, n < 30 ? 0.3f : 0f, new Quaternionf(),
        n < 60 ? 0 : InputCommand.button(1)))); // off it goes, then brakes to a stop
    bed.step(120);
    final int before = client.corrections();
    final var onHost = hostShip(bed);
    onHost.transform().position().set(10f, 10f, 10f); // the game's hyperspace
    bed.server.teleported(onHost);
    final var matrix = new Matrix4f();
    final var drawn = new Vector3f();
    float nearestToMiddle = Float.MAX_VALUE;
    for (int i = 0; i < 20; i++) {
      bed.step(1);
      mine.transform().interpolatedWorldMatrix(0.5f, matrix).getTranslation(drawn);
      nearestToMiddle = Math.min(nearestToMiddle, drawn.distance(5f, 5f, 5f));
    }
    assertTrue(client.corrections() > before, "corrected");
    assertTrue(mine.transform().position().distance(onHost.transform().position()) < 1e-3f);
    assertTrue(nearestToMiddle > 4f, "never drawn on the way: " + nearestToMiddle);
    assertTrue(visual.transform().position().length() < 1e-3f, "the visual did not try to slide 17 m");
  }

  @Test
  void flyingCommandsSurviveTheWire() {
    final var aim = new Quaternionf().rotateX(2.5f).rotateZ(1f); // upside down, rolled: no yaw and pitch can say it
    final var c = InputCommand.flying(77, 0.25f, -0.5f, 1f, aim, InputCommand.button(3));
    final var q = c.quantized();
    assertEquals(-0.5f, q.moveY(), 0.01f);
    assertEquals(0.25f, q.moveX(), 0.01f);
    assertTrue(q.orientation(new Quaternionf()).equals(aim, 1e-3f) || q.aim().equals(new Quaternionf(aim).scale(-1),
        1e-3f), "aim " + q.aim() + " vs " + aim);
    assertEquals(c.buttons(), q.buttons());
    assertNull(new InputCommand(1, 0f, 1f, 30f, 10f, 0, -1).quantized().aim(), "walking commands have no aim");
    assertEquals(0f, new InputCommand(1, 0f, 1f, 30f, 10f, 0, -1).quantized().moveY());
    final var walk = new InputCommand(1, 0f, 1f, 30f, 10f, 0, -1);
    final var w1 = new engine.net.BitWriter();
    walk.writeBody(w1);
    assertTrue(w1.byteLength() <= 8, "a walking command stays small: " + w1.byteLength());
  }

  @Test
  void theHostsOwnShipFliesToo() {
    final var bed = wrappedBed(1);
    final var ship = bed.server.spawn("ship", new Vector3f(0, 0, 0));
    bed.server.setLocalPlayer(ship, loopTheLoop());
    bed.step(200);
    final var copy = bed.copy(0, ship);
    assertTrue(ship.get(FlightMotor.class).velocity().length() > 1f);
    bed.step(200); // braked to a stop
    assertEquals(0f, ship.get(FlightMotor.class).velocity().length(), 1e-6f);
    assertTrue(SPACE.distanceSquared(copy.transform().position(), ship.transform().position()) < 1e-4f);
  }
}
