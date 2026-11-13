package engine.net.replication;

import engine.ecs.Component;

/**
 * Marks an entity as shared over the network. {@link ServerReplication} adds it when the host spawns the entity, and
 * {@link ClientReplication} when the copy is made on a client; games read it, they do not create it.
 */
public final class NetworkIdentity extends Component {
  /** {@link #owner()} of entities that belong to the host (or to nobody). */
  public static final int HOST = -1;

  private int netId;
  private String type;
  private int owner = HOST;
  private byte[] spawnData = new byte[0];
  private boolean copy;
  private int localClient = HOST;

  NetworkIdentity() {
  }

  void init(int netId, String type, int owner, byte[] spawnData, boolean copy, int localClient) {
    this.netId = netId;
    this.type = type;
    this.owner = owner;
    this.spawnData = spawnData;
    this.copy = copy;
    this.localClient = localClient;
  }

  /** The number the host gave the entity, the same on every machine (1 and up). */
  public int netId() {
    return this.netId;
  }

  /** The spawn type it was made from ({@link Replication#registerSpawner}). */
  public String type() {
    return this.type;
  }

  /** The client it belongs to ({@code Connection.id()} / {@code NetClient.id()}), or {@link #HOST}. */
  public int owner() {
    return this.owner;
  }

  /** What the host passed to {@code spawn} (e.g. a colour or a name), the same on every machine. */
  public byte[] spawnData() {
    return this.spawnData.clone();
  }

  /** True on clients: this is a copy that follows the host's entity. False on the host. */
  public boolean isCopy() {
    return this.copy;
  }

  /** Whether the entity belongs to this machine: to the host on the host, to this client on a client. */
  public boolean isMine() {
    return this.owner == this.localClient;
  }

  void setLocalClient(int id) {
    this.localClient = id;
  }

  @Override
  public String toString() {
    return "NetworkIdentity[" + this.netId + " " + this.type + " owner " + this.owner + "]";
  }
}
