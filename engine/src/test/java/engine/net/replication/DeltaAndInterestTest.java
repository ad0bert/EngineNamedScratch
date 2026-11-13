package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Connection;
import engine.net.LoopbackNetwork.Conditions;
import engine.net.Quantize;

/** Phase 5: snapshots as changes from what each client confirmed, relevance, bandwidth. */
class DeltaAndInterestTest {
  private static Connection link(ReplicationBed bed, int guest) {
    final int id = bed.clients.get(guest).client().id();
    return bed.server.server().connection(id);
  }

  private static long bytesOver(ReplicationBed bed, int guest, int steps) {
    final long before = link(bed, guest).bytesSent();
    bed.step(steps);
    return link(bed, guest).bytesSent() - before;
  }

  @Test
  void quantisedValuesRoundTripExactly() {
    final var random = new Random(5);
    for (int i = 0; i < 1000; i++) {
      final float v = (random.nextFloat() - 0.5f) * 20000f;
      final int q = Quantize.position(v);
      assertEquals(q, Quantize.position(Quantize.position(q)), "stable");
      assertEquals(v, Quantize.position(q), 0.5f / Quantize.POSITION_SCALE + Math.ulp(v));
      final int n = random.nextInt();
      assertEquals(n, Quantize.unzigzag(Quantize.zigzag(n)));
      final var rot = new Quaternionf().rotateXYZ(random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat());
      final long packed = Quantize.rotation(rot);
      assertEquals(packed, Quantize.rotation(Quantize.rotation(packed, new Quaternionf())), "stable");
    }
    assertEquals(1, Quantize.zigzag(-1));
    assertEquals(2, Quantize.zigzag(1));
  }

  @Test
  void statesWriteAsSmallChanges() {
    final var a = new QState(1000, 2000, -3000, 12345L, new byte[] { 1, 2 });
    final var b = new QState(1010, 2000, -3005, 12345L, new byte[] { 1, 2 });
    final var full = new BitWriter();
    b.write(full, null);
    final var delta = new BitWriter();
    b.write(delta, a);
    assertEquals(b, QState.read(new BitReader(full.toByteArray()), null));
    assertEquals(b, QState.read(new BitReader(delta.toByteArray()), a));
    assertTrue(delta.byteLength() <= 4, "a small move is a few bytes: " + delta.byteLength());
    assertTrue(full.byteLength() > 18);
    assertTrue(b.sameAs(QState.read(new BitReader(delta.toByteArray()), a)));
  }

