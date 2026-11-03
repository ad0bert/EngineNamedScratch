package engine.net;

import java.net.SocketAddress;

/** Lets a server refuse clients, e.g. when the game has started or the name is taken. */
@FunctionalInterface
public interface ConnectionFilter {
  /**
   * Decides about a client that passed the handshake. {@code payload} is what it passed to
   * {@link NetClient#connect(byte[])} (up to {@link NetConfig#MAX_CONNECT_PAYLOAD} bytes, unchecked). Returns null to let
   * it in, or the reason to show it (sent as {@link DisconnectReason#DENIED}'s message, up to 200 bytes of UTF-8).
   */
  String check(SocketAddress from, byte[] payload);
}
