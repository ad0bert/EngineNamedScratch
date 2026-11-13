package engine.net.replication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.core.Engine;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Connection;
import engine.net.Delivery;
import engine.net.DisconnectReason;
import engine.net.NetClient;
import engine.net.NetDataException;
import engine.net.NetListener;
import engine.physics.CharacterController;
import engine.physics.Rigidbody;
import lombok.extern.slf4j.Slf4j;

/**
 * A client's side of replication: makes a copy of every entity the host spawns (with the same {@link Spawner}s),
 * moves the copies as the host's entities move, and removes them when the host despawns them or the connection ends.
 *
 * <pre>{@code
 * var replication = new ClientReplication(engine, new NetClient(UdpTransport.open(), hostAddress, netConfig));
 * replication.registerSpawner("crate", ctx -> new Entity("crate").add(...));
 * replication.client().connect(name);
 * engine.addStepHook(replication); // instead of adding the client itself
 * }</pre>
 *
 * Copies are shown a little in the past ({@link #setInterpolationDelay}, 0.1 s by default): between the two snapshots
 * around that moment, so their motion stays smooth when snapshots arrive unevenly or some are lost. {@link NetState}s
 * are applied when that moment reaches the snapshot they came in. Copies' rigid bodies are made kinematic and their
 * character controllers are turned off: the host moves them.
 */
@Slf4j
public final class ClientReplication extends Replication {
  private static final int MAX_SAMPLES = 64;

  private record Sample(long tick, Vector3f position, Quaternionf rotation, byte[] state) {
  }

  private static final class Copy {
    final Entity entity;
    final List<Sample> samples = new ArrayList<>();
    long appliedStateTick;

    Copy(Entity entity) {
      this.entity = entity;
    }
  }

  private final NetClient client;
  private final Map<Integer, Copy> copies = new HashMap<>();
  private double interpolationDelay = 0.1;
  private double offset;
  private boolean clockKnown;
  private final Quaternionf scratchRotation = new Quaternionf();

  /** Takes over {@code client}'s listener; set the game's on this instead. */
  public ClientReplication(Engine engine, NetClient client) {
    super(engine);
    this.client = client;
    client.setListener(new NetListener() {
      @Override
      public void connected(Connection connection) {
        ClientReplication.this.listener.connected(connection);
      }

      @Override
      public void disconnected(Connection connection, DisconnectReason reason, String message) {
        removeAll();
        ClientReplication.this.listener.disconnected(connection, reason, message);
      }

      @Override
      public void received(Connection connection, byte[] data, Delivery delivery) {
        dispatch(connection, data, delivery);
      }
    });
  }

  public NetClient client() {
    return this.client;
  }

  /**
   * How far in the past copies are shown, in seconds (default 0.1). Longer survives more jitter and loss, shorter
   * shows the host's world sooner. At least the time between two snapshots.
   */
  public void setInterpolationDelay(double seconds) {
    if (!(seconds >= 0)) {
      throw new IllegalArgumentException("delay must be >= 0");
    }
    this.interpolationDelay = seconds;
  }

  public double interpolationDelay() {
    return this.interpolationDelay;
  }

  /**
   * The host's step number as far as this client can tell: the newest snapshot's, carried forward with this engine's
   * steps. Fractional; -1 before the first snapshot.
   */
  public double hostTick() {
    return this.clockKnown ? this.engine.tick() + this.offset : -1;
  }

  /** Calls {@code name} on the host, about {@code target} (or null). Reliable. False if not connected. */
  public boolean rpc(Entity target, String name, BitWriter args) {
    return this.client.send(rpcMessage(name, target, args), Delivery.RELIABLE);
  }

  /** Sends a game message to the host; it arrives at the host's listener. False if not connected. */
  public boolean send(byte[] data, Delivery delivery) {
    return this.client.send(gameMessage(data), delivery);
  }

  @Override
  public void beforeSystems(Engine e, long tick, double dt) {
    this.client.receive();
    interpolate(tick);
  }

  @Override
  public void afterStep(Engine e, long tick, double dt) {
    this.client.flush();
  }

  private void dispatch(Connection from, byte[] data, Delivery delivery) {
    if (data.length == 0) {
      return;
    }
    try {
      final int tag = data[0] & 0xFF;
      if (tag == GAME) {
        this.listener.received(from, gamePayload(data), delivery);
        return;
      }
      final var in = new BitReader(data);
      in.readBits(8);
      switch (tag) {
        case SPAWN -> spawned(in);
        case DESPAWN -> despawned(in.readVarInt());
        case SNAPSHOT -> snapshot(in);
        case RPC -> handleRpc(in, from);
        default -> log.debug("Unexpected message {} from the host", tag);
      }
    } catch (NetDataException e) {
      log.debug("Bad message from the host: {}", e.toString());
    }
  }

