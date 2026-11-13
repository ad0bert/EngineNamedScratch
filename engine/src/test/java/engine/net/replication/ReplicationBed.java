package engine.net.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import engine.core.Engine;
import engine.core.EngineConfig;
import engine.ecs.Component;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Connection;
import engine.net.Delivery;
import engine.net.DisconnectReason;
import engine.net.LoopbackNetwork;
import engine.net.NetClient;
import engine.net.NetConfig;
import engine.net.NetListener;
import engine.net.NetServer;
import engine.physics.BoxCollider;
import engine.physics.CapsuleCollider;
import engine.physics.CharacterController;
import engine.physics.Rigidbody;

/** A host and guests, each a headless engine, on a loopback network with a clock moved one step at a time. */
final class ReplicationBed {
  static final double STEP = 1 / 60.0;
  static final EngineConfig GAME = new EngineConfig("Replication test", "1", 8, 8, STEP);

  /** Replicated game state for the tests. */
  static final class Health extends Component implements NetState {
    int hp = 100;

    @Override
    public void writeState(BitWriter out) {
      out.writeInt(this.hp, 0, 1000);
    }

    @Override
    public void readState(BitReader in) {
      this.hp = in.readInt(0, 1000);
    }
  }

  /** Records a listener's events. */
  static class Events implements NetListener {
    final List<String> log = new ArrayList<>();
    final List<byte[]> messages = new ArrayList<>();
    final List<Connection> connections = new ArrayList<>();

    @Override
    public void connected(Connection c) {
      this.log.add("connected " + c.id());
      this.connections.add(c);
    }

    @Override
    public void disconnected(Connection c, DisconnectReason reason, String message) {
      this.log.add("disconnected " + reason);
    }

    @Override
    public void received(Connection c, byte[] data, Delivery delivery) {
      this.messages.add(data);
    }
  }

  final AtomicLong time = new AtomicLong(1_000_000_000_000L);
  final LoopbackNetwork network = new LoopbackNetwork(this.time::get, 3);
  final NetConfig config = NetConfig.forGame(GAME);
  final Engine host;
  final ServerReplication server;
  final Events hostEvents = new Events();
  final List<Engine> guests = new ArrayList<>();
  final List<ClientReplication> clients = new ArrayList<>();
  final List<Events> guestEvents = new ArrayList<>();

  ReplicationBed() {
    final var netServer = new NetServer(this.network.open(27015), this.config, this.time::get);
    this.host = new Engine(GAME, e -> {
    });
    this.host.startHeadless();
    scenery(this.host);
    this.server = new ServerReplication(this.host, netServer);
    register(this.server);
    this.server.setListener(this.hostEvents);
    this.host.addStepHook(this.server);
  }

  static void register(Replication r) {
    r.registerSpawner("crate", ctx -> {
      final var e = new Entity("crate");
      e.add(new BoxCollider(0.5f, 0.5f, 0.5f));
      e.add(new Rigidbody()).useGravity = false; // stays where it is put
      e.add(new Health());
      return e;
    });
    r.registerSpawner("player", ctx -> {
      final var e = new Entity("player " + ctx.owner());
      e.add(new CharacterController());
      e.add(new Health());
      return e;
    });
    r.registerSpawner("marker", ctx -> new Entity("marker"));
    r.registerSpawner("walker", ctx -> {
      final var e = new Entity("walker " + ctx.owner());
      e.add(new CapsuleCollider(0.3f, 0.6f)); // feet 0.9 below the centre
      e.add(new CharacterController());
      e.add(new CharacterMotor());
      e.add(new Health());
      return e;
    });
  }

  /** The same static floor (top at y = 0) and wall (x = 10) in every engine: scenery, not replicated. */
  static void scenery(Engine engine) {
    final var floor = new Entity("floor");
    floor.transform().position().set(0, -0.5f, 0);
    floor.add(new BoxCollider(100f, 0.5f, 100f));
    engine.scene().add(floor);
    final var wall = new Entity("wall");
    wall.transform().position().set(10.5f, 1f, 0);
    wall.add(new BoxCollider(0.5f, 1f, 100f));
    engine.scene().add(wall);
  }

  /** A guest engine with its replication, connecting now. */
  ClientReplication addGuest() {
    final var engine = new Engine(GAME, e -> {
    });
    engine.startHeadless();
    scenery(engine);
    final var client = new ClientReplication(engine,
        new NetClient(this.network.open(), this.server.server().address(), this.config, this.time::get));
    register(client);
    final var events = new Events();
    client.setListener(events);
    engine.addStepHook(client);
    client.client().connect();
    this.guests.add(engine);
    this.clients.add(client);
    this.guestEvents.add(events);
    return client;
  }

  /** {@code n} steps of every engine, the host first. */
  void step(int n) {
    for (int i = 0; i < n; i++) {
      this.time.addAndGet((long) (STEP * 1e9));
      this.host.runTicks(1);
      for (Engine g : this.guests) {
        g.runTicks(1);
      }
    }
  }

  void connectAll() {
    for (int i = 0; i < 600 && !this.clients.stream().allMatch(c -> c.client().isConnected()); i++) {
      step(1);
    }
    if (!this.clients.stream().allMatch(c -> c.client().isConnected())) {
      throw new AssertionError("guests did not connect");
    }
  }

  /** The copy of {@code hostEntity} on guest {@code i}, or null. */
  Entity copy(int i, Entity hostEntity) {
    return this.clients.get(i).entity(hostEntity.get(NetworkIdentity.class).netId());
  }
}
