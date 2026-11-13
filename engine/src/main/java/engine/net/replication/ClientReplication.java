package engine.net.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 *
 * <p>
 * <b>Prediction.</b> This client's own characters (owned by it, with a {@link CharacterMotor} and a
 * {@link CharacterController}) are not copies: with a {@link #setCommandSource command source}, every step makes an
 * {@link InputCommand} for a host step a little ahead of the host, applies it here at once and sends it to the host,
 * which applies it when it gets to that step. When the host's result for a step differs from what was predicted, the
 * character is put where the host had it and the commands since are replayed ({@link #corrections()}). The client
 * keeps its commands arriving about two steps early by running its engine slightly faster or slower
 * ({@link Engine#setTimeScale}).
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
    byte[] lastState = new byte[0];

    Copy(Entity entity) {
      this.entity = entity;
    }
  }

  private static final double TARGET_LEAD = 2.0;
  private static final int HISTORY = 256;

  private static final class Step {
    final InputCommand command;
    CharacterController.State state;

    Step(InputCommand command) {
      this.command = command;
    }
  }

  private static final class Predicted {
    final Entity entity;
    final CharacterMotor motor;
    final CharacterController controller;
    final TreeMap<Long, Step> history = new TreeMap<>();
    long reconciledTick = -1;
    byte[] lastState = new byte[0];

    Predicted(Entity entity, CharacterMotor motor, CharacterController controller) {
      this.entity = entity;
      this.motor = motor;
      this.controller = controller;
    }
  }

  /** Received states by entity and host step: the baselines snapshots refer to. */
  private static final int HISTORY_TICKS = 180;
  private static final QState MISSING = new QState(0, 0, 0, 0, new byte[0]);

  private final NetClient client;
  private final Map<Integer, Copy> copies = new HashMap<>();
  private final Map<Integer, TreeMap<Long, QState>> histories = new HashMap<>();
  private long latestMessage = -1;
  private int messageBits;
  private boolean ackDue;
  private long lastPrune;
  private final Map<Integer, Predicted> predicted = new HashMap<>();
  private CommandSource commandSource;
  private double predictionOffset;
  private boolean predicting;
  private long lastCommandTick = -1;
  private final ArrayDeque<InputCommand> recentCommands = new ArrayDeque<>();
  private double lead;
  private boolean leadKnown;
  private int corrections;
  private double renderTick = -1;
  private final Vector3f before = new Vector3f();
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

  /**
   * Where this player's input comes from (usually an {@link ActionCommandSource}); null stops predicting. It drives
   * the characters this client owns that have a {@link CharacterMotor}.
   */
  public void setCommandSource(CommandSource source) {
    this.commandSource = source;
  }

  /** How many times the host's result differed from the prediction and the character was corrected. */
  public int corrections() {
    return this.corrections;
  }

  /** How many steps early the commands arrive at the host (smoothed); about 2 when all is well. */
  public double commandLead() {
    return this.lead;
  }

  /** The host step the newest command was made for, or -1. */
  public long predictedTick() {
    return this.lastCommandTick;
  }

  /** The predicted characters of this client. */
  public List<Entity> predictedEntities() {
    return this.predicted.values().stream().map(p -> p.entity).toList();
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
    predict(tick, dt);
  }

  @Override
  public void afterStep(Engine e, long tick, double dt) {
    if (this.lastCommandTick >= 0 && !this.predicted.isEmpty()) {
      for (Predicted p : this.predicted.values()) {
        final var step = p.history.get(this.lastCommandTick);
        if (step != null && step.state == null) {
          step.state = p.controller.save(new CharacterController.State());
        }
      }
      sendCommands();
    }
    sendAck();
    this.client.flush();
  }

  private void predict(long tick, double dt) {
    if (this.commandSource == null || this.predicted.isEmpty() || !this.clockKnown || !this.client.isConnected()) {
      return;
    }
    if (!this.predicting) {
      // the newest snapshot is already half a round trip old, and a command needs the other half to arrive
      final double rtt = this.client.connection().rtt();
      this.predictionOffset = this.offset + rtt / this.engine.config().updateStep() + TARGET_LEAD;
      this.predicting = true;
    }
    final long commandTick = Math.max(this.lastCommandTick + 1, Math.round(tick + this.predictionOffset));
    var command = this.commandSource.sample(commandTick, dt);
    command = new InputCommand(commandTick, command.moveX(), command.moveZ(), command.yaw(), command.pitch(),
        command.buttons(), this.renderTick).quantized();
    this.lastCommandTick = commandTick;
    this.recentCommands.addFirst(command);
    while (this.recentCommands.size() > REDUNDANT_COMMANDS) {
      this.recentCommands.removeLast();
    }
    for (Predicted p : this.predicted.values()) {
      p.motor.fadeCorrection(dt);
      p.motor.apply(command);
      p.history.put(commandTick, new Step(command));
      while (p.history.size() > HISTORY) {
        p.history.pollFirstEntry();
      }
    }
  }

  private void sendCommands() {
    if (this.recentCommands.isEmpty()) {
      return;
    }
    final var out = new BitWriter(64).writeBits(COMMANDS, 8).writeLong(this.recentCommands.getFirst().tick())
        .writeInt(this.recentCommands.size(), 1, REDUNDANT_COMMANDS);
    long previous = -1;
    for (InputCommand c : this.recentCommands) {
      if (previous >= 0) {
        out.writeVarInt((int) Math.min(Integer.MAX_VALUE, previous - c.tick() - 1));
      }
      c.writeBody(out);
      previous = c.tick();
    }
    this.client.send(out.toByteArray(), Delivery.UNRELIABLE);
  }

  /** The host's word on this client's characters: correct them if the prediction was off. */
  private void prediction(BitReader in) {
    final long hostTick = in.readLong();
    in.readLong(); // the step of the newest command the host used (diagnostics)
    final float reportedLead = in.readFloat();
    adjustClock(reportedLead);
    while (in.hasMoreBytes()) {
      final int netId = in.readVarInt();
      final var hostState = readCharacter(in);
      in.align();
      final var p = this.predicted.get(netId);
      if (p == null || hostTick <= p.reconciledTick) {
        continue;
      }
      p.reconciledTick = hostTick;
      reconcile(p, hostTick, hostState);
    }
  }

  private void reconcile(Predicted p, long hostTick, CharacterController.State hostState) {
    final var mine = p.history.get(hostTick);
    p.history.headMap(hostTick).clear();
    if (mine != null && mine.state != null && matches(mine.state, hostState)) {
      return;
    }
    if (mine == null && hostTick > this.lastCommandTick && this.lastCommandTick >= 0) {
      return; // the host is ahead of what was predicted (the clock is about to be fixed): nothing to compare
    }
    this.corrections++;
    this.before.set(p.entity.transform().position());
    p.controller.restore(hostState);
    if (mine != null) {
      p.motor.restoreLast(mine.command);
    }
    final float dt = (float) this.engine.config().updateStep();
    for (var entry : p.history.tailMap(hostTick, false).entrySet()) {
      final var step = entry.getValue();
      if (step.state == null) {
        break; // not stepped yet
      }
      p.motor.apply(step.command);
      this.engine.physics().replayCharacter(this.engine.scene(), p.controller, dt);
      p.controller.save(step.state);
    }
    if (mine != null) {
      mine.state = new CharacterController.State().set(hostState);
    }
    p.motor.addCorrection(this.before.sub(p.entity.transform().position()));
  }

  private static boolean matches(CharacterController.State a, CharacterController.State b) {
    return a.position.distanceSquared(b.position) < 1e-6f && a.velocity.distanceSquared(b.velocity) < 1e-4f
        && a.grounded == b.grounded;
  }

  /** Keeps the commands arriving {@link #TARGET_LEAD} steps early: a jump if far off, else a slightly faster clock. */
  private void adjustClock(float reportedLead) {
    if (!this.predicting) {
      return;
    }
    if (!this.leadKnown) {
      this.lead = reportedLead;
      this.leadKnown = true;
    } else {
      this.lead += (reportedLead - this.lead) * 0.1;
    }
    final double error = TARGET_LEAD - this.lead;
    if (this.lead < 0.5 || Math.abs(error) > 6) {
      this.predictionOffset += error; // late commands are useless: catch up at once
      this.lead = TARGET_LEAD;
      this.engine.setTimeScale(1.0);
    } else {
      this.engine.setTimeScale(1.0 + Math.clamp(error * 0.005, -0.02, 0.02));
    }
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
        case PREDICTION -> prediction(in);
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
    final var older = this.histories.get(netId);
    if (older != null) {
      older.headMap(tick).clear(); // from an earlier life of this entity here
    }
    final var motor = entity.findOrNull(CharacterMotor.class);
    final var controller = entity.findOrNull(CharacterController.class);
    if (owner == this.client.id() && motor != null && controller != null) {
      motor.setPredictedHere(true); // this client moves it itself
      applyState(entity, state);
      this.predicted.put(netId, new Predicted(entity, motor, controller));
      observeHostTick(tick);
      return;
    }
    for (int i = 0; i < entity.componentCount(); i++) {
      switch (entity.componentAt(i)) {
        case Rigidbody body -> body.kinematic = true;
        case CharacterController walker -> walker.setEnabled(false);
        default -> {
        }
      }
    }
    applyState(entity, state);
    final var copy = new Copy(entity);
    copy.samples.add(new Sample(tick, position, rotation, null));
    copy.appliedStateTick = tick;
    copy.lastState = state;
    this.copies.put(netId, copy);
    observeHostTick(tick);
  }

  private void despawned(int netId) {
    final var copy = this.copies.remove(netId);
    final var own = this.predicted.remove(netId);
    if (copy != null || own != null) {
      this.byNetId.remove(netId);
      this.engine.scene().remove(copy != null ? copy.entity : own.entity);
    }
  }

  private void snapshot(BitReader in) {
    final int wireId = (int) in.readBits(16);
    final long tick = in.readLong();
    observeHostTick(tick);
    boolean complete = true;
    while (in.hasMoreBytes()) {
      final int netId = in.readVarInt();
      final int age = in.readInt(0, ServerReplication.MAX_BASELINE_AGE);
      QState base = null;
      if (age > 0) {
        final var history = this.histories.get(netId);
        base = history == null ? null : history.get(tick - age);
        if (base == null) {
          base = MISSING; // read it to get past it, but it cannot be used
        }
      }
      final var q = QState.read(in, base);
      in.align();
      if (base == MISSING) {
        complete = false; // do not confirm this message: the host must not build on it
        continue;
      }
      final var history = this.histories.computeIfAbsent(netId, id -> new TreeMap<>());
      history.put(tick, q);
      history.headMap(tick - HISTORY_TICKS).clear();
      final var copy = this.copies.get(netId);
      if (copy != null) {
        insert(copy, new Sample(tick, q.position(new Vector3f()), q.orientation(new Quaternionf()), q.state()));
      } else if (this.predicted.containsKey(netId)) {
        final var own = this.predicted.get(netId);
        if (!java.util.Arrays.equals(own.lastState, q.state())) {
          own.lastState = q.state();
          applyState(own.entity, q.state()); // own character: game state at once, no movement
        }
      }
    }
    if (complete) {
      noteMessage(wireId);
    }
  }

  /** Remembers a snapshot message for the next acknowledgement (the newest id and the 32 before it). */
  private void noteMessage(int wireId) {
    final long id = this.latestMessage < 0 ? wireId : unwrap16(wireId, this.latestMessage);
    if (this.latestMessage < 0) {
      this.latestMessage = id;
      this.messageBits = 0;
    } else if (id > this.latestMessage) {
      final long shift = id - this.latestMessage;
      this.messageBits = shift > 32 ? 0 : (int) (((long) this.messageBits << shift) | (1L << (shift - 1)));
      this.latestMessage = id;
    } else if (id < this.latestMessage && this.latestMessage - id <= 32) {
      this.messageBits |= 1 << (this.latestMessage - id - 1);
    }
    this.ackDue = true;
  }

  private void sendAck() {
    if (!this.ackDue) {
      return;
    }
    this.ackDue = false;
    this.client.send(new BitWriter(8).writeBits(SNAPSHOT_ACK, 8).writeBits(this.latestMessage & 0xFFFF, 16)
        .writeInt(this.messageBits).toByteArray(), Delivery.UNRELIABLE);
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
    this.renderTick = renderTick;
    if (tick - this.lastPrune > 120) { // let go of what entities that are gone left behind
      this.lastPrune = tick;
      final double hostNow = tick + this.offset;
      this.histories.entrySet().removeIf(e -> !this.byNetId.containsKey(e.getKey())
          && (e.getValue().isEmpty() || e.getValue().lastKey() < hostNow - HISTORY_TICKS));
    }
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
          if (!java.util.Arrays.equals(s.state, copy.lastState)) {
            applyState(copy.entity, s.state);
            copy.lastState = s.state;
          }
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
    for (Predicted p : this.predicted.values()) {
      this.engine.scene().remove(p.entity);
    }
    this.copies.clear();
    this.predicted.clear();
    this.histories.clear();
    this.latestMessage = -1;
    this.messageBits = 0;
    this.ackDue = false;
    this.byNetId.clear();
    this.clockKnown = false;
    this.predicting = false;
    this.leadKnown = false;
    this.lastCommandTick = -1;
    this.recentCommands.clear();
    this.renderTick = -1;
    this.engine.setTimeScale(1.0);
  }
}
