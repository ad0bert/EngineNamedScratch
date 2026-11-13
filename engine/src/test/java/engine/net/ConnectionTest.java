package engine.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

import engine.net.LoopbackNetwork.Conditions;

/** Messages over a connection: order, reliability, fragments, statistics. */
class ConnectionTest {
  private static byte[] numbered(int i) {
    return ByteBuffer.allocate(4).putInt(i).array();
  }

  private static int number(byte[] data) {
    return ByteBuffer.wrap(data).getInt();
  }

  private static NetTestBed connected(Conditions conditions, long seed) {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL), seed);
    bed.addClient().connect();
    bed.connectAll();
    bed.network.conditions(conditions);
    return bed;
  }

  @Test
  void reliableMessagesArriveOnceAndInOrderOverABadNetwork() {
    final var bed = connected(new Conditions(0.05, 0.05, 0.25, 0.1), 11);
    final var client = bed.clients.get(0);
    final var toClient = bed.server.connections().get(0);
    for (int i = 0; i < 600; i++) {
      client.send(numbered(i), Delivery.RELIABLE);
      toClient.send(numbered(i), Delivery.RELIABLE);
      if (i % 3 == 0) {
        bed.step(1);
      }
    }
    bed.step(600);
    final var atServer = bed.serverEvents.reliable;
    final var atClient = bed.clientEvents.get(0).reliable;
    assertEquals(600, atServer.size());
    assertEquals(600, atClient.size());
    for (int i = 0; i < 600; i++) {
      assertEquals(i, number(atServer.get(i)));
      assertEquals(i, number(atClient.get(i)));
    }
    assertEquals(0, client.connection().pendingReliable());
    assertTrue(bed.network.dropped() > 100, "the network really lost packets");
  }

  @Test
  void largeMessagesAreSplitAndJoined() {
    final var bed = connected(new Conditions(0.03, 0.03, 0.2, 0.05), 5);
    final var random = new Random(9);
    final byte[] level = new byte[200_000];
    random.nextBytes(level);
    final byte[] small = { 7 };
    bed.server.broadcast(level, Delivery.RELIABLE);
    bed.server.broadcast(small, Delivery.RELIABLE);
    bed.server.broadcast(new byte[0], Delivery.RELIABLE);
    bed.step(900);
    final var got = bed.clientEvents.get(0).reliable;
    assertEquals(3, got.size());
    assertArrayEquals(level, got.get(0));
    assertArrayEquals(small, got.get(1));
    assertEquals(0, got.get(2).length);
  }

  @Test
  void unreliableMessagesArriveAtMostOnce() {
    final var bed = connected(new Conditions(0.02, 0.04, 0.2, 0.3), 2);
    final var client = bed.clients.get(0);
    for (int i = 0; i < 300; i++) {
      client.send(numbered(i), Delivery.UNRELIABLE);
      bed.step(1);
    }
    bed.step(30);
    final var seen = new HashSet<Integer>();
    for (byte[] m : bed.serverEvents.unreliable) {
      assertTrue(seen.add(number(m)), "duplicate " + number(m));
    }
    assertTrue(seen.size() > 200 && seen.size() < 290, "about 80 % arrive: " + seen.size());
  }

  @Test
  void manySmallMessagesShareAPacket() {
    final var bed = connected(Conditions.PERFECT, 1);
    final var client = bed.clients.get(0);
    final long before = client.connection().packetsSent();
    for (int i = 0; i < 100; i++) {
      client.send(numbered(i), Delivery.UNRELIABLE);
      client.send(numbered(i), Delivery.RELIABLE);
    }
    bed.step(1);
    assertEquals(2, client.connection().packetsSent() - before, "1500 bytes of messages need two 1200-byte packets");
    bed.step(1); // they arrive in the next step
    assertEquals(100, bed.serverEvents.unreliable.size());
    assertEquals(100, bed.serverEvents.reliable.size());
  }

  @Test
  void roundTripAndLossAreMeasured() {
    final var bed = connected(new Conditions(0.05, 0, 0.1, 0), 4);
    bed.step(1200);
    final var c = bed.clients.get(0).connection();
    // 100 ms on the wire, plus up to a step of waiting on each side before a packet is read
    assertEquals(0.1 + 1 / 60.0, c.rtt(), 0.025, "rtt");
    assertEquals(0.1, c.packetLoss(), 0.05, "loss");
    // idle: acknowledgements and keep-alives only, well below a packet per step
    assertTrue(c.bytesSentPerSecond() > 100 && c.bytesSentPerSecond() < 60 * 23, "bytes per second: "
        + c.bytesSentPerSecond());
  }

  @Test
  void sequenceNumbersWrapAround() {
    final var bed = connected(new Conditions(0.01, 0.02, 0.05, 0.02), 8);
    final var client = bed.clients.get(0);
    for (int i = 0; i < 70_000; i++) {
      client.send(numbered(i), Delivery.RELIABLE);
      bed.step(1);
    }
    bed.step(120);
    assertTrue(client.connection().packetsSent() > 70_000);
    final var got = bed.serverEvents.reliable;
    assertEquals(70_000, got.size());
    for (int i = 0; i < got.size(); i++) {
      if (number(got.get(i)) != i) {
        throw new AssertionError("message " + i + " is " + number(got.get(i)));
      }
    }
  }

  @Test
  void tooMuchUnacknowledgedDataEndsTheConnection() {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL).withMaxPendingReliable(50), 1);
    final var client = bed.addClient();
    client.connect();
    bed.connectAll();
    bed.network.conditions(new Conditions(0, 0, 1.0, 0));
    for (int i = 0; i < 50; i++) {
      assertTrue(client.send(numbered(i), Delivery.RELIABLE));
    }
    assertFalse(client.send(numbered(50), Delivery.RELIABLE));
    bed.step(1);
    assertEquals(DisconnectReason.OVERFLOW, client.disconnectReason());
  }

  @Test
  void sizesAreChecked() {
    final var bed = connected(Conditions.PERFECT, 1);
    final var c = bed.clients.get(0).connection();
    assertThrows(IllegalArgumentException.class, () -> c.send(new byte[c.maxUnreliableSize() + 1],
        Delivery.UNRELIABLE));
    assertThrows(IllegalArgumentException.class, () -> c.send(new byte[bed.server.config().maxMessageSize() + 1],
        Delivery.RELIABLE));
    assertTrue(c.send(new byte[c.maxUnreliableSize()], Delivery.UNRELIABLE));
    bed.step(2);
    assertEquals(1, bed.serverEvents.unreliable.size());
  }

  @Test
  void aListenerCanDisconnectWhileMessagesAreDelivered() {
    final var bed = connected(Conditions.PERFECT, 1);
    bed.server.setListener(new NetListener() {
      @Override
      public void received(Connection connection, byte[] data, Delivery delivery) {
        connection.disconnect("one is enough");
      }
    });
    final var client = bed.clients.get(0);
    client.send(numbered(1), Delivery.RELIABLE);
    client.send(numbered(2), Delivery.RELIABLE);
    bed.step(3);
    assertEquals(DisconnectReason.KICKED, client.disconnectReason());
    assertEquals("one is enough", client.disconnectMessage());
  }
}
