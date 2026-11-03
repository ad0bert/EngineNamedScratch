package engine.net;

/**
 * What a {@link NetServer} or {@link NetClient} reports. Called on the update thread, from {@code receive()} (and
 * {@code flush()} or a disconnect call for connections that end there).
 */
public interface NetListener {
  /** A client connected (server), or this client is now connected (client). */
  default void connected(Connection connection) {
  }

  /**
   * A connection ended, or (client) the attempt to connect failed; then {@code connection} is null. {@code message} is
   * the text the other side gave (a kick or a denial), or empty.
   */
  default void disconnected(Connection connection, DisconnectReason reason, String message) {
  }

  /** A message arrived. The array belongs to the listener. */
  default void received(Connection connection, byte[] data, Delivery delivery) {
  }
}
