package engine.net;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * Sends and receives datagrams: whole packets that may be lost, duplicated or arrive out of order, never corrupted or
 * split. {@link UdpTransport} is the real network, {@link LoopbackNetwork} an in-memory one for tests. Not
 * thread-safe: {@link NetServer} and {@link NetClient} use it from the update thread only, and never block on it.
 */
public interface Transport extends AutoCloseable {
  /** Sends the bytes from the buffer's position to its limit. Failures are dropped packets, not exceptions. */
  void send(SocketAddress to, ByteBuffer packet);

  /**
   * Puts the next waiting packet into {@code into} (from its position) and returns who sent it, or returns null at
   * once if nothing is waiting. A packet larger than the space left is cut short.
   */
  SocketAddress receive(ByteBuffer into);

  /** Where others send to reach this transport. */
  SocketAddress localAddress();

  @Override
  void close();
}
