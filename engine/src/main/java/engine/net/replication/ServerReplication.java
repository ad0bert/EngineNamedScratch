package engine.net.replication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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
import engine.physics.Collider;
import engine.physics.PhysicsSystem;
import engine.physics.RayHit;
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
  private static final int SENT_MESSAGES = 256;
  /** Older baselines are not used: the client may have let go of them. */
  static final int MAX_BASELINE_AGE = 120;

  private static final class Tracked {
    final Entity entity;
    final NetworkIdentity identity;
    boolean announced;
    boolean removed;
    boolean alwaysRelevant;
    float priority = 1f;
    /** Where it was after recent steps, oldest first, for lag compensation (entities with colliders only). */
    final java.util.ArrayDeque<Pose> poses = new java.util.ArrayDeque<>();

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

  /** What a client has of an entity: since when, the newest state it confirmed, how long it has waited. */
  private static final class Known {
    final Tracked tracked;
    final long since;
    QState baseline;
    long baselineTick = -1;
    double waiting;

    Known(Tracked tracked, long since) {
      this.tracked = tracked;
      this.since = since;
    }
  }

  private record SentEntry(int netId, QState state) {
  }

  private record Pose(long tick, Vector3f position, Quaternionf rotation) {
  }

  private static final int POSE_HISTORY = 64;
  private double maxRewind = 0.5;

  /** A snapshot message sent to a client, kept until it is acknowledged or its slot is reused. */
  private static final class SentMessage {
    long id = -1;
    long tick;
    boolean acked;
    final List<SentEntry> entries = new ArrayList<>();
  }

  /** Everything about one client's view of the world. */
  private static final class View {
    final Connection connection;
    final Map<Integer, Known> known = new LinkedHashMap<>();
    final SentMessage[] sent = new SentMessage[SENT_MESSAGES];
    long nextMessageId;

    View(Connection connection) {
      this.connection = connection;
      for (int i = 0; i < SENT_MESSAGES; i++) {
        this.sent[i] = new SentMessage();
      }
    }
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
  private final Map<Connection, View> views = new LinkedHashMap<>();
  private RelevanceFilter relevance = RelevanceFilter.ALL;
  private int relevanceInterval = 10;
  /** Bytes per second of snapshots per client. */
  @Getter private int bandwidthLimit = 48_000;

  /** Takes over {@code server}'s listener; set the game's on this instead. */
  public ServerReplication(Engine engine, NetServer server) {
    super(engine);
    this.server = server;
    server.setListener(new NetListener() {
      @Override
      public void connected(Connection connection) {
        ServerReplication.this.views.put(connection, new View(connection));
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
        ServerReplication.this.views.remove(connection);
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

  /** Which entities each client gets (default {@link RelevanceFilter#ALL}). */
  public void setRelevanceFilter(RelevanceFilter filter) {
    this.relevance = filter;
  }

  /** Steps between relevance checks (default 10). */
  public void setRelevanceInterval(int steps) {
    if (steps < 1) {
      throw new IllegalArgumentException("interval must be at least 1");
    }
    this.relevanceInterval = steps;
  }

  /** Sends {@code entity} to every client whatever the relevance filter says (e.g. a match's score keeper). */
  public void setAlwaysRelevant(Entity entity, boolean value) {
    final var t = this.tracked.get(entity);
    if (t != null) {
      t.alwaysRelevant = value;
    }
  }

  /**
   * How urgent the entity's updates are compared with others (default 1) when a snapshot does not have room for all
   * changes: an entity waits less the higher this is. A client's own entities count four times.
   */
  public void setPriority(Entity entity, float priority) {
    final var t = this.tracked.get(entity);
    if (t != null) {
      t.priority = Math.max(0.01f, priority);
    }
  }

  /**
   * At most this many bytes per second of snapshots to each client (default 48000). When changes do not fit, the
   * most urgent go first and the rest wait for the next snapshot.
   */
  public void setBandwidthLimit(int bytesPerSecond) {
    if (bytesPerSecond < 1000) {
      throw new IllegalArgumentException("at least 1000 bytes per second");
    }
    this.bandwidthLimit = bytesPerSecond;
  }

  /** Whether the client has the entity (it is relevant to it and was spawned there). */
  public boolean isKnownBy(Connection client, Entity entity) {
    final var view = this.views.get(client);
    final var t = this.tracked.get(entity);
    return view != null && t != null && view.known.containsKey(t.identity.netId());
  }

  /** How many entities the client has. */
  public int knownCount(Connection client) {
    final var view = this.views.get(client);
    return view == null ? 0 : view.known.size();
  }

  /** The furthest back {@link #raycastAsSeenBy} goes, in seconds (default 0.5): older views are not trusted. */
  public void setMaxRewind(double seconds) {
    if (!(seconds >= 0) || seconds > POSE_HISTORY * this.engine.config().updateStep()) {
      throw new IllegalArgumentException("rewind must be 0.." + POSE_HISTORY * this.engine.config().updateStep());
    }
    this.maxRewind = seconds;
  }

  /**
   * Lag compensation: the nearest solid hit of a ray as client {@code clientId} saw the world when it gave its
   * current command (its {@link InputCommand#viewTick()}): replicated entities are where they were then, the client's
   * own ones and everything else where they are now. For hitscan weapons, so that aiming at what is on the screen
   * hits. For the host's own player (or without a view) it is a plain raycast.
   */
  public Optional<RayHit> raycastAsSeenBy(int clientId, Vector3f origin, Vector3f direction, float maxDistance) {
    final double view = clientId == NetworkIdentity.HOST ? -1 : commandOf(clientId).viewTick();
    return raycastAt(view < 0 ? this.engine.tick() : view, clientId, origin, direction, maxDistance,
        c -> !c.trigger);
  }

  /**
   * The nearest hit (that {@code accept} lets through) of a ray with replicated entities moved back to host step
   * {@code hostTick} (fractional; at most {@link #setMaxRewind} ago), except those owned by {@code exceptOwner}. They
   * are back where they belong before this returns.
   */
  public Optional<RayHit> raycastAt(double hostTick, int exceptOwner, Vector3f origin, Vector3f direction,
      float maxDistance, Predicate<Collider> accept) {
    final double now = this.engine.tick();
    final double tick = Math.clamp(hostTick, now - this.maxRewind / this.engine.config().updateStep(), now);
    final List<Tracked> moved = new ArrayList<>();
    final List<Vector3f> savedPositions = new ArrayList<>();
    final List<Quaternionf> savedRotations = new ArrayList<>();
    final List<Collider> colliders = new ArrayList<>();
    final java.util.Set<Entity> movedEntities = new java.util.HashSet<>();
    final var position = new Vector3f();
    final var rotation = new Quaternionf();
    try {
      for (Tracked t : this.tracked.values()) {
        if (t.identity.owner() == exceptOwner || t.poses.isEmpty() || tick >= now
            || !poseAt(t, tick, position, rotation)) {
          continue;
        }
        final var transform = t.entity.transform();
        moved.add(t);
        savedPositions.add(new Vector3f(transform.position()));
        savedRotations.add(new Quaternionf(transform.rotation()));
        transform.position().set(position);
        transform.rotation().set(rotation);
        PhysicsSystem.refreshShapes(t.entity);
        movedEntities.add(t.entity);
        for (int i = 0; i < t.entity.componentCount(); i++) {
          if (t.entity.componentAt(i) instanceof Collider c) {
            colliders.add(c);
          }
        }
      }
      final var physics = this.engine.physics();
      final var back = physics.raycast(colliders, origin, direction, maxDistance, accept);
      final var rest = physics.raycast(this.engine.scene(), origin, direction, maxDistance,
          c -> accept.test(c) && !movedEntities.contains(c.entity()));
      if (back.isPresent() && (rest.isEmpty() || back.get().distance() <= rest.get().distance())) {
        return back;
      }
      return rest;
    } finally {
      for (int i = 0; i < moved.size(); i++) {
        final var transform = moved.get(i).entity.transform();
        transform.position().set(savedPositions.get(i));
        transform.rotation().set(savedRotations.get(i));
        PhysicsSystem.refreshShapes(moved.get(i).entity);
      }
    }
  }

  /** Where {@code t} was at {@code tick}, between the recorded steps around it; false if that is unknown. */
  private static boolean poseAt(Tracked t, double tick, Vector3f position, Quaternionf rotation) {
    Pose before = null;
    for (Pose p : t.poses) {
      if (p.tick() <= tick) {
        before = p;
        continue;
      }
      if (before == null) {
        position.set(p.position());
        rotation.set(p.rotation());
      } else {
        final float f = (float) ((tick - before.tick()) / (p.tick() - before.tick()));
        before.position().lerp(p.position(), f, position);
        before.rotation().slerp(p.rotation(), f, rotation);
      }
      return true;
    }
    if (before == null) {
      return false;
    }
    position.set(before.position());
    rotation.set(before.rotation());
    return true;
  }

  private void recordPoses(long tick) {
    for (Tracked t : this.tracked.values()) {
      if (t.entity.findOrNull(Collider.class) == null) {
        continue;
      }
      final var transform = t.entity.transform();
      t.poses.addLast(new Pose(tick, new Vector3f(transform.position()), new Quaternionf(transform.rotation())));
      while (t.poses.size() > POSE_HISTORY) {
        t.poses.removeFirst();
      }
    }
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
    recordPoses(tick);
    sendOutbox(tick);
    if (this.relevance != RelevanceFilter.ALL && tick % this.relevanceInterval == 0) {
      updateRelevance(tick);
    }
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
      } else if (tag == SNAPSHOT_ACK) {
        final var in = new BitReader(data);
        in.readBits(8);
        acksFrom(from, in);
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

  private void acksFrom(Connection from, BitReader in) {
    final var view = this.views.get(from);
    if (view == null || view.nextMessageId == 0) {
      return;
    }
    final long newest = unwrap16((int) in.readBits(16), view.nextMessageId - 1);
    final int bits = in.readInt();
    acknowledge(view, newest);
    for (int i = 0; i < 32; i++) {
      if ((bits & (1 << i)) != 0) {
        acknowledge(view, newest - 1 - i);
      }
    }
  }

  private void acknowledge(View view, long id) {
    if (id < 0 || id >= view.nextMessageId) {
      return;
    }
    final var record = view.sent[(int) (id % SENT_MESSAGES)];
    if (record.id != id || record.acked) {
      return;
    }
    record.acked = true;
    for (SentEntry e : record.entries) {
      final var known = view.known.get(e.netId());
      if (known != null && record.tick >= known.since && record.tick > known.baselineTick) {
        known.baseline = e.state();
        known.baselineTick = record.tick;
      }
    }
  }

  private boolean relevant(View view, Tracked t, List<Entity> owned) {
    if (t.alwaysRelevant || t.identity.owner() == view.connection.id() || this.relevance == RelevanceFilter.ALL) {
      return true;
    }
    return this.relevance.isRelevant(view.connection, t.entity, owned, view.known.containsKey(t.identity.netId()));
  }

  private List<Entity> ownedBy(Connection c) {
    final List<Entity> owned = new ArrayList<>();
    for (Tracked t : this.tracked.values()) {
      if (t.identity.owner() == c.id()) {
        owned.add(t.entity);
      }
    }
    return owned;
  }

  private void spawnTo(View view, Tracked t, long tick) {
    view.connection.send(spawnMessage(t, tick), Delivery.RELIABLE);
    view.known.put(t.identity.netId(), new Known(t, tick));
  }

  private void despawnFrom(View view, Tracked t) {
    if (view.known.remove(t.identity.netId()) != null) {
      view.connection.send(new BitWriter().writeBits(DESPAWN, 8).writeVarInt(t.identity.netId()).toByteArray(),
          Delivery.RELIABLE);
    }
  }

  private void updateRelevance(long tick) {
    for (View view : this.views.values()) {
      if (!view.connection.isConnected()) {
        continue;
      }
      final var owned = ownedBy(view.connection);
      for (Tracked t : this.tracked.values()) {
        if (!t.announced) {
          continue;
        }
        final boolean want = relevant(view, t, owned);
        final boolean has = view.known.containsKey(t.identity.netId());
        if (want && !has) {
          spawnTo(view, t, tick);
        } else if (!want && has) {
          despawnFrom(view, t);
        }
      }
    }
  }

  private void sendOutbox(long tick) {
    for (int i = 0; i < this.outbox.size(); i++) {
      switch (this.outbox.get(i)) {
        case Announce(Tracked t) -> {
          if (!t.removed) {
            for (View view : this.views.values()) {
              if (view.connection.isConnected() && relevant(view, t, ownedBy(view.connection))) {
                spawnTo(view, t, tick);
              }
            }
            t.announced = true;
          }
        }
        case AnnounceAll(Connection to) -> {
          final var view = this.views.get(to);
          if (view != null && to.isConnected()) {
            final var owned = ownedBy(to);
            for (Tracked t : this.tracked.values()) {
              if (t.announced && !view.known.containsKey(t.identity.netId()) && relevant(view, t, owned)) {
                spawnTo(view, t, tick);
              }
            }
          }
        }
        case Despawn(Tracked t) -> {
          for (View view : this.views.values()) {
            despawnFrom(view, t);
          }
        }
        case Message(Connection to, byte[] data, Delivery delivery) -> {
          if (to == null) {
            for (Connection c : this.server.connections()) {
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

  private record Candidate(Known known, QState state, QState base) {
  }

  /**
   * One snapshot per client: the entities it has whose state differs from what it last confirmed, as changes from that
   * (or in full), most urgent first, as many as its bandwidth allows.
   */
  private void sendSnapshots(long tick) {
    if (this.views.isEmpty()) {
      return;
    }
    final Map<Tracked, QState> current = new java.util.HashMap<>();
    for (Tracked t : this.tracked.values()) {
      if (t.announced) {
        current.put(t, QState.of(t.entity));
      }
    }
    final int budget = (int) Math.max(1, this.bandwidthLimit * this.engine.config().updateStep()
        * this.snapshotInterval);
    for (View view : this.views.values()) {
      final var c = view.connection;
      if (!c.isConnected()) {
        continue;
      }
      final List<Candidate> candidates = new ArrayList<>();
      for (Known k : view.known.values()) {
        final var q = current.get(k.tracked);
        if (q == null) {
          continue;
        }
        final boolean fresh = k.baseline != null && tick - k.baselineTick <= MAX_BASELINE_AGE;
        final var base = fresh ? k.baseline : null;
        if (q.sameAs(base)) {
          k.waiting = 0;
          continue; // the client has exactly this
        }
        k.waiting += k.tracked.priority * (k.tracked.identity.owner() == c.id() ? 4 : 1);
        candidates.add(new Candidate(k, q, base));
      }
      candidates.sort((x, y) -> Double.compare(y.known.waiting, x.known.waiting));
      final int maxBytes = c.maxUnreliableSize();
      int used = 0;
      BitWriter message = null;
      SentMessage record = null;
      for (Candidate cand : candidates) {
        final int netId = cand.known.tracked.identity.netId();
        final int age = cand.base == null ? 0 : (int) (tick - cand.known.baselineTick);
        this.scratch.reset().writeVarInt(netId).writeInt(age, 0, MAX_BASELINE_AGE);
        cand.state.write(this.scratch, cand.base);
        final byte[] entry = this.scratch.align().toByteArray();
        if (entry.length + 11 > maxBytes) {
          warnOnce("big " + netId, "State of {} is too large for a snapshot ({} bytes); it is left out",
              cand.known.tracked.entity, entry.length);
          continue;
        }
        if (used > 0 && used + entry.length > budget) {
          break; // the rest waits, and gets more urgent
        }
        if (message != null && message.byteLength() + entry.length > maxBytes) {
          c.send(message.toByteArray(), Delivery.UNRELIABLE);
          message = null;
        }
        if (message == null) {
          final long id = view.nextMessageId++;
          message = new BitWriter(maxBytes).writeBits(SNAPSHOT, 8).writeBits(id & 0xFFFF, 16).writeLong(tick);
          record = view.sent[(int) (id % SENT_MESSAGES)];
          record.id = id;
          record.tick = tick;
          record.acked = false;
          record.entries.clear();
        }
        for (byte b : entry) {
          message.writeBits(b, 8);
        }
        record.entries.add(new SentEntry(netId, cand.state));
        used += entry.length;
        cand.known.waiting = 0;
      }
      if (message != null) {
        c.send(message.toByteArray(), Delivery.UNRELIABLE);
      }
    }
  }
}
