package engine.net.replication;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.core.Engine;
import engine.ecs.Entity;
import engine.net.BitReader;

/**
 * Builds the entity for a spawn type, on the host and on every client. The replication adds the returned entity to
 * the scene, places it and gives it its {@link NetworkIdentity}; the spawner only makes it (children it makes it adds to
 * the scene itself). On a client, {@link Context#onHost()} is false: a spawner can leave out what only the host needs,
 * such as AI. Rigid bodies of copies are made kinematic and character controllers are turned off, since the host
 * moves them.
 */
@FunctionalInterface
public interface Spawner {
  Entity spawn(Context context);

  /**
   * What is being spawned.
   *
   * @param type     the spawn type
   * @param netId    the entity's network number
   * @param owner    the owning client, or {@link NetworkIdentity#HOST}
   * @param data     what the host passed to {@code spawn}
   * @param position where it starts
   * @param rotation how it is turned
   * @param onHost   true on the host, false on clients
   * @param engine   the engine it is spawned into
   */
  record Context(String type, int netId, int owner, byte[] data, Vector3f position, Quaternionf rotation,
      boolean onHost, Engine engine) {
    /** A reader over {@link #data()}. */
    public BitReader reader() {
      return new BitReader(this.data);
    }
  }
}
