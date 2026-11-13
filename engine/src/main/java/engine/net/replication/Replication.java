package engine.net.replication;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import engine.core.Engine;
import engine.core.StepHook;
import engine.ecs.Component;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Connection;
import engine.net.NetConfig;
import engine.net.NetDataException;
import engine.net.NetListener;
import engine.physics.CharacterController;
import lombok.extern.slf4j.Slf4j;

/**
 * What the host's {@link ServerReplication} and a client's {@link ClientReplication} share: spawn types, RPC handlers,
 * the replicated entities by network number, and the game's listener.
 *
 * <p>
 * Replication owns the messages of its server or client: everything sent over it starts with a tag byte. Games send
 * their own messages through {@code send}/{@code broadcast} here (or use RPCs), not through the server or client
 * directly, and get them in their {@link NetListener}'s {@code received}, without the tag.
 */
@Slf4j
public abstract sealed class Replication implements StepHook permits ServerReplication, ClientReplication {
  static final int GAME = 0xF0;
  static final int SPAWN = 0xF1;
  static final int DESPAWN = 0xF2;
  static final int SNAPSHOT = 0xF3;
  static final int RPC = 0xF4;
  static final int COMMANDS = 0xF5;
  static final int PREDICTION = 0xF6;
  static final int SNAPSHOT_ACK = 0xF7;
  /** Commands sent again in every message, so that a lost packet costs nothing. */
  static final int REDUNDANT_COMMANDS = 8;
  static final int MAX_TYPE = 200;
  static final int MAX_DATA = 64 * 1024;

  final Engine engine;
  private final Map<String, Spawner> spawners = new HashMap<>();
  private final Map<Integer, String> rpcNames = new HashMap<>();
  private final Map<Integer, RpcHandler> rpcHandlers = new HashMap<>();
  final Map<Integer, Entity> byNetId = new LinkedHashMap<>();
  private final Collection<Entity> entitiesView = Collections.unmodifiableCollection(this.byNetId.values());
  private final Set<String> warned = new HashSet<>();
  NetListener listener = new NetListener() {
  };
  Wraparound wraparound;

  Replication(Engine engine) {
    this.engine = engine;
  }

  /** How entities of {@code type} are built; register the same types on the host and on clients. */
  public void registerSpawner(String type, Spawner spawner) {
    if (type.isEmpty() || type.length() > MAX_TYPE) {
      throw new IllegalArgumentException("bad type name: " + type);
    }
    this.spawners.put(type, spawner);
  }

  /**
   * Handles calls named {@code name} that the other side sends: on the host, calls from clients; on a client, calls
   * from the host. Names are matched by a 32-bit hash; two names with the same hash are refused here.
   */
  public void onRpc(String name, RpcHandler handler) {
    final int id = rpcId(name);
    final String existing = this.rpcNames.get(id);
    if (existing != null && !existing.equals(name)) {
      throw new IllegalArgumentException("RPC names " + existing + " and " + name + " collide; rename one");
    }
    this.rpcNames.put(id, name);
    this.rpcHandlers.put(id, handler);
  }

  /** Receives connections, disconnections and the game's own messages (see {@code send}). */
  public void setListener(NetListener listener) {
    this.listener = listener;
  }

  /**
   * The box the game's world wraps around in, or null (the default) for open space: copies crossing an edge then
   * blend the short way, across it, and so do corrections of predicted players and lag compensation. Set the same on
   * the host and on clients.
   */
  public void setWraparound(Wraparound wraparound) {
    this.wraparound = wraparound;
  }

  public Wraparound wraparound() {
    return this.wraparound;
  }

  /** The point {@code t} of the way from {@code a} to {@code b}: straight, or the short way in a wraparound world. */
  Vector3f blend(Vector3f a, Vector3f b, float t, Vector3f dest) {
    return this.wraparound == null ? a.lerp(b, t, dest) : this.wraparound.lerp(a, b, t, dest);
  }

  /** The replicated entity with this network number, or null. */
  public Entity entity(int netId) {
    return this.byNetId.get(netId);
  }

  /** All replicated entities, in the order they were spawned. A live view: do not change the scene while iterating. */
  public Collection<Entity> entities() {
    return this.entitiesView;
  }

  // ---- shared plumbing ----

  /** Expands a 16-bit number on the wire to the full counter closest to {@code reference}. */
  static long unwrap16(int wire, long reference) {
    return reference + (short) (wire - (int) reference);
  }

  static int rpcId(String name) {
    return NetConfig.protocolId("rpc/" + name);
  }

