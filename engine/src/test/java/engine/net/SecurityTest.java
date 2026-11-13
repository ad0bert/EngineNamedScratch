package engine.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Encrypted connections: nobody on the way can read, change, replay or end them; passwords. */
class SecurityTest {
  private static final byte[] SECRET = "TOP SECRET: the treasure is under the old oak".getBytes(StandardCharsets.UTF_8);

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private static NetTestBed bed(NetConfig config) {
    final var bed = new NetTestBed(config, 1);
    bed.addClient().connect();
    bed.connectAll();
    return bed;
  }

  @Test
  void anEavesdropperSeesNoPlainText() {
    for (boolean encrypted : new boolean[] { true, false }) {
      final var bed = bed(NetConfig.of(NetTestBed.PROTOCOL).withEncryption(encrypted));
      final List<byte[]> wire = new ArrayList<>();
      bed.network.interceptor((from, to, packet) -> {
        wire.add(packet);
        return packet;
      });
      bed.clients.get(0).send(SECRET, Delivery.RELIABLE);
      bed.server.broadcast(SECRET, Delivery.UNRELIABLE);
      bed.step(5);
      assertArrayEquals(SECRET, bed.serverEvents.reliable.getFirst(), "it arrives");
      assertArrayEquals(SECRET, bed.clientEvents.get(0).unreliable.getFirst());
      final boolean visible = wire.stream().anyMatch(p -> contains(p, SECRET));
      assertEquals(!encrypted, visible, encrypted ? "encrypted: not readable" : "unencrypted: readable");
    }
  }

  @Test
  void changedPacketsAreDroppedAndTheMessageStillArrivesIntact() {
    final var bed = bed(NetConfig.of(NetTestBed.PROTOCOL));
    final int[] spoiled = { 0 };
    bed.network.interceptor((from, to, packet) -> {
      if (packet[6] == Packets.DATA && packet.length > Packets.DATA_HEADER + 16 && spoiled[0] < 5) {
        spoiled[0]++;
        final byte[] copy = packet.clone();
        copy[Packets.DATA_HEADER + 2] ^= 0x40; // one bit in the encrypted body
        return copy;
      }
      return packet;
    });
    bed.clients.get(0).send(SECRET, Delivery.RELIABLE);
    bed.step(60);
    assertEquals(5, spoiled[0]);
    assertEquals(1, bed.serverEvents.reliable.size());
    assertArrayEquals(SECRET, bed.serverEvents.reliable.getFirst());
    assertTrue(bed.server.malformedPackets() >= 1, "the changed packet was refused");
  }

  @Test
  void replayedPacketsDeliverNothingTwice() {
    final var bed = bed(NetConfig.of(NetTestBed.PROTOCOL));
    final List<byte[]> captured = new ArrayList<>();
    bed.network.interceptor((from, to, packet) -> {
      if (packet[6] == Packets.DATA && to.equals(bed.server.address())) {
        captured.add(packet);
      }
      return packet;
    });
    bed.clients.get(0).send(SECRET, Delivery.UNRELIABLE);
    bed.step(5);
    bed.network.interceptor(null);
    final var attacker = bed.network.open(4444);
    for (int round = 0; round < 3; round++) {
      for (byte[] p : captured) {
        attacker.send(bed.server.address(), ByteBuffer.wrap(p)); // from another address: ignored
      }
    }
    bed.step(5);
    assertEquals(1, bed.serverEvents.unreliable.size());
  }

  @Test
  void aForgedDisconnectFromTheClientsAddressOnlyWorksUnencrypted() {
    for (boolean encrypted : new boolean[] { true, false }) {
      final var bed = bed(NetConfig.of(NetTestBed.PROTOCOL).withEncryption(encrypted));
      final boolean[] armed = { false };
      // someone on the path swaps one of the client's packets for a goodbye with the right salt (it travels in clear)
      bed.network.interceptor((from, to, packet) -> {
        if (armed[0] && packet[6] == Packets.DATA && to.equals(bed.server.address())) {
          armed[0] = false;
          final var p = ByteBuffer.wrap(packet);
          final long salt = p.getLong(7);
          final short sequence = p.getShort(15);
          final var forged = ByteBuffer.allocate(64);
          Packets.header(forged, NetTestBed.PROTOCOL, Packets.DISCONNECT);
          forged.putLong(salt).putShort(sequence).put((byte) DisconnectReason.LEFT.code).put((byte) 0);
          return java.util.Arrays.copyOf(forged.array(), forged.position());
        }
        return packet;
      });
      armed[0] = true;
      bed.step(5);
      assertFalse(armed[0], "the forgery went out");
      assertEquals(encrypted ? 1 : 0, bed.server.connections().size(),
          encrypted ? "encrypted: a forged goodbye is not authentic" : "unencrypted: anyone on the path can end it");
    }
  }

  @Test
  void passwordsAreChecked() {
    final var right = NetConfig.of(NetTestBed.PROTOCOL).withPassword("correct horse battery staple");
    final var bed = new NetTestBed(right, 1);
    final var good = bed.addClient(right);
    final var wrong = bed.addClient(NetConfig.of(NetTestBed.PROTOCOL).withPassword("hunter2"));
    final var none = bed.addClient(NetConfig.of(NetTestBed.PROTOCOL));
    good.connect();
    wrong.connect();
    none.connect();
    bed.step(60);
    assertTrue(good.isConnected());
    assertEquals(DisconnectReason.WRONG_PASSWORD, wrong.disconnectReason());
    assertEquals(DisconnectReason.WRONG_PASSWORD, none.disconnectReason());
    assertEquals(1, bed.server.connections().size());
    good.send(SECRET, Delivery.RELIABLE);
    bed.step(5);
    assertArrayEquals(SECRET, bed.serverEvents.reliable.getFirst());
  }

  @Test
  void aPasswordForAnOpenServerIsRefusedToo() {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL), 1);
    final var client = bed.addClient(NetConfig.of(NetTestBed.PROTOCOL).withPassword("secret"));
    client.connect();
    bed.step(30);
    assertEquals(DisconnectReason.WRONG_PASSWORD, client.disconnectReason());
    assertEquals("this server has no password", client.disconnectMessage());
  }

  @Test
  void bothSidesMustAgreeOnEncryption() {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL), 1);
    final var plain = bed.addClient(NetConfig.of(NetTestBed.PROTOCOL).withEncryption(false));
    plain.connect();
    bed.step(30);
    assertEquals(DisconnectReason.VERSION_MISMATCH, plain.disconnectReason());
    assertEquals("this server encrypts", plain.disconnectMessage());
  }

  @Test
  void passwordsStayOutOfLogsAndNeedEncryption() {
    final var config = NetConfig.of(1).withPassword("swordfish");
    assertTrue(config.encrypted());
    assertFalse(config.toString().contains("swordfish"));
    assertThrows(IllegalArgumentException.class, () -> new NetConfig(1, 8, 1200, 10, 5, 0.1, 1000, 100, false, "x"));
    assertFalse(config.withEncryption(false).encrypted());
  }

  @Test
  void encryptedConnectionsCarryLargeMessagesThroughLoss() {
    final var bed = bed(NetConfig.of(NetTestBed.PROTOCOL));
    bed.network.conditions(new LoopbackNetwork.Conditions(0.03, 0.03, 0.2, 0.05));
    final byte[] big = new byte[100_000];
    new java.util.Random(3).nextBytes(big);
    bed.server.broadcast(big, Delivery.RELIABLE);
    bed.step(900);
    assertArrayEquals(big, bed.clientEvents.get(0).reliable.getFirst());
  }
}
