package engine.net;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import lombok.extern.slf4j.Slf4j;

/**
 * UDP through a non-blocking {@link DatagramChannel}. The operating system queues arriving packets until the next
 * {@link #receive}, which the server or client calls once per update step; the receive buffer is made large enough
 * for a step's worth of traffic.
 */
@Slf4j
public final class UdpTransport implements Transport {
  private static final int SOCKET_BUFFER = 1 << 20;
  private final DatagramChannel channel;
  private final SocketAddress local;
  private long sendErrors;

  private UdpTransport(DatagramChannel channel) throws IOException {
    this.channel = channel;
    this.local = channel.getLocalAddress();
  }

  /** Listens on {@code port} on every network interface (0: a free port, see {@link #localAddress()}). */
  public static UdpTransport bind(int port) {
    return bind(new InetSocketAddress(port));
  }

  /** Listens on the given address, e.g. {@code new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)}. */
  public static UdpTransport bind(InetSocketAddress address) {
    DatagramChannel ch = null;
    try {
      ch = DatagramChannel.open();
      ch.configureBlocking(false);
      ch.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER);
      ch.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER);
      ch.bind(address);
      return new UdpTransport(ch);
    } catch (IOException e) {
      if (ch != null) {
        try {
          ch.close();
        } catch (IOException ignored) {
          // already failing
        }
      }
      throw new UncheckedIOException("Cannot open a UDP socket on " + address, e);
    }
  }

  /** For a client: a free port on every interface. */
  public static UdpTransport open() {
    return bind(0);
  }

  @Override
  public void send(SocketAddress to, ByteBuffer packet) {
    try {
      this.channel.send(packet, to);
    } catch (IOException e) {
      if (this.sendErrors++ % 1000 == 0) {
        log.debug("Could not send to {}: {}", to, e.toString());
      }
    }
  }

  @Override
  public SocketAddress receive(ByteBuffer into) {
    try {
      return this.channel.receive(into);
    } catch (IOException e) {
      // e.g. Windows reports an earlier packet's "port unreachable" here; the next call goes on
      log.debug("Receive failed: {}", e.toString());
      return null;
    }
  }

  @Override
  public SocketAddress localAddress() {
    return this.local;
  }

  /** The port this transport listens on. */
  public int port() {
    return ((InetSocketAddress) this.local).getPort();
  }

  @Override
  public void close() {
    try {
      this.channel.close();
    } catch (IOException e) {
      log.debug("Closing the socket failed: {}", e.toString());
    }
  }
}
