package engine.net.replication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import engine.core.Engine;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Connection;
import engine.net.Delivery;
import engine.net.DisconnectReason;
import engine.net.NetDataException;
import engine.net.NetListener;
import engine.net.NetServer;
import engine.physics.CharacterController;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The host's side of replication: entities spawned here appear on every client, follow the host's movement there and
 * disappear with it.
 *
 * <pre>{@code
 * var replication = new ServerReplication(engine, new NetServer(UdpTransport.bind(27015), netConfig));
 * replication.registerSpawner("crate", ctx -> new Entity("crate").add(...));
 * replication.setListener(new NetListener() {
 *   public void connected(Connection c) { replication.spawn("player", spawnPoint, c.id()); }
 * });
 * engine.addStepHook(replication); // instead of adding the server itself
 * Entity crate = replication.spawn("crate", new Vector3f(0, 3, 0));
 * }</pre>
 *
 * Each step it receives (before the systems); after the step it sends, in the order they happened, spawns, despawns,
 * RPCs and game messages (reliable), then every {@link #snapshotInterval()} steps a snapshot (unreliable) of the
 * entities' transforms and {@link NetState}s. Entities that have not changed for half a second are only sent about
 * once a second. An entity removed from the scene is despawned on clients as well. A client that joins later gets
 * every entity as it is then. Entities owned by a client are despawned when it leaves (see
 * {@link #setDespawnOwnedOnDisconnect}).
 *
 * <p>
 * Transforms are sent as they are, relative to the entity's parent: replicate root entities.
 */
@Slf4j
public final class ServerReplication extends Replication {
  /** Steps after a change in which an entity is in every snapshot; after that it is refreshed about once a second. */
  private static final int RECENT_TICKS = 30;

  private static final class Tracked {
    final Entity entity;
    final NetworkIdentity identity;
    final Vector3f position = new Vector3f();
    final Quaternionf rotation = new Quaternionf();
    byte[] state = new byte[0];
    long changedTick = Long.MIN_VALUE;
    boolean announced;
    boolean removed;

    Tracked(Entity entity, NetworkIdentity identity) {
      this.entity = entity;
      this.identity = identity;
    }
  }

  private record Announce(Tracked tracked) {
  }

  private record AnnounceAll(Connection to) {
  }

  private record Despawn(Tracked tracked) {
  }

  private record Message(Connection to, byte[] data, Delivery delivery) {
  }

  /** A client's commands waiting for their step, and the one in use. */
  private static final class Pilot {
    final java.util.TreeMap<Long, InputCommand> pending = new java.util.TreeMap<>();
    InputCommand last = InputCommand.NONE;
    /** The newest step that ran a command the client sent for it (not a repeat), or -1. */
    long ack = -1;
    long newest = -1;
  }

  private final Map<Integer, Pilot> pilots = new java.util.HashMap<>();
  private Entity localPlayer;
  private CommandSource localSource;
  private final CharacterController.State characterScratch = new CharacterController.State();

  @Getter private final NetServer server;
  private final Map<Entity, Tracked> tracked = new LinkedHashMap<>();
  private final List<Object> outbox = new ArrayList<>();
  private int nextNetId = 1;
  /** Steps between snapshots: 2 sends 30 per second at 60 steps per second. */
  @Getter private int snapshotInterval = 2;
  private boolean despawnOwned = true;
  private final BitWriter scratch = new BitWriter(256);

  /** Takes over {@code server}'s listener; set the game's on this instead. */
  public ServerReplication(Engine engine, NetServer server) {
    super(engine);
    this.server = server;
    server.setListener(new NetListener() {
      @Override
      public void connected(Connection connection) {
        ServerReplication.this.outbox.add(new AnnounceAll(connection));
        ServerReplication.this.listener.connected(connection);
      }

      @Override
      public void disconnected(Connection connection, DisconnectReason reason, String message) {
        if (ServerReplication.this.despawnOwned) {
          for (Tracked t : List.copyOf(ServerReplication.this.tracked.values())) {
            if (t.identity.owner() == connection.id()) {
              despawn(t.entity);
            }
          }
        }
        ServerReplication.this.pilots.remove(connection.id());
        ServerReplication.this.listener.disconnected(connection, reason, message);
      }

      @Override
      public void received(Connection connection, byte[] data, Delivery delivery) {
        dispatch(connection, data, delivery);
      }
    });
  }

  /** Sends a snapshot every {@code steps} steps (1 = every step). */
  public void setSnapshotInterval(int steps) {
    if (steps < 1) {
      throw new IllegalArgumentException("interval must be at least 1");
    }
    this.snapshotInterval = steps;
  }

  /** Whether a client's entities are despawned when it disconnects (default true). */
  public void setDespawnOwnedOnDisconnect(boolean value) {
    this.despawnOwned = value;
  }

  /**
   * The host's own player: every step its {@link CharacterMotor} gets the next command from {@code source}. Null
   * stops it. (Clients' players get theirs from the clients.)
   */
  public void setLocalPlayer(Entity player, CommandSource source) {
    this.localPlayer = player;
    this.localSource = source;
  }

  public Entity spawn(String type, Vector3fc position) {
    return spawn(type, position, new Quaternionf(), NetworkIdentity.HOST, new byte[0]);
  }

  public Entity spawn(String type, Vector3fc position, int owner) {
    return spawn(type, position, new Quaternionf(), owner, new byte[0]);
  }

  /**
   * Builds an entity with the spawner registered for {@code type}, adds it to the scene, and spawns it on every client
   * (after this step, with the state it has then). {@code owner} is a client's {@link Connection#id()} or
   * {@link NetworkIdentity#HOST}; {@code data} (up to 64 KiB) reaches every spawner, here and on clients.
   */
  public Entity spawn(String type, Vector3fc position, Quaternionfc rotation, int owner, byte[] data) {
    if (data.length > MAX_DATA) {
      throw new IllegalArgumentException("spawn data of " + data.length + " bytes, more than " + MAX_DATA);
    }
    final int netId = this.nextNetId++;
    final var entity = build(type, netId, owner, data, position, rotation, true, NetworkIdentity.HOST);
    final var t = new Tracked(entity, entity.get(NetworkIdentity.class));
    this.tracked.put(entity, t);
    this.outbox.add(new Announce(t));
    return entity;
  }

  /** Removes the entity from the scene here and on every client. Removing it from the scene does the same. */
  public void despawn(Entity entity) {
    final var t = this.tracked.get(entity);
    if (t != null) {
      forget(t);
      this.engine.scene().remove(entity);
    }
  }

  /** Calls {@code name} on every client, about {@code target} (or null), reliably and in order with spawns. */
  public void rpc(Entity target, String name, BitWriter args) {
    this.outbox.add(new Message(null, rpcMessage(name, target, args), Delivery.RELIABLE));
  }

  /** Calls {@code name} on one client. */
  public void rpc(Connection to, Entity target, String name, BitWriter args) {
    this.outbox.add(new Message(to, rpcMessage(name, target, args), Delivery.RELIABLE));
  }

  /** Sends a game message to one client; it arrives at the client's listener. */
  public void send(Connection to, byte[] data, Delivery delivery) {
    this.outbox.add(new Message(to, gameMessage(data), delivery));
  }

  /** Sends a game message to every client. */
  public void broadcast(byte[] data, Delivery delivery) {
    this.outbox.add(new Message(null, gameMessage(data), delivery));
  }

  @Override
  public void beforeSystems(Engine e, long tick, double dt) {
    this.server.receive();
    if (this.localPlayer != null && this.localSource != null) {
      final var motor = this.localPlayer.findOrNull(CharacterMotor.class);
      if (motor != null && this.engine.scene().contains(this.localPlayer)) {
        motor.apply(this.localSource.sample(tick, dt).quantized());
      }
    }
    for (Pilot p : this.pilots.values()) {
      p.pending.headMap(tick).clear(); // too late
      final var command = p.pending.remove(tick);
      if (command != null) {
        p.last = command;
        p.ack = tick;
      } else {
        p.last = p.last.withTick(tick); // nothing in time: keep doing what it did
      }
    }
    for (Tracked t : this.tracked.values()) {
      final int owner = t.identity.owner();
      if (owner >= 0) {
        final var pilot = this.pilots.get(owner);
        final var motor = pilot == null ? null : t.entity.findOrNull(CharacterMotor.class);
        if (motor != null) {
          motor.apply(pilot.last);
        }
      }
    }
  }

  /** The command in use for client {@code clientId}'s characters (its last one if a step's did not come in time). */
  public InputCommand commandOf(int clientId) {
    final var p = this.pilots.get(clientId);
    return p == null ? InputCommand.NONE : p.last;
  }

  @Override
  public void afterStep(Engine e, long tick, double dt) {
    for (Tracked t : List.copyOf(this.tracked.values())) {
      if (!this.engine.scene().contains(t.entity)) {
        forget(t); // the game removed it from the scene
      }
    }
    sendOutbox(tick);
    if (tick % this.snapshotInterval == 0) {
      sendSnapshots(tick);
      sendPredictions(tick);
    }
    this.server.flush();
  }

  private void forget(Tracked t) {
    this.tracked.remove(t.entity);
    this.byNetId.remove(t.identity.netId());
    t.removed = true;
    this.outbox.add(new Despawn(t));
  }

  private void dispatch(Connection from, byte[] data, Delivery delivery) {
    if (data.length == 0) {
      return;
    }
    try {
      final int tag = data[0] & 0xFF;
      if (tag == GAME) {
        this.listener.received(from, gamePayload(data), delivery);
      } else if (tag == COMMANDS) {
        final var in = new BitReader(data);
        in.readBits(8);
        commandsFrom(from, in);
      } else if (tag == RPC) {
        final var in = new BitReader(data);
        in.readBits(8);
        handleRpc(in, from);
      } else {
        log.debug("Unexpected message {} from client {}", tag, from.id());
      }
    } catch (NetDataException e) {
      log.debug("Bad message from client {}: {}", from.id(), e.toString());
    }
  }

  private void commandsFrom(Connection from, BitReader in) {
    final var pilot = this.pilots.computeIfAbsent(from.id(), id -> new Pilot());
    final long now = this.engine.tick();
    long tick = in.readLong();
    final int count = in.readInt(1, REDUNDANT_COMMANDS);
    for (int i = 0; i < count; i++) {
      if (i > 0) {
        tick -= in.readVarInt() + 1L;
      }
      final var command = InputCommand.readBody(in, tick);
      // only steps still to come, and not absurdly far ahead
      if (tick >= now && tick <= now + 600 && pilot.pending.size() < 256) {
        pilot.pending.putIfAbsent(tick, command);
      }
      pilot.newest = Math.max(pilot.newest, tick);
    }
  }

  /** Tells each client where its characters really are, which of its commands made them so, and how early they come. */
  private void sendPredictions(long tick) {
    for (Connection c : this.server.connections()) {
      final var pilot = this.pilots.get(c.id());
      if (pilot == null) {
        continue;
      }
      BitWriter out = null;
      for (Tracked t : this.tracked.values()) {
        if (t.identity.owner() != c.id() || !t.announced) {
          continue;
        }
        final var controller = t.entity.findOrNull(CharacterController.class);
        if (controller == null || t.entity.findOrNull(CharacterMotor.class) == null) {
          continue;
        }
        if (out == null) {
          out = new BitWriter(128).writeBits(PREDICTION, 8).writeLong(tick).writeLong(pilot.ack)
              .writeFloat(pilot.newest - tick);
        }
        out.writeVarInt(t.identity.netId());
        writeCharacter(out, controller.save(this.characterScratch));
        out.align();
      }
      if (out != null) {
        c.send(out.toByteArray(), Delivery.UNRELIABLE);
      }
    }
  }

  private void sendOutbox(long tick) {
    final var connections = this.server.connections();
    for (int i = 0; i < this.outbox.size(); i++) {
      switch (this.outbox.get(i)) {
        case Announce(Tracked t) -> {
          if (!t.removed) {
            remember(t, tick);
            final byte[] message = spawnMessage(t, tick);
            for (Connection c : connections) {
              c.send(message, Delivery.RELIABLE);
            }
            t.announced = true;
          }
        }
        case AnnounceAll(Connection to) -> {
          if (to.isConnected()) {
            for (Tracked t : this.tracked.values()) {
              if (t.announced) {
                to.send(spawnMessage(t, tick), Delivery.RELIABLE);
              }
            }
          }
        }
        case Despawn(Tracked t) -> {
          if (t.announced) {
            final byte[] message = new BitWriter().writeBits(DESPAWN, 8).writeVarInt(t.identity.netId())
                .toByteArray();
            for (Connection c : connections) {
              c.send(message, Delivery.RELIABLE);
            }
          }
        }
        case Message(Connection to, byte[] data, Delivery delivery) -> {
          if (to == null) {
            for (Connection c : connections) {
              c.send(data, delivery);
            }
          } else {
            to.send(data, delivery);
          }
        }
        default -> throw new IllegalStateException();
      }
    }
    this.outbox.clear();
  }

  private void remember(Tracked t, long tick) {
    t.position.set(t.entity.transform().position());
    t.rotation.set(t.entity.transform().rotation());
    t.state = stateOf(t.entity);
    t.changedTick = tick;
  }

  private byte[] spawnMessage(Tracked t, long tick) {
    final var transform = t.entity.transform();
    return new BitWriter(64).writeBits(SPAWN, 8)
        .writeVarInt(t.identity.netId())
        .writeString(t.identity.type(), MAX_TYPE)
        .writeVarInt(t.identity.owner() + 1)
        .writeLong(tick)
        .writeVector(transform.position())
        .writeRotation(transform.rotation())
        .writeBytes(t.identity.spawnData())
        .writeBytes(stateOf(t.entity))
        .toByteArray();
  }

  private void sendSnapshots(long tick) {
    final var connections = this.server.connections();
    if (connections.isEmpty()) {
      return;
    }
    final int maxBytes = connections.get(0).maxUnreliableSize();
    final double perSecond = 1.0 / (this.engine.config().updateStep() * this.snapshotInterval);
    final long refreshEvery = Math.max(1, Math.round(perSecond));
    final long snapshotNumber = tick / this.snapshotInterval;
    final List<byte[]> messages = new ArrayList<>();
    BitWriter message = null;
    for (Tracked t : this.tracked.values()) {
      if (!t.announced) {
        continue;
      }
      final var transform = t.entity.transform();
      final byte[] state = stateOf(t.entity);
      if (!transform.position().equals(t.position) || !transform.rotation().equals(t.rotation)
          || !Arrays.equals(state, t.state)) {
        t.position.set(transform.position());
        t.rotation.set(transform.rotation());
        t.state = state;
        t.changedTick = tick;
      }
      final boolean recent = tick - t.changedTick <= RECENT_TICKS;
      if (!recent && (snapshotNumber + t.identity.netId()) % refreshEvery != 0) {
        continue;
      }
      this.scratch.reset().writeVarInt(t.identity.netId()).writeVector(transform.position())
          .writeRotation(transform.rotation()).writeBoolean(state.length > 0);
      if (state.length > 0) {
        this.scratch.writeBytes(state);
      }
      final byte[] entry = this.scratch.toByteArray();
      if (entry.length + 9 > maxBytes) {
        log.warn("State of {} is too large for a snapshot ({} bytes); it is left out", t.entity, entry.length);
        continue;
      }
      if (message != null && message.byteLength() + entry.length > maxBytes) {
        messages.add(message.toByteArray());
        message = null;
      }
      if (message == null) {
        message = new BitWriter(maxBytes).writeBits(SNAPSHOT, 8).writeLong(tick);
      }
      for (byte b : entry) {
        message.writeBits(b, 8);
      }
    }
    if (message != null) {
      messages.add(message.toByteArray());
    }
    for (Connection c : connections) {
      for (byte[] m : messages) {
        c.send(m, Delivery.UNRELIABLE);
      }
    }
  }
}
