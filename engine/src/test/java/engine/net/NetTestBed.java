package engine.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A server and clients on a {@link LoopbackNetwork} with a clock the test moves step by step. */
final class NetTestBed {
  static final int PROTOCOL = NetConfig.protocolId("test/1");
  static final long STEP_NANOS = 1_000_000_000L / 60;

  final AtomicLong time = new AtomicLong(1_000_000_000_000L);
  final LoopbackNetwork network;
  final NetServer server;
  final Recorder serverEvents = new Recorder();
  final List<NetClient> clients = new ArrayList<>();
  final List<Recorder> clientEvents = new ArrayList<>();

  /** Records what a listener hears, as text, and the messages. */
  static final class Recorder implements NetListener {
    final List<String> events = new ArrayList<>();
    final List<byte[]> reliable = new ArrayList<>();
    final List<byte[]> unreliable = new ArrayList<>();
    final List<Connection> connected = new ArrayList<>();

    @Override
    public void connected(Connection connection) {
      this.events.add("connected " + connection.id());
      this.connected.add(connection);
    }

    @Override
    public void disconnected(Connection connection, DisconnectReason reason, String message) {
      this.events.add("disconnected " + (connection == null ? "-" : connection.id()) + " " + reason
          + (message.isEmpty() ? "" : " " + message));
    }

    @Override
    public void received(Connection connection, byte[] data, Delivery delivery) {
      (delivery == Delivery.RELIABLE ? this.reliable : this.unreliable).add(data);
    }
  }

  NetTestBed(NetConfig config, long seed) {
    this.network = new LoopbackNetwork(this.time::get, seed);
    this.server = new NetServer(this.network.open(27015), config, this.time::get);
    this.server.setListener(this.serverEvents);
  }

  NetTestBed() {
    this(NetConfig.of(PROTOCOL), 1);
  }

  NetClient addClient(NetConfig config) {
    final var client = new NetClient(this.network.open(), this.server.address(), config, this.time::get);
    final var recorder = new Recorder();
    client.setListener(recorder);
    this.clients.add(client);
    this.clientEvents.add(recorder);
    return client;
  }

  NetClient addClient() {
    return addClient(this.server.config());
  }

  /** Runs {@code n} update steps of 1/60 s: everyone receives, then everyone sends. */
  void step(int n) {
    for (int i = 0; i < n; i++) {
      this.time.addAndGet(STEP_NANOS);
      this.server.receive();
      this.clients.forEach(NetClient::receive);
      this.server.flush();
      this.clients.forEach(NetClient::flush);
    }
  }

  /** Steps until every client is connected (or fails after 10 s). */
  void connectAll() {
    for (int i = 0; i < 600 && !this.clients.stream().allMatch(NetClient::isConnected); i++) {
      step(1);
    }
    if (!this.clients.stream().allMatch(NetClient::isConnected)) {
      throw new AssertionError("clients did not connect: " + this.clientEvents.stream().map(r -> r.events).toList());
    }
  }
}
