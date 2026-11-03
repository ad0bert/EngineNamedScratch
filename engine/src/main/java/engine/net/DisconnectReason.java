package engine.net;

/** Why a connection ended, or a connection attempt failed. */
public enum DisconnectReason {
  /** The client left: it called {@code disconnect()} (seen on both sides). */
  LEFT(1),
  /** The server ended this connection ({@link Connection#disconnect(String)}). */
  KICKED(2),
  /** The server shut down. */
  SERVER_CLOSED(3),
  /** Nothing arrived from the other side for {@link NetConfig#timeout()}. */
  TIMED_OUT(4),
  /** The server has {@link NetConfig#maxClients()} players already. */
  SERVER_FULL(5),
  /** The server's {@link ConnectionFilter} refused the client; its reason is the message. */
  DENIED(6),
  /** The server runs a different game or version ({@link NetConfig#protocolId()}). */
  VERSION_MISMATCH(7),
  /** The server did not answer within {@link NetConfig#connectTimeout()}. */
  NO_ANSWER(8),
  /** Too much reliable data was waiting to be acknowledged ({@link NetConfig#maxPendingReliable()}). */
  OVERFLOW(9),
  /** The other side sent data that breaks the rules (a message over the size limit, for example). */
  PROTOCOL_ERROR(10);

  final int code;

  DisconnectReason(int code) {
    this.code = code;
  }

  static DisconnectReason of(int code) {
    for (DisconnectReason r : values()) {
      if (r.code == code) {
        return r;
      }
    }
    return PROTOCOL_ERROR;
  }
}
