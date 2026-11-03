package engine.net;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import engine.core.Engine;
import engine.core.StepHook;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Accepts clients and exchanges messages with them. In a co-op game with a listen server, the hosting player's game
 * runs one next to its own scene; it can equally run in a headless engine.
 *
 * <pre>{@code
 * var server = new NetServer(UdpTransport.bind(27015), NetConfig.forGame(engine.config()));
 * server.setListener(new NetListener() {
 *   public void connected(Connection c) { ... }
 *   public void received(Connection c, byte[] data, Delivery d) { ... }
 * });
 * engine.addStepHook(server); // receives before the systems, sends after the step
 * }</pre>
 *
 * Joining takes a handshake: the client asks, the server answers with a cookie made from the client's address, and
 * only a client that sends the cookie back gets a connection. The server keeps no state for clients that have not
 * finished it, and never answers with more bytes than it was sent, so forged requests can neither fill its memory nor
 * use it to flood someone else.
 *
 * <p>
 * Not thread-safe: call it on the update thread (as a {@link StepHook} it is).
 */
@Slf4j
public final class NetServer implements StepHook, AutoCloseable {
  private static final long COOKIE_PERIOD = 10_000_000_000L;
  private static final int MAX_PACKETS_PER_RECEIVE = 4096;

  private final Transport transport;
  @Getter private final NetConfig config;
  private final LongSupplier clock;
  private final SecureRandom random = new SecureRandom();
  private final Mac mac;
  private final ByteBuffer in;
  private final ByteBuffer out = ByteBuffer.allocate(Packets.REQUEST_SIZE);
  private final Map<SocketAddress, Connection> byAddress = new HashMap<>();
  private final Connection[] slots;
  private final List<Connection> connections = new ArrayList<>();
  private List<Connection> view = List.of();
  private NetListener listener = new NetListener() {
  };
  private ConnectionFilter filter = (from, payload) -> null;
  private final Connection.Owner owner = new Connection.Owner() {
    @Override
    public void deliver(Connection connection, byte[] data, Delivery delivery) {
      NetServer.this.listener.received(connection, data, delivery);
    }

    @Override
    public void disconnectRequested(Connection connection, String message) {
      end(connection, DisconnectReason.KICKED, message, true);
    }
  };
  private boolean closed;
  /** Packets dropped because they were malformed, for diagnostics. */
  @Getter private long malformedPackets;

  /** A server using the system clock. The server owns the transport and closes it in {@link #close()}. */
  public NetServer(Transport transport, NetConfig config) {
    this(transport, config, System::nanoTime);
  }

