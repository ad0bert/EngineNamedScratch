package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.net.LoopbackNetwork.Conditions;
import engine.physics.CharacterController;

/** Client-side prediction of the player's own character, checked against the host. */
class PredictionTest {
  /** Yaw that looks along +X. */
  private static final float EAST = -90f;
  /** The button {@link CharacterMotor} jumps with by default. */
  private static final int JUMP = StandardCommand.button(0);

  /** A command source that plays {@code script} (by sample number, from 0). */
  private static final class Script implements CommandSource {
    final IntFunction<StandardCommand> script;
    int samples;

    Script(IntFunction<StandardCommand> script) {
      this.script = script;
    }

    @Override
    public StandardCommand sample(long tick, double dt) {
      final var c = this.script.apply(this.samples++);
      return new StandardCommand(tick, c.moveX(), c.moveZ(), c.yaw(), c.pitch(), c.buttons(), -1);
    }
  }

  private static StandardCommand walk(float forward, int buttons) {
    return new StandardCommand(0, 0f, forward, EAST, 10f, buttons, -1);
  }

  /** East for {@code steps} samples, a jump at sample 40, then standing still. */
  private static Script eastThenStop(int steps) {
    return new Script(n -> walk(n < steps ? 1f : 0f, n >= 40 && n < 45 ? JUMP : 0));
  }

  private static ReplicationBed bedWithWalker(Conditions conditions) {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.network.conditions(conditions);
    bed.server.spawn("walker", new Vector3f(0, 0.95f, 0), bed.clients.get(0).client().id());
    bed.step(30); // spawned and standing
    return bed;
  }

  private static Entity hostWalker(ReplicationBed bed) {
    return bed.server.entities().stream().filter(e -> e.has(CharacterMotor.class)).findFirst().orElseThrow();
  }

  private static Entity ownWalker(ReplicationBed bed, int guest) {
    return bed.clients.get(guest).predictedEntities().getFirst();
  }

  @Test
  void theOwnCharacterMovesAtOnceAndEndsWhereTheHostHasIt() {
    final var bed = bedWithWalker(new Conditions(0.04, 0.01, 0.02, 0));
    final var client = bed.clients.get(0);
    final var mine = ownWalker(bed, 0);
    final var onHost = hostWalker(bed);
    assertTrue(mine.get(CharacterController.class).isEnabled(), "predicted, not a copy");
    client.setCommandSource(eastThenStop(120));
    final float startX = mine.transform().position().x;
    bed.step(2);
    assertTrue(mine.transform().position().x > startX + 0.05f, "moves within two steps, no round trip");
    assertEquals(0f, onHost.transform().position().x, 1e-4f, "the host has not heard of it yet");
    bed.step(240);
    final var a = mine.transform().position();
    final var b = onHost.transform().position();
    assertTrue(a.x > 7f, "walked: " + a);
    assertTrue(a.distance(b) < 1e-3f, "client " + a + " host " + b);
    assertTrue(client.corrections() <= 2, client.corrections() + " corrections");
    assertTrue(client.commandLead() > 0.5 && client.commandLead() < 6, "lead " + client.commandLead());
    assertEquals(10f, onHost.get(CharacterMotor.class).pitch(), 0.05f, "the host used the client's look");
  }

  @Test
  void bothStopAtTheSameWall() {
    final var bed = bedWithWalker(new Conditions(0.03, 0.02, 0.05, 0.02));
    bed.clients.get(0).setCommandSource(eastThenStop(400));
    bed.step(420);
    final var a = ownWalker(bed, 0).transform().position();
    final var b = hostWalker(bed).transform().position();
    assertEquals(9.7f, b.x, 0.05f, "against the wall");
    assertTrue(a.distance(b) < 1e-3f, "client " + a + " host " + b);
  }

  @Test
  void theHostOverrulesThePrediction() {
    final var bed = bedWithWalker(new Conditions(0.04, 0.0, 0.0, 0));
    final var client = bed.clients.get(0);
    final var mine = ownWalker(bed, 0);
    final var visual = new Entity("visual");
    mine.get(CharacterMotor.class).visual = visual;
    bed.guests.get(0).scene().add(visual);
    client.setCommandSource(eastThenStop(200));
    bed.step(60);
    final int before = client.corrections();
    hostWalker(bed).get(CharacterController.class).teleport(new Vector3f(0, 0.95f, 5f)); // the game moved it
    bed.step(15);
    assertTrue(client.corrections() > before, "corrected");
    assertTrue(Math.abs(mine.transform().position().z - 5f) < 0.01f, "now where the host put it: "
        + mine.transform().position());
    bed.step(200);
    assertTrue(mine.transform().position().distance(hostWalker(bed).transform().position()) < 1e-3f);
    assertTrue(visual.transform().position().length() < 1e-3f, "the visual slid back home");
  }

