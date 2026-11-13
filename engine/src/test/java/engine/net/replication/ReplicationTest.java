package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.net.BitWriter;
import engine.net.Delivery;
import engine.net.LoopbackNetwork.Conditions;
import engine.physics.CharacterController;
import engine.physics.Rigidbody;

class ReplicationTest {
  @Test
  void aSpawnAppearsOnTheGuestWithEverything() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var rotation = new Quaternionf().rotateY(1.2f);
    final var crate = bed.server.spawn("crate", new Vector3f(1, 2, 3), rotation, 0,
        "red".getBytes(StandardCharsets.UTF_8));
    crate.get(ReplicationBed.Health.class).hp = 42;
    bed.step(10);
    final var copy = bed.copy(0, crate);
    assertNotNull(copy, "the guest has a copy");
    final var id = copy.get(NetworkIdentity.class);
    assertEquals("crate", id.type());
    assertEquals(0, id.owner());
    assertTrue(id.isCopy());
    assertTrue(id.isMine(), "guest 0 owns it");
    assertFalse(crate.get(NetworkIdentity.class).isMine(), "not the host's");
    assertArrayEquals("red".getBytes(StandardCharsets.UTF_8), id.spawnData());
    assertEquals(42, copy.get(ReplicationBed.Health.class).hp, "the state as it was after the spawning step");
    assertTrue(copy.transform().position().distance(1, 2, 3) < 1e-5f);
    assertTrue(copy.transform().rotation().equals(rotation, 1e-3f));
    assertTrue(copy.get(Rigidbody.class).kinematic, "the host moves it");
    assertFalse(crate.get(Rigidbody.class).kinematic);
    assertTrue(bed.guests.get(0).scene().contains(copy));
  }

  @Test
  void copiesFollowSmoothlyAndALittleLate() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.network.conditions(new Conditions(0.04, 0.03, 0.1, 0.02));
    final var mover = bed.server.spawn("marker", new Vector3f());
    final float speed = 0.05f; // per step: 3 m/s
    final List<Float> seen = new ArrayList<>();
    final List<Float> lag = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      mover.transform().position().x += speed;
      mover.transform().rotation().rotateY(0.01f);
      bed.step(1);
      final var copy = bed.copy(0, mover);
      if (copy != null && i > 60) {
        seen.add(copy.transform().position().x);
        lag.add(mover.transform().position().x - copy.transform().position().x);
      }
    }
    int stalls = 0;
    for (int i = 1; i < seen.size(); i++) {
      final float delta = seen.get(i) - seen.get(i - 1);
      assertTrue(delta >= -1e-4f, "never goes backwards: " + delta + " at " + i);
      assertTrue(delta <= speed * 4, "no big jumps: " + delta + " at " + i);
      if (delta < speed * 0.25f) {
        stalls++;
      }
    }
    assertTrue(stalls < seen.size() / 20, "moves almost every step: " + stalls + " stalls");
    final double meanLag = lag.stream().mapToDouble(Float::doubleValue).average().orElseThrow();
    // 0.1 s interpolation delay plus the network's 0.04 s: about 0.14 s at 3 m/s
    assertTrue(meanLag > 0.25 && meanLag < 0.75, "lag " + meanLag + " m");
  }

  @Test
  void statesAreAppliedAndChangesFollow() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var crate = bed.server.spawn("crate", new Vector3f());
    bed.step(20);
    final var copy = bed.copy(0, crate);
    assertEquals(100, copy.get(ReplicationBed.Health.class).hp);
    crate.get(ReplicationBed.Health.class).hp = 7;
    bed.step(2);
    assertEquals(100, copy.get(ReplicationBed.Health.class).hp, "not before the interpolated moment");
    bed.step(20);
    assertEquals(7, copy.get(ReplicationBed.Health.class).hp);
  }

  @Test
  void despawningAndRemovingFromTheSceneReachGuests() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var a = bed.server.spawn("marker", new Vector3f());
    final var b = bed.server.spawn("marker", new Vector3f());
    final var c = bed.server.spawn("marker", new Vector3f());
    bed.step(10);
    final var copyA = bed.copy(0, a);
    final var copyB = bed.copy(0, b);
    assertEquals(3, bed.clients.get(0).entities().size());
    bed.server.despawn(a);
    bed.host.scene().remove(b);
    bed.step(10);
    assertNull(bed.clients.get(0).entity(a.get(NetworkIdentity.class).netId()));
    assertFalse(bed.guests.get(0).scene().contains(copyA));
    assertFalse(bed.guests.get(0).scene().contains(copyB));
    assertEquals(1, bed.clients.get(0).entities().size());
    assertSame(c, bed.server.entity(c.get(NetworkIdentity.class).netId()));
    assertEquals(1, bed.server.entities().size());
  }

  @Test
  void aSpawnAndDespawnInOneStepNeverReachesGuests() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var flash = bed.server.spawn("marker", new Vector3f());
    bed.server.despawn(flash);
    bed.step(10);
    assertTrue(bed.clients.get(0).entities().isEmpty());
  }

  @Test
  void aLateGuestGetsTheWorldAsItIs() {
    final var bed = new ReplicationBed();
    final var crate = bed.server.spawn("crate", new Vector3f(5, 0, 0));
    bed.step(30);
    crate.transform().position().set(6, 1, 0);
    crate.get(ReplicationBed.Health.class).hp = 3;
    bed.addGuest();
    bed.connectAll();
    bed.step(20);
    final var copy = bed.copy(0, crate);
    assertNotNull(copy);
    assertEquals(3, copy.get(ReplicationBed.Health.class).hp);
    assertTrue(copy.transform().position().distance(6, 1, 0) < 1e-4f);
  }

  @Test
  void aPlayerSpawnedOnConnectReachesEveryoneOnce() {
    final var bed = new ReplicationBed();
    bed.server.setListener(new ReplicationBed.Events() {
      @Override
      public void connected(engine.net.Connection c) {
        bed.server.spawn("player", new Vector3f(c.id(), 0, 0), c.id());
      }
    });
    bed.addGuest();
    bed.addGuest();
    bed.connectAll();
    bed.step(20);
    for (var client : bed.clients) {
      assertEquals(2, client.entities().size(), "both players, each once");
      for (Entity e : client.entities()) {
        final var id = e.get(NetworkIdentity.class);
        assertEquals(id.owner() == client.client().id(), id.isMine());
        assertFalse(e.get(CharacterController.class).isEnabled(), "copies are moved by the host");
      }
    }
  }

  @Test
  void aLeavingGuestTakesItsEntitiesAlong() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.addGuest();
    bed.connectAll();
    final int leaving = bed.clients.get(0).client().id();
    bed.server.spawn("player", new Vector3f(), leaving);
    final var stays = bed.server.spawn("marker", new Vector3f());
    bed.step(10);
    assertEquals(2, bed.clients.get(1).entities().size());
    bed.clients.get(0).client().disconnect();
    bed.step(10);
    assertEquals(List.of(stays), List.copyOf(bed.server.entities()));
    assertEquals(1, bed.clients.get(1).entities().size());
    assertTrue(bed.clients.get(0).entities().isEmpty(), "the leaver dropped its copies");
    assertEquals(0, bed.guests.get(0).scene().entities().size());
  }

  @Test
  void rpcsGoBothWaysInOrderWithSpawns() {
    final var bed = new ReplicationBed();
    final var client = bed.addGuest();
    bed.connectAll();
    final int me = client.client().id();
    final var mine = bed.server.spawn("crate", new Vector3f(), me);
    final var theirs = bed.server.spawn("crate", new Vector3f());
    bed.step(10);
    final List<String> atHost = new ArrayList<>();
    bed.server.onRpc("hit", call -> {
      final int damage = call.args().readInt(0, 100);
      atHost.add(call.entity().name() + " " + damage + " owner=" + call.isFromOwner());
      if (call.isFromOwner()) {
        call.entity().get(ReplicationBed.Health.class).hp -= damage;
      }
    });
    final List<String> atGuest = new ArrayList<>();
    client.onRpc("explode", call -> atGuest.add("explode " + (call.entity() == null ? "-" : call.entity().name())
        + " " + call.args().readString(20)));
    client.rpc(client.entity(mine.get(NetworkIdentity.class).netId()), "hit", new BitWriter().writeInt(10, 0, 100));
    client.rpc(client.entity(theirs.get(NetworkIdentity.class).netId()), "hit", new BitWriter().writeInt(5, 0, 100));
    // a spawn and a call about it in the same step: the call must not overtake the spawn
    final var fresh = bed.server.spawn("marker", new Vector3f());
    bed.server.rpc(fresh, "explode", new BitWriter().writeString("boom", 20));
    bed.server.rpc(null, "explode", new BitWriter().writeString("all", 20));
    bed.step(10);
    assertEquals(List.of("crate 10 owner=true", "crate 5 owner=false"), atHost);
    assertEquals(90, mine.get(ReplicationBed.Health.class).hp);
    assertEquals(100, theirs.get(ReplicationBed.Health.class).hp, "only the owner may hit");
    assertEquals(List.of("explode marker boom", "explode - all"), atGuest);
  }

  @Test
  void unknownCallsAndTypesAreSkipped() {
    final var bed = new ReplicationBed();
    final var client = bed.addGuest();
    bed.server.registerSpawner("hostOnly", ctx -> new Entity("secret"));
    bed.connectAll();
    bed.server.spawn("hostOnly", new Vector3f());
    final var known = bed.server.spawn("marker", new Vector3f());
    bed.server.rpc(null, "nobodyListens", null);
    client.rpc(null, "nobodyListensEither", null);
    bed.step(10);
    assertEquals(1, client.entities().size());
    assertNotNull(bed.copy(0, known));
    assertThrows(IllegalArgumentException.class, () -> bed.server.spawn("noSuchType", new Vector3f()));
  }

  @Test
  void gameMessagesPassThrough() {
    final var bed = new ReplicationBed();
    final var client = bed.addGuest();
    bed.connectAll();
    client.send("hello".getBytes(StandardCharsets.UTF_8), Delivery.RELIABLE);
    bed.server.broadcast(new byte[] { (byte) 0xF1, 2 }, Delivery.RELIABLE); // looks like a tag: still the game's
    bed.step(10);
    assertEquals("hello", new String(bed.hostEvents.messages.get(0), StandardCharsets.UTF_8));
    assertArrayEquals(new byte[] { (byte) 0xF1, 2 }, bed.guestEvents.get(0).messages.get(0));
    assertTrue(bed.clients.get(0).entities().isEmpty());
  }

  @Test
  void whenTheHostGoesAwayCopiesGoToo() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    bed.server.spawn("marker", new Vector3f());
    bed.step(10);
    assertEquals(1, bed.guests.get(0).scene().entities().size());
    bed.server.server().close();
    bed.step(5);
    assertEquals(0, bed.guests.get(0).scene().entities().size());
    assertEquals("disconnected SERVER_CLOSED", bed.guestEvents.get(0).log.getLast());
  }

  @Test
  void stillEntitiesAreSentRarely() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    for (int i = 0; i < 200; i++) {
      bed.server.spawn("marker", new Vector3f(i, 0, 0));
    }
    bed.step(120); // spawned, then still for a while
    final var link = bed.server.server().connections().get(0);
    final long before = link.bytesSent();
    bed.step(60);
    final long perSecond = link.bytesSent() - before;
    // all 200 every snapshot would be ~200 * 20 bytes * 30 per second = 120 KB
    assertTrue(perSecond < 12_000, perSecond + " bytes in a second");
    assertEquals(200, bed.clients.get(0).entities().size());
  }
}
