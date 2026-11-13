package engine.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import engine.net.LoopbackNetwork.Conditions;

/** Connecting, refusing, leaving, timing out and surviving garbage. */
class NetSessionTest {
  private static final byte[] NAME = "Ada".getBytes(StandardCharsets.UTF_8);

  @Test
  void aClientConnectsAndTheServerSeesItsPayload() {
    final var bed = new NetTestBed();
    final var client = bed.addClient();
    assertEquals(NetClient.State.IDLE, client.state());
    client.connect(NAME);
    assertEquals(NetClient.State.CONNECTING, client.state());
    bed.connectAll();
    assertEquals(NetClient.State.CONNECTED, client.state());
    assertEquals(0, client.id());
    assertEquals(List.of("connected 0"), bed.serverEvents.events);
    assertEquals(List.of("connected 0"), bed.clientEvents.get(0).events);
    final var onServer = bed.server.connections().get(0);
    assertArrayEquals(NAME, onServer.connectPayload());
    assertSame(onServer, bed.server.connection(0));
  }

  @Test
  void clientsGetTheLowestFreeNumber() {
    final var bed = new NetTestBed();
    for (int i = 0; i < 3; i++) {
      bed.addClient().connect();
    }
    bed.connectAll();
    assertEquals(List.of(0, 1, 2), bed.clients.stream().map(NetClient::id).sorted().toList());
    final var second = bed.clients.stream().filter(c -> c.id() == 1).findFirst().orElseThrow();
    second.disconnect();
    bed.clients.remove(second);
    bed.step(2);
    final var fourth = bed.addClient();
    fourth.connect();
    bed.connectAll();
    assertEquals(1, fourth.id());
  }

  @Test
  void theHandshakeWorksThroughLossAndDelay() {
    final var bed = new NetTestBed();
    bed.network.conditions(new Conditions(0.08, 0.04, 0.4, 0.1));
    for (int i = 0; i < 4; i++) {
      bed.addClient().connect();
    }
    bed.connectAll();
    bed.step(30);
    assertEquals(4, bed.server.connections().size());
    assertEquals(4, bed.serverEvents.connected.size(), "each client connects once: " + bed.serverEvents.events);
  }

