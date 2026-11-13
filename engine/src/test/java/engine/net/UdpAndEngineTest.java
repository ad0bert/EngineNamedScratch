package engine.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import engine.core.Engine;
import engine.core.EngineConfig;

/** Real sockets on the loopback interface, and a server and a client inside two headless engines. */
class UdpAndEngineTest {
  private static final InetSocketAddress ANY_LOCAL = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

  @Test
  void udpCarriesTheHandshakeAndMessages() throws InterruptedException {
    final var config = NetConfig.of(NetTestBed.PROTOCOL);
    try (var server = new NetServer(UdpTransport.bind(ANY_LOCAL), config);
        var client = new NetClient(UdpTransport.bind(ANY_LOCAL), server.address(), config)) {
      final var got = new ArrayList<String>();
      server.setListener(new NetListener() {
        @Override
        public void received(Connection c, byte[] data, Delivery d) {
          got.add(new String(data, StandardCharsets.UTF_8));
          c.send("pong".getBytes(StandardCharsets.UTF_8), Delivery.RELIABLE);
        }
      });
      final var answers = new ArrayList<String>();
      client.setListener(new NetListener() {
        @Override
        public void received(Connection c, byte[] data, Delivery d) {
          answers.add(new String(data, StandardCharsets.UTF_8));
        }
      });
      client.connect();
      boolean sent = false;
      for (int i = 0; i < 500 && answers.isEmpty(); i++) {
        server.update();
        client.update();
        if (client.isConnected() && !sent) {
          sent = client.send("ping".getBytes(StandardCharsets.UTF_8), Delivery.RELIABLE);
        }
        Thread.sleep(2);
      }
      assertEquals(List.of("ping"), got);
      assertEquals(List.of("pong"), answers);
    }
  }

  @Test
  void serverAndClientRunAsStepHooksOfHeadlessEngines() {
    final var time = new AtomicLong(1_000_000_000L);
    final var network = new LoopbackNetwork(time::get, 1);
    final var gameConfig = new EngineConfig("Net test", "1", 8, 8, 1 / 60.0);
    final var config = NetConfig.forGame(gameConfig);
    final var server = new NetServer(network.open(27015), config, time::get);
    final var client = new NetClient(network.open(), server.address(), config, time::get);
    final var ticksSeen = new ArrayList<Long>();
    // the host's game sends its tick each step; the client's game records what it got
    final var host = new Engine(gameConfig, e -> e.addStepHook(server));
    final var guest = new Engine(gameConfig, e -> e.addStepHook(client));
    host.addStepHook(new engine.core.StepHook() {
      @Override
      public void afterStep(Engine e, long tick, double dt) {
        server.broadcast(new BitWriter().writeLong(tick).toByteArray(), Delivery.UNRELIABLE);
      }
    });
    client.setListener(new NetListener() {
      @Override
      public void received(Connection c, byte[] data, Delivery d) {
        ticksSeen.add(new BitReader(data).readLong());
      }
    });
    host.startHeadless();
    guest.startHeadless();
    client.connect();
    for (int i = 0; i < 120; i++) {
      time.addAndGet(1_000_000_000L / 60);
      host.runTicks(1);
      guest.runTicks(1);
    }
    assertTrue(client.isConnected());
    assertTrue(ticksSeen.size() > 100, "the client hears the host every step: " + ticksSeen.size());
    assertEquals(host.tick(), ticksSeen.getLast());
    guest.stop();
    host.stop();
    server.close();
    client.close();
  }
}