  @Test
  void heavyLossStillAgrees() {
    final var bed = bedWithWalker(new Conditions(0.05, 0.03, 0.3, 0.05));
    final var client = bed.clients.get(0);
    client.setCommandSource(eastThenStop(150));
    bed.step(400);
    final var a = ownWalker(bed, 0).transform().position();
    final var b = hostWalker(bed).transform().position();
    assertTrue(b.x > 5f, "the host got the commands: " + b);
    assertTrue(a.distance(b) < 1e-3f, "client " + a + " host " + b);
  }

  @Test
  void otherPlayersSeeAnInterpolatedCopy() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.addGuest();
    bed.connectAll();
    bed.server.spawn("walker", new Vector3f(0, 0.95f, 0), bed.clients.get(0).client().id());
    bed.step(30);
    bed.clients.get(0).setCommandSource(eastThenStop(100));
    bed.step(200);
    final var hostEntity = hostWalker(bed);
    final var copy = bed.copy(1, hostEntity);
    assertNotNull(copy);
    assertFalse(copy.get(CharacterController.class).isEnabled(), "the other guest only follows");
    assertTrue(bed.clients.get(1).predictedEntities().isEmpty());
    assertTrue(copy.transform().position().distance(hostEntity.transform().position()) < 1e-3f);
    assertEquals(10f, copy.get(CharacterMotor.class).pitch(), 0.05f, "pitch arrives as state");
  }

  @Test
  void theHostsOwnPlayerIsDrivenLocally() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var hostPlayer = bed.server.spawn("walker", new Vector3f(0, 0.95f, 0));
    bed.server.setLocalPlayer(hostPlayer, eastThenStop(60));
    bed.step(150);
    final var copy = bed.copy(0, hostPlayer);
    assertTrue(hostPlayer.transform().position().x > 3f);
    assertTrue(copy.transform().position().distance(hostPlayer.transform().position()) < 1e-3f);
  }

  @Test
  void gameButtonsAndTheViewReachTheHost() {
    final var bed = bedWithWalker(new Conditions(0.04, 0.0, 0.0, 0));
    final int fire = StandardCommand.button(1);
    final var client = bed.clients.get(0);
    client.setCommandSource(new Script(n -> walk(0f, n >= 50 && n < 52 ? fire : 0)));
    final var motor = hostWalker(bed).get(CharacterMotor.class);
    final List<Long> presses = new ArrayList<>();
    final List<Double> lags = new ArrayList<>();
    bed.host.addStepHook(new engine.core.StepHook() {
      @Override
      public void afterStep(engine.core.Engine e, long tick, double dt) {
        if (motor.pressed(fire)) {
          presses.add(tick);
        }
        final double view = motor.lastCommand() == null ? -1 : motor.lastCommand().viewTick();
        if (view >= 0) {
          lags.add(tick - view);
        }
      }
    });
    bed.step(150);
    assertEquals(1, presses.size(), "pressed once (held for two steps)");
    assertTrue(bed.server.commandOf(client.client().id()).tick() > 0);
    final double meanLag = lags.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    // what the player sees: 0.1 s of interpolation, the trip to the client, the commands' lead
    assertTrue(meanLag > 6 && meanLag < 20, "the player looks " + meanLag + " steps into the past");
  }

  @Test
  void commandsSurviveTheWire() {
    final var c = new StandardCommand(1234, 0.5f, -1f, 725f, -30f, JUMP | StandardCommand.button(7), 1220.5);
    final var q = StandardCommand.CODEC.overTheWire(c);
    assertEquals(0.5f, q.moveX(), 0.01f);
    assertEquals(-1f, q.moveZ(), 1e-6f);
    assertEquals(5f, q.yaw(), 0.01f);
    assertEquals(-30f, q.pitch(), 0.02f);
    assertEquals(c.buttons(), q.buttons());
    assertEquals(1220.5, q.viewTick(), 0.01);
    assertEquals(q, StandardCommand.CODEC.overTheWire(q), "quantizing twice changes nothing");
    final var out = new engine.net.BitWriter();
    Replication.writeCommand(StandardCommand.CODEC, c, out);
    assertTrue(out.byteLength() <= 10, "a command stays small: " + out.byteLength());
  }
}