  @Test
  void aFullServerSaysSo() {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL).withMaxClients(1), 1);
    bed.addClient().connect();
    bed.connectAll();
    final var late = bed.addClient();
    late.connect();
    bed.step(30);
    assertEquals(NetClient.State.DISCONNECTED, late.state());
    assertEquals(DisconnectReason.SERVER_FULL, late.disconnectReason());
    assertEquals(List.of("disconnected - SERVER_FULL"), bed.clientEvents.get(1).events);
  }

  @Test
  void theFilterCanRefuseWithAReason() {
    final var bed = new NetTestBed();
    bed.server.setFilter((from, payload) -> new String(payload, StandardCharsets.UTF_8).equals("Ada")
        ? "Name taken"
        : null);
    final var ada = bed.addClient();
    ada.connect(NAME);
    final var bob = bed.addClient();
    bob.connect("Bob".getBytes(StandardCharsets.UTF_8));
    bed.step(30);
    assertEquals(DisconnectReason.DENIED, ada.disconnectReason());
    assertEquals("Name taken", ada.disconnectMessage());
    assertTrue(bob.isConnected());
  }

  @Test
  void anotherVersionIsRefused() {
    final var bed = new NetTestBed();
    final var old = bed.addClient(NetConfig.of(NetConfig.protocolId("test/0")));
    old.connect();
    bed.step(30);
    assertEquals(DisconnectReason.VERSION_MISMATCH, old.disconnectReason());
    assertTrue(bed.server.connections().isEmpty());
  }

  @Test
  void noServerMeansNoAnswer() {
    final var bed = new NetTestBed();
    final var client = new NetClient(bed.network.open(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 1),
        NetConfig.of(NetTestBed.PROTOCOL).withConnectTimeout(1.0), bed.time::get);
    final var events = new NetTestBed.Recorder();
    client.setListener(events);
    bed.clients.add(client);
    client.connect();
    bed.step(55);
    assertEquals(NetClient.State.CONNECTING, client.state());
    bed.step(10);
    assertEquals(DisconnectReason.NO_ANSWER, client.disconnectReason());
    assertEquals(List.of("disconnected - NO_ANSWER"), events.events);
  }

  @Test
  void leavingKickingAndClosingAreReportedOnBothSides() {
    final var bed = new NetTestBed();
    final var leaver = bed.addClient();
    final var kicked = bed.addClient();
    final var stayer = bed.addClient();
    leaver.connect();
    kicked.connect();
    stayer.connect();
    bed.connectAll();
    leaver.disconnect();
    bed.server.connection(kicked.id()).disconnect("Be nice");
    bed.step(5);
    assertEquals(DisconnectReason.LEFT, leaver.disconnectReason());
    assertEquals(DisconnectReason.KICKED, kicked.disconnectReason());
    assertEquals("Be nice", kicked.disconnectMessage());
    assertTrue(bed.serverEvents.events.contains("disconnected " + leaver.connection().id() + " LEFT"));
    assertTrue(bed.serverEvents.events.contains("disconnected " + kicked.connection().id() + " KICKED Be nice"));
    assertEquals(1, bed.server.connections().size());
    bed.server.close();
    bed.step(5);
    assertEquals(DisconnectReason.SERVER_CLOSED, stayer.disconnectReason());
  }

  @Test
  void silenceEndsTheConnectionOnBothSides() {
    final var bed = new NetTestBed(NetConfig.of(NetTestBed.PROTOCOL).withTimeout(2.0), 1);
    final var client = bed.addClient();
    client.connect();
    bed.connectAll();
    bed.network.conditions(new Conditions(0, 0, 1.0, 0)); // the cable is cut
    bed.step(110);
    assertTrue(client.isConnected(), "not yet: under two seconds");
    bed.step(15);
    assertEquals(DisconnectReason.TIMED_OUT, client.disconnectReason());
    assertEquals(List.of("connected 0", "disconnected 0 TIMED_OUT"), bed.serverEvents.events);
  }

  @Test
  void aClientCanConnectAgainAfterLeaving() {
    final var bed = new NetTestBed();
    final var client = bed.addClient();
    client.connect();
    bed.connectAll();
    client.disconnect();
    bed.step(3);
    client.connect();
    bed.connectAll();
    bed.step(3);
    assertEquals(1, bed.server.connections().size());
    assertEquals(List.of("connected 0", "disconnected 0 LEFT", "connected 0"), bed.serverEvents.events);
  }

  @Test
  void garbageAndForgeriesAreIgnored() {
    final var bed = new NetTestBed();
    final var client = bed.addClient();
    client.connect();
    bed.connectAll();
    final var attacker = bed.network.open(666);
    final var random = new Random(3);
    final var server = bed.server.address();
    for (int i = 0; i < 2000; i++) {
      final byte[] junk = new byte[random.nextInt(1300)];
      random.nextBytes(junk);
      if (i % 2 == 0 && junk.length >= 7) { // a valid header, random body
        ByteBuffer.wrap(junk).putShort((short) Packets.MAGIC).putInt(NetTestBed.PROTOCOL)
            .put((byte) (1 + random.nextInt(7)));
      }
      attacker.send(server, ByteBuffer.wrap(junk));
    }
    // a challenge response with a made-up cookie, padded like a real one
    final var forged = ByteBuffer.allocate(Packets.REQUEST_SIZE);
    Packets.header(forged, NetTestBed.PROTOCOL, Packets.CHALLENGE_RESPONSE);
    forged.putLong(1234).putLong(5678).put((byte) 0);
    forged.position(Packets.REQUEST_SIZE).flip();
    attacker.send(server, forged);
    bed.step(5);
    assertEquals(1, bed.server.connections().size(), "no connection for the attacker");
    assertTrue(bed.server.malformedPackets() > 0);
    assertTrue(client.isConnected(), "the real client is unharmed");
  }

  @Test
  void unpaddedRequestsGetNoAnswer() {
    final var bed = new NetTestBed();
    final var probe = bed.network.open(667);
    final var request = ByteBuffer.allocate(64);
    Packets.header(request, NetTestBed.PROTOCOL, Packets.CONNECT_REQUEST);
    request.putLong(1).flip();
    probe.send(bed.server.address(), request);
    bed.step(2);
    assertNull(probe.receive(ByteBuffer.allocate(2000)), "a short request could amplify a forged sender");
  }

  @Test
  void connectChecksItsArguments() {
    final var bed = new NetTestBed();
    final var client = bed.addClient();
    assertThrows(IllegalArgumentException.class, () -> client.connect(new byte[NetConfig.MAX_CONNECT_PAYLOAD + 1]));
    client.connect();
    assertThrows(IllegalStateException.class, client::connect);
    assertFalse(client.send(new byte[1], Delivery.RELIABLE), "not connected yet");
  }
}