  private void spawned(BitReader in) {
    final int netId = in.readVarInt();
    final String type = in.readString(MAX_TYPE);
    final int owner = in.readVarInt() - 1;
    final long tick = in.readLong();
    final var position = in.readVector(new Vector3f());
    final var rotation = in.readRotation(new Quaternionf());
    final byte[] data = in.readBytes(MAX_DATA);
    final byte[] state = in.readBytes(MAX_DATA);
    if (this.copies.containsKey(netId)) {
      return;
    }
    final var entity = build(type, netId, owner, data, position, rotation, false, this.client.id());
    if (entity == null) {
      return;
    }
    for (int i = 0; i < entity.componentCount(); i++) {
      switch (entity.componentAt(i)) {
        case Rigidbody body -> body.kinematic = true;
        case CharacterController controller -> controller.setEnabled(false);
        default -> {
        }
      }
    }
    applyState(entity, state);
    final var copy = new Copy(entity);
    copy.samples.add(new Sample(tick, position, rotation, null));
    copy.appliedStateTick = tick;
    this.copies.put(netId, copy);
    observeHostTick(tick);
  }

  private void despawned(int netId) {
    final var copy = this.copies.remove(netId);
    if (copy != null) {
      this.byNetId.remove(netId);
      this.engine.scene().remove(copy.entity);
    }
  }

  private void snapshot(BitReader in) {
    final long tick = in.readLong();
    observeHostTick(tick);
    while (in.hasMoreBytes()) {
      final int netId = in.readVarInt();
      final var position = in.readVector(new Vector3f());
      final var rotation = in.readRotation(new Quaternionf());
      final byte[] state = in.readBoolean() ? in.readBytes(MAX_DATA) : null;
      in.align();
      final var copy = this.copies.get(netId);
      if (copy != null) {
        insert(copy, new Sample(tick, position, rotation, state));
      }
    }
  }

  private static void insert(Copy copy, Sample sample) {
    final var samples = copy.samples;
    int i = samples.size();
    while (i > 0 && samples.get(i - 1).tick > sample.tick) {
      i--;
    }
    if (i > 0 && samples.get(i - 1).tick == sample.tick) {
      return; // a duplicate
    }
    if (i == 0 && !samples.isEmpty() && sample.tick < copy.appliedStateTick) {
      return; // older than what is already shown
    }
    samples.add(i, sample);
    if (samples.size() > MAX_SAMPLES) {
      samples.removeFirst();
    }
  }

  /**
   * Keeps {@code offset} (host tick minus this engine's tick) at the newest snapshot seen: it jumps up when a snapshot
   * is ahead of the estimate (packets that came quickly) and drifts down slowly otherwise (clocks running apart).
   */
  private void observeHostTick(long hostTick) {
    final double sample = hostTick - this.engine.tick();
    final double far = 1.0 / this.engine.config().updateStep(); // a second of steps
    if (!this.clockKnown || Math.abs(sample - this.offset) > far) {
      this.offset = sample;
      this.clockKnown = true;
    } else if (sample > this.offset) {
      this.offset = sample;
    } else {
      this.offset -= Math.min(this.offset - sample, 0.01);
    }
  }

  private void interpolate(long tick) {
    if (!this.clockKnown) {
      return;
    }
    final double renderTick = tick + this.offset - this.interpolationDelay / this.engine.config().updateStep();
    for (Copy copy : this.copies.values()) {
      final var samples = copy.samples;
      int before = -1;
      for (int i = 0; i < samples.size() && samples.get(i).tick <= renderTick; i++) {
        before = i;
      }
      // NetStates whose moment has come, in order
      for (int i = 0; i <= before; i++) {
        final var s = samples.get(i);
        if (s.state != null && s.tick > copy.appliedStateTick) {
          applyState(copy.entity, s.state);
          copy.appliedStateTick = s.tick;
        }
      }
      final var transform = copy.entity.transform();
      if (before < 0) {
        final var first = samples.getFirst();
        transform.position().set(first.position);
        transform.rotation().set(first.rotation);
        continue;
      }
      final var a = samples.get(before);
      if (before + 1 < samples.size()) {
        final var b = samples.get(before + 1);
        final float t = (float) ((renderTick - a.tick) / (b.tick - a.tick));
        a.position.lerp(b.position, t, transform.position());
        a.rotation.slerp(b.rotation, t, this.scratchRotation);
        transform.rotation().set(this.scratchRotation);
      } else {
        transform.position().set(a.position); // nothing newer yet: wait where the last snapshot was
        transform.rotation().set(a.rotation);
      }
      if (before > 0) {
        samples.subList(0, before).clear();
      }
    }
  }

  private void removeAll() {
    for (Copy copy : this.copies.values()) {
      this.engine.scene().remove(copy.entity);
    }
    this.copies.clear();
    this.byNetId.clear();
    this.clockKnown = false;
  }
}