  static int netIdOf(Entity target) {
    if (target == null) {
      return 0;
    }
    final var id = target.findOrNull(NetworkIdentity.class);
    if (id == null) {
      throw new IllegalArgumentException(target + " is not replicated");
    }
    return id.netId();
  }

  static byte[] rpcMessage(String name, Entity target, BitWriter args) {
    return new BitWriter().writeBits(RPC, 8).writeInt(rpcId(name)).writeVarInt(netIdOf(target))
        .writeBytes(args == null ? new byte[0] : args.toByteArray()).toByteArray();
  }

  static byte[] gameMessage(byte[] data) {
    final byte[] out = new byte[data.length + 1];
    out[0] = (byte) GAME;
    System.arraycopy(data, 0, out, 1, data.length);
    return out;
  }

  static byte[] gamePayload(byte[] message) {
    return Arrays.copyOfRange(message, 1, message.length);
  }

  void handleRpc(BitReader in, Connection from) {
    final int id = in.readInt();
    final int netId = in.readVarInt();
    final byte[] args = in.readBytes(MAX_DATA);
    final var handler = this.rpcHandlers.get(id);
    if (handler == null) {
      warnOnce("rpc " + id, "No handler for RPC {} from {}", Integer.toHexString(id), from);
      return;
    }
    Entity target = null;
    if (netId != 0) {
      target = this.byNetId.get(netId);
      if (target == null) {
        return; // already gone, or never known here
      }
    }
    try {
      handler.handle(new RpcHandler.Call(this.rpcNames.get(id), target, new BitReader(args), from));
    } catch (NetDataException e) {
      log.debug("RPC {} from {} had bad arguments: {}", this.rpcNames.get(id), from, e.toString());
    } catch (RuntimeException e) {
      log.error("RPC handler {} failed", this.rpcNames.get(id), e);
    }
  }

  /** Builds, places, identifies and adds an entity; null (logged) if the type is unknown. */
  Entity build(String type, int netId, int owner, byte[] data, Vector3fc position, Quaternionfc rotation,
      boolean onHost, int localClient) {
    final var spawner = this.spawners.get(type);
    if (spawner == null) {
      if (onHost) {
        throw new IllegalArgumentException("no spawner registered for " + type);
      }
      warnOnce("type " + type, "No spawner for type {}; its entities are left out", type);
      return null;
    }
    final var entity = spawner.spawn(new Spawner.Context(type, netId, owner, data.clone(), new Vector3f(position),
        new Quaternionf(rotation), onHost, this.engine));
    var identity = entity.findOrNull(NetworkIdentity.class);
    if (identity == null) {
      identity = entity.add(new NetworkIdentity());
    }
    identity.init(netId, type, owner, data.clone(), !onHost, localClient);
    entity.transform().position().set(position);
    entity.transform().rotation().set(rotation);
    entity.transform().snapshot();
    this.engine.scene().add(entity);
    this.byNetId.put(netId, entity);
    return entity;
  }

  static void writeCharacter(BitWriter out, CharacterController.State s) {
    out.writeVector(s.position).writeVector(s.velocity).writeFloat(s.walk.x).writeFloat(s.walk.z)
        .writeBoolean(s.grounded).writeFloat(s.airTime);
  }

  static CharacterController.State readCharacter(BitReader in) {
    final var s = new CharacterController.State();
    in.readVector(s.position);
    in.readVector(s.velocity);
    s.walk.set(in.readFloat(), 0f, in.readFloat());
    s.grounded = in.readBoolean();
    s.airTime = in.readFloat();
    return s;
  }

  static byte[] stateOf(Entity entity) {
    BitWriter out = null;
    for (int i = 0; i < entity.componentCount(); i++) {
      if (entity.componentAt(i) instanceof NetState s) {
        if (out == null) {
          out = new BitWriter();
        }
        s.writeState(out);
      }
    }
    return out == null ? new byte[0] : out.toByteArray();
  }

  static void applyState(Entity entity, byte[] state) {
    if (state.length == 0) {
      return;
    }
    final var in = new BitReader(state);
    try {
      for (int i = 0; i < entity.componentCount(); i++) {
        final Component c = entity.componentAt(i);
        if (c instanceof NetState s) {
          s.readState(in);
        }
      }
    } catch (NetDataException e) {
      log.warn("State of {} does not match its components: {}", entity, e.toString());
    }
  }

  void warnOnce(String key, String format, Object... args) {
    if (this.warned.add(key)) {
      log.warn(format, args);
    }
  }
}