  /** {@code clock} gives the time in nanoseconds (tests drive it by hand). */
  public NetServer(Transport transport, NetConfig config, LongSupplier clock) {
    this.transport = transport;
    this.config = config;
    this.clock = clock;
    this.in = ByteBuffer.allocate(config.maxPacketSize() + 1);
    this.slots = new Connection[config.maxClients()];
    try {
      this.mac = Mac.getInstance("HmacSHA256");
      final byte[] secret = new byte[32];
      this.random.nextBytes(secret);
      this.mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  public void setListener(NetListener listener) {
    this.listener = listener;
  }

  /** Decides who may join; by default everyone, up to {@link NetConfig#maxClients()}. */
  public void setFilter(ConnectionFilter filter) {
    this.filter = filter;
  }

  /** Where clients connect to. */
  public SocketAddress address() {
    return this.transport.localAddress();
  }

  /** The connected clients, in the order they joined. A snapshot: it does not change when clients come and go. */
  public List<Connection> connections() {
    return this.view;
  }

  /** The connected client with this {@link Connection#id()}, or null. */
  public Connection connection(int id) {
    return id >= 0 && id < this.slots.length ? this.slots[id] : null;
  }

  /** Sends to every connected client. */
  public void broadcast(byte[] data, Delivery delivery) {
    for (Connection c : this.view) {
      c.send(data, delivery);
    }
  }

  /** Sends to every connected client except {@code except} (e.g. the one a chat line came from). */
  public void broadcastExcept(Connection except, byte[] data, Delivery delivery) {
    for (Connection c : this.view) {
      if (c != except) {
        c.send(data, delivery);
      }
    }
  }

  /** {@link #receive()}, then {@link #flush()}. */
  public void update() {
    receive();
    flush();
  }

  /**
   * Handles every packet that arrived: handshakes, messages (the listener's {@code received}), acknowledgements,
   * goodbyes; then ends connections that timed out.
   */
  public void receive() {
    if (this.closed) {
      return;
    }
    for (int i = 0; i < MAX_PACKETS_PER_RECEIVE; i++) {
      this.in.clear();
      final var from = this.transport.receive(this.in);
      if (from == null) {
        break;
      }
      this.in.flip();
      if (this.in.remaining() > this.config.maxPacketSize()) {
        this.malformedPackets++;
        continue;
      }
      try {
        handle(from, this.in);
      } catch (NetDataException | BufferUnderflowException e) {
        this.malformedPackets++;
        log.trace("Dropped a malformed packet from {}: {}", from, e.toString());
      }
    }
    final long now = this.clock.getAsLong();
    for (Connection c : this.view) {
      if (c.isConnected()) {
        if (c.violated()) {
          end(c, DisconnectReason.PROTOCOL_ERROR, "", true);
        } else if (c.timedOut(now)) {
          end(c, DisconnectReason.TIMED_OUT, "", false);
        }
      }
    }
  }

  /** Sends what was queued for each client, along with acknowledgements and keep-alives. */
  public void flush() {
    if (this.closed) {
      return;
    }
    final long now = this.clock.getAsLong();
    for (Connection c : this.view) {
      if (c.overflowed()) {
        end(c, DisconnectReason.OVERFLOW, "", true);
      } else {
        c.flush(this.config.protocolId(), now);
      }
    }
  }

  /** Disconnects every client ({@link DisconnectReason#SERVER_CLOSED}) and closes the transport. */
  @Override
  public void close() {
    if (this.closed) {
      return;
    }
    for (Connection c : this.view) {
      end(c, DisconnectReason.SERVER_CLOSED, "", true);
    }
    this.closed = true;
    this.transport.close();
  }

  @Override
  public void beforeSystems(Engine engine, long tick, double dt) {
    receive();
  }

  @Override
  public void afterStep(Engine engine, long tick, double dt) {
    flush();
  }

  private void handle(SocketAddress from, ByteBuffer p) {
    if (p.remaining() < Packets.HEADER || (p.getShort() & 0xFFFF) != Packets.MAGIC) {
      throw new NetDataException("not ours");
    }
    final int protocol = p.getInt();
    final byte type = p.get();
    if (protocol != this.config.protocolId()) {
      if (type == Packets.CONNECT_REQUEST && p.limit() >= Packets.REQUEST_SIZE) {
        deny(from, p.getLong(), protocol, DisconnectReason.VERSION_MISMATCH, "");
      }
      return;
    }
    switch (type) {
      case Packets.CONNECT_REQUEST -> {
        if (p.limit() < Packets.REQUEST_SIZE) {
          throw new NetDataException("request not padded");
        }
        final long clientSalt = p.getLong();
        this.out.clear();
        Packets.header(this.out, this.config.protocolId(), Packets.CHALLENGE);
        this.out.putLong(clientSalt).putLong(cookie(from, clientSalt, this.clock.getAsLong() / COOKIE_PERIOD));
        send(from);
      }
      case Packets.CHALLENGE_RESPONSE -> {
        if (p.limit() < Packets.REQUEST_SIZE) {
          throw new NetDataException("response not padded");
        }
        challengeResponse(from, p);
      }
      case Packets.DATA -> {
        final var c = this.byAddress.get(from);
        if (c != null && c.isConnected() && p.getLong() == c.salt()) {
          c.receive(p, this.clock.getAsLong());
        }
      }
      case Packets.DISCONNECT -> {
        final var c = this.byAddress.get(from);
        if (c != null && c.isConnected() && p.getLong() == c.salt()) {
          final var why = DisconnectReason.of(p.get());
          end(c, why, Packets.getText(p), false);
        }
      }
      default -> throw new NetDataException("unexpected packet type " + type);
    }
  }

  private void challengeResponse(SocketAddress from, ByteBuffer p) {
    final long clientSalt = p.getLong();
    final long cookie = p.getLong();
    final byte[] payload = Packets.getBytes(p, NetConfig.MAX_CONNECT_PAYLOAD);
    final long period = this.clock.getAsLong() / COOKIE_PERIOD;
    if (cookie != cookie(from, clientSalt, period) && cookie != cookie(from, clientSalt, period - 1)) {
      return; // forged, or too old
    }
    final long salt = clientSalt ^ cookie;
    final var existing = this.byAddress.get(from);
    if (existing != null) {
      if (existing.salt() == salt) {
        accept(existing); // our accept was lost
        return;
      }
      end(existing, DisconnectReason.LEFT, "", false); // the client started again from the same address
    }
    int slot = -1;
    for (int i = 0; i < this.slots.length && slot < 0; i++) {
      if (this.slots[i] == null) {
        slot = i;
      }
    }
    if (slot < 0) {
      deny(from, clientSalt, this.config.protocolId(), DisconnectReason.SERVER_FULL, "");
      return;
    }
    final String refusal = this.filter.check(from, payload.clone());
    if (refusal != null) {
      deny(from, clientSalt, this.config.protocolId(), DisconnectReason.DENIED, refusal);
      return;
    }
    final var c = new Connection(slot, from, salt, this.config, this.transport, this.owner, payload,
        this.clock.getAsLong());
    this.slots[slot] = c;
    this.byAddress.put(from, c);
    this.connections.add(c);
    this.view = Collections.unmodifiableList(new ArrayList<>(this.connections));
    accept(c);
    log.info("Client {} connected from {}", slot, from);
    this.listener.connected(c);
  }

  private void accept(Connection c) {
    this.out.clear();
    Packets.header(this.out, this.config.protocolId(), Packets.ACCEPT);
    this.out.putLong(c.salt()).putShort((short) c.id());
    send(c.address());
  }

  private void deny(SocketAddress to, long clientSalt, int protocol, DisconnectReason why, String text) {
    this.out.clear();
    Packets.header(this.out, protocol, Packets.DENY);
    this.out.putLong(clientSalt).put((byte) why.code);
    Packets.putBytes(this.out, Packets.text(text));
    send(to);
  }

  private void send(SocketAddress to) {
    this.out.flip();
    this.transport.send(to, this.out);
  }

  private void end(Connection c, DisconnectReason why, String text, boolean tell) {
    if (!c.isConnected()) {
      return;
    }
    if (tell) {
      c.sendDisconnect(this.config.protocolId(), why, text);
    }
    c.close(why, text);
    this.slots[c.id()] = null;
    this.byAddress.remove(c.address(), c);
    this.connections.remove(c);
    this.view = Collections.unmodifiableList(new ArrayList<>(this.connections));
    log.info("Client {} disconnected: {}", c.id(), why);
    this.listener.disconnected(c, why, c.disconnectMessage());
  }

  private long cookie(SocketAddress from, long clientSalt, long period) {
    this.mac.update(ByteBuffer.allocate(16).putLong(period).putLong(clientSalt).array());
    if (from instanceof InetSocketAddress inet && inet.getAddress() != null) {
      this.mac.update(inet.getAddress().getAddress());
      this.mac.update(ByteBuffer.allocate(4).putInt(inet.getPort()).array());
    } else {
      this.mac.update(from.toString().getBytes(StandardCharsets.UTF_8));
    }
    return ByteBuffer.wrap(this.mac.doFinal()).getLong();
  }
}
