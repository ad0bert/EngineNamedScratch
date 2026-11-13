package engine.net.replication;

import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.Connection;

/** Handles a remote procedure call, on the update thread. */
@FunctionalInterface
public interface RpcHandler {
  void handle(Call call);

  /**
   * One call.
   *
   * @param name   the name it was registered under
   * @param entity the entity it was sent to, or null for a call to no entity
   * @param args   the arguments the caller wrote
   * @param from   on the host: the client that called (check that it may: {@link #isFromOwner()}); on a client: the
   *               connection to the host
   */
  record Call(String name, Entity entity, BitReader args, Connection from) {
    /** Whether the caller owns {@link #entity()}: the usual check before a client may act on an entity. */
    public boolean isFromOwner() {
      final var id = this.entity == null ? null : this.entity.findOrNull(NetworkIdentity.class);
      return id != null && id.owner() == this.from.id();
    }
  }
}
