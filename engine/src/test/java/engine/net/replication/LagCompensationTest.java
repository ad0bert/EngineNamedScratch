package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.core.Engine;
import engine.core.StepHook;
import engine.ecs.Entity;
import engine.net.LoopbackNetwork.Conditions;
import engine.physics.RayHit;

/** Hit tests on the host against the world as a client saw it. */
class LagCompensationTest {
  private record Shot(long commandTick, Vector3f seen) {
  }

  @Test
  void aimingAtWhatIsOnTheScreenHits() {
    final var bed = new ReplicationBed();
    final var client = bed.addGuest();
    bed.connectAll();
    bed.network.conditions(new Conditions(0.05, 0.0, 0.0, 0));
    final int me = client.client().id();
    bed.server.spawn("walker", new Vector3f(0, 0.95f, -20), me);
    final var target = bed.server.spawn("target", new Vector3f(-20, 1, 0));
    bed.step(30);
    client.setCommandSource((tick, dt) -> new StandardCommand(tick, 0, 0, 0, 0, 0, -1));
    final List<Shot> shots = new ArrayList<>();
    final List<String> results = new ArrayList<>();
    bed.host.addStepHook(new StepHook() {
      @Override
      public void beforeSystems(Engine e, long tick, double dt) {
        target.transform().position().x += 0.1f; // 6 m/s across the line of fire
      }

      @Override
      public void afterStep(Engine e, long tick, double dt) {
        for (Shot s : shots) {
          if (bed.server.commandOf(me).tick() == s.commandTick) { // the host is at the step of that command
            final var origin = new Vector3f(s.seen).add(0, 0, -10);
            final var dir = new Vector3f(0, 0, 1);
            final Optional<RayHit> plain = e.raycast(origin, dir, 20f);
            final Optional<RayHit> fair = bed.server.raycastAsSeenBy(me, origin, dir, 20f);
            results.add((plain.isPresent() && plain.get().collider().entity() == target ? "plain hit" : "plain miss")
                + ", " + (fair.isPresent() && fair.get().collider().entity() == target ? "fair hit" : "fair miss"));
          }
        }
      }
    });
    for (int i = 0; i < 300; i++) {
      bed.step(1);
      if (i == 150 || i == 200) { // fire at the copy as it is on the guest's screen now
        shots.add(new Shot(client.predictedTick(), new Vector3f(bed.copy(0, target).transform().position())));
      }
    }
    assertEquals(List.of("plain miss, fair hit", "plain miss, fair hit"), results);
    final var hostTarget = target.transform().position();
    assertTrue(hostTarget.x > 5f, "and the target is back where it is: " + hostTarget);
  }

  @Test
  void rewindingIsLimited() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var target = bed.server.spawn("target", new Vector3f(0, 1, 0));
    bed.step(10);
    for (int i = 0; i < 60; i++) {
      target.transform().position().x += 0.1f;
      bed.step(1);
    }
    final double now = bed.host.tick();
    final var dir = new Vector3f(0, 0, 1);
    // one second ago it was at x = 0; with the default limit of 0.5 s the ray there finds nothing
    final var tooOld = bed.server.raycastAt(now - 60, 99, new Vector3f(0, 1, -5), dir, 20f, c -> true);
    assertFalse(tooOld.isPresent());
    final var halfSecond = bed.server.raycastAt(now - 30, 99, new Vector3f(3, 1, -5), dir, 20f, c -> true);
    assertTrue(halfSecond.isPresent());
    assertSame(target, halfSecond.get().collider().entity());
    final var present = bed.server.raycastAt(now, 99, new Vector3f(6, 1, -5), dir, 20f, c -> true);
    assertSame(target, present.get().collider().entity());
    assertEquals(6f, target.transform().position().x, 1e-3f);
  }

  @Test
  void theHostsOwnShotsAreNotRewound() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var target = bed.server.spawn("target", new Vector3f(0, 1, 0));
    bed.step(10);
    for (int i = 0; i < 20; i++) {
      target.transform().position().x += 0.1f;
      bed.step(1);
    }
    final Entity hit = bed.server.raycastAsSeenBy(NetworkIdentity.HOST, new Vector3f(2, 1, -5), new Vector3f(0, 0, 1),
        20f).orElseThrow().collider().entity();
    assertSame(target, hit);
  }
}