  @Test
  void stillEntitiesCostNothingOnceConfirmed() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    for (int i = 0; i < 200; i++) {
      bed.server.spawn("marker", new Vector3f(i, 0, 0));
    }
    bed.step(60);
    final long bytes = bytesOver(bed, 0, 60);
    assertTrue(bytes < 1500, "a still world costs keep-alives only: " + bytes + " bytes in a second");
    assertEquals(200, bed.clients.get(0).entities().size());
  }

  @Test
  void movingEntitiesCostFewBytesEach() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final List<Entity> movers = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      movers.add(bed.server.spawn("marker", new Vector3f(i, 0, 0)));
    }
    bed.step(30);
    long bytes = 0;
    for (int s = 0; s < 60; s++) {
      for (Entity m : movers) {
        m.transform().position().z += 0.05f; // 3 m/s, not turning
      }
      final long before = link(bed, 0).bytesSent();
      bed.step(1);
      bytes += link(bed, 0).bytesSent() - before;
    }
    // full: ~25 bytes an entity, 30 snapshots, 50 entities = 37500; changes from the confirmed state: far less
    assertTrue(bytes < 15_000, bytes + " bytes in a second for 50 moving entities");
  }

  @Test
  void copiesEndUpExactlyRightDespiteLossAndReordering() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.network.conditions(new Conditions(0.04, 0.06, 0.25, 0.1));
    final var random = new Random(2);
    final List<Entity> movers = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      movers.add(bed.server.spawn("crate", new Vector3f(i, 1, 0)));
    }
    for (int s = 0; s < 300; s++) {
      for (Entity m : movers) {
        m.transform().position().add(random.nextFloat() - 0.5f, 0, random.nextFloat() - 0.5f);
        m.transform().rotation().rotateY(random.nextFloat() * 0.2f);
        if (random.nextInt(40) == 0) {
          m.get(ReplicationBed.Health.class).hp = random.nextInt(1000);
        }
      }
      bed.step(1);
    }
    bed.step(240); // still now
    for (Entity m : movers) {
      final var copy = bed.copy(0, m);
      assertTrue(copy.transform().position().distance(m.transform().position()) < 1e-3f, m + " is off");
      assertTrue(Math.abs(copy.transform().rotation().dot(m.transform().rotation())) > 0.99999f, m + " is turned");
      assertEquals(m.get(ReplicationBed.Health.class).hp, copy.get(ReplicationBed.Health.class).hp);
    }
  }

  @Test
  void clientsOnlyGetWhatIsNearThem() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.addGuest();
    bed.connectAll();
    bed.server.setRelevanceFilter(RelevanceFilter.byDistance(20f));
    final int a = bed.clients.get(0).client().id();
    final int b = bed.clients.get(1).client().id();
    final var playerA = bed.server.spawn("marker", new Vector3f(0, 0, 0), a);
    final var playerB = bed.server.spawn("marker", new Vector3f(100, 0, 0), b);
    final var nearA = bed.server.spawn("marker", new Vector3f(5, 0, 0));
    final var nearB = bed.server.spawn("marker", new Vector3f(105, 0, 0));
    final var everywhere = bed.server.spawn("marker", new Vector3f(50, 0, 0));
    bed.server.setAlwaysRelevant(everywhere, true);
    bed.step(30);
    assertNotNull(bed.copy(0, nearA));
    assertNull(bed.copy(0, nearB), "too far from A");
    assertNull(bed.copy(0, playerB));
    assertNotNull(bed.copy(1, nearB));
    assertNotNull(bed.copy(0, everywhere));
    assertNotNull(bed.copy(1, everywhere));
    assertTrue(bed.server.isKnownBy(link(bed, 0), nearA));
    assertFalse(bed.server.isKnownBy(link(bed, 0), nearB));
    // B walks over to A: it gets A's surroundings, and A gets B
    playerB.transform().position().set(10, 0, 0);
    bed.step(30);
    assertNotNull(bed.copy(1, nearA));
    assertNotNull(bed.copy(0, playerB));
    assertTrue(bed.copy(0, playerB).transform().position().distance(10, 0, 0) < 1e-3f);
    // and leaves again: out of sight is out of the scene
    playerB.transform().position().set(200, 0, 0);
    bed.step(30);
    assertNull(bed.copy(0, playerB));
    assertNull(bed.copy(1, nearA));
    assertEquals(3, bed.server.knownCount(link(bed, 0)), "A has itself, nearA and the always relevant one");
  }

  @Test
  void anEntityComingBackIntoViewArrivesAsItIsNow() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.server.setRelevanceFilter(RelevanceFilter.byDistance(20f));
    bed.server.spawn("marker", new Vector3f(0, 0, 0), bed.clients.get(0).client().id());
    final var crate = bed.server.spawn("crate", new Vector3f(5, 1, 0));
    bed.step(30);
    crate.transform().position().set(50, 1, 0);
    bed.step(30);
    assertNull(bed.copy(0, crate));
    crate.get(ReplicationBed.Health.class).hp = 12;
    crate.transform().position().set(8, 1, 0);
    bed.step(30);
    final var copy = bed.copy(0, crate);
    assertNotNull(copy);
    assertEquals(12, copy.get(ReplicationBed.Health.class).hp);
    assertTrue(copy.transform().position().distance(8, 1, 0) < 1e-3f);
  }

  @Test
  void theBandwidthLimitHoldsAndEverythingStillArrives() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.server.setBandwidthLimit(8000);
    final List<Entity> movers = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      movers.add(bed.server.spawn("marker", new Vector3f(i, 0, 0)));
    }
    bed.step(240); // the spawns themselves are reliable messages, outside the snapshot budget
    final var random = new Random(4);
    long bytes = 0;
    for (int s = 0; s < 120; s++) {
      for (Entity m : movers) {
        m.transform().position().y += random.nextFloat() * 0.2f;
      }
      final long before = link(bed, 0).bytesSent();
      bed.step(1);
      bytes += link(bed, 0).bytesSent() - before;
    }
    final double perSecond = bytes / 2.0;
    assertTrue(perSecond < 8000 * 1.25, perSecond + " bytes per second, limit 8000");
    bed.step(600); // still: the waiting changes trickle through
    for (Entity m : movers) {
      assertTrue(bed.copy(0, m).transform().position().distance(m.transform().position()) < 1e-3f);
    }
  }

  @Test
  void urgentEntitiesWaitLess() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.server.setBandwidthLimit(3000);
    final List<Entity> crowd = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      crowd.add(bed.server.spawn("marker", new Vector3f(i, 0, 0)));
    }
    final var important = bed.server.spawn("marker", new Vector3f(-5, 0, 0));
    bed.server.setPriority(important, 50f);
    bed.step(600);
    double crowdError = 0;
    double importantError = 0;
    for (int s = 0; s < 120; s++) {
      for (Entity m : crowd) {
        m.transform().position().y = s * 0.05f;
      }
      important.transform().position().y = s * 0.05f;
      bed.step(1);
      importantError += bed.copy(0, important).transform().position().distance(important.transform().position());
      for (Entity m : crowd) {
        crowdError += bed.copy(0, m).transform().position().distance(m.transform().position()) / crowd.size();
      }
    }
    // the important one is only as far behind as the interpolation delay makes everything (3 m/s * ~0.1 s a step)
    assertTrue(importantError < crowdError / 2, "important " + importantError + " vs crowd " + crowdError);
  }
}
