package engine.net;

import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.function.LongSupplier;

import engine.core.Engine;
import engine.core.StepHook;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Connects to a {@link NetServer} and exchanges messages with it.
 *
 * <pre>{@code
 * var client = new NetClient(UdpTransport.open(), new InetSocketAddress(host, 27015),
 *     NetConfig.forGame(engine.config()));
 * client.setListener(...);
 * client.connect(playerName.getBytes(UTF_8));
 * engine.addStepHook(client);
 * }</pre>
 *
 * {@link #state()} goes from {@link State#CONNECTING} to {@link State#CONNECTED}, and ends in
 * {@link State#DISCONNECTED} with a {@link #disconnectReason()}; the listener hears of both. {@link #connect} may be
 * called again after that. Not thread-safe: call it on the update thread (as a {@link StepHook} it is).
 */
@Slf4j
public final class NetClient implements StepHook, AutoCloseable {
  /** Where the client is. */
  public enum State {
    /** {@link #connect} has not been called. */
    IDLE,
    /** Doing the handshake. */
    CONNECTING,
    CONNECTED,
    /** The connection ended, or connecting failed; see {@link #disconnectReason()}. */
    DISCONNECTED
  }

  private static final long HANDSHAKE_RESEND = 100_000_000L;
  private static final int MAX_PACKETS_PER_RECEIVE = 4096;

  private final Transport transport;
  private final SocketAddress server;
  @Getter private final NetConfig config;
  private final LongSupplier clock;
  private final SecureRandom random = new SecureRandom();
  private final ByteBuffer in;
  private final ByteBuffer out = ByteBuffer.allocate(Packets.REQUEST_SIZE);
  private NetListener listener = new NetListener() {
  };
  private final Connection.Owner owner = new Connection.Owner() {
    @Override
    public void deliver(Connection connection, byte[] data, Delivery delivery) {
      NetClient.this.listener.received(connection, data, delivery);
    }

    @Override
    public void disconnectRequested(Connection connection, String message) {
      end(DisconnectReason.LEFT, message, true);
    }
  };

  @Getter private State state = State.IDLE;
  private Connection connection;
  private DisconnectReason reason;
  private String message = "";
  private byte[] payload = new byte[0];
  private long clientSalt;
  private long cookie;
  private boolean challenged;
  private long connectStart;
  private long lastHandshake;
  private boolean closed;
  private final byte[] passwordKey;
  private java.security.KeyPair keyPair;
  /** Packets dropped because they were malformed, for diagnostics. */
  @Getter private long malformedPackets;

  /** A client using the system clock. It owns the transport and closes it in {@link #close()}. */
  public NetClient(Transport transport, SocketAddress server, NetConfig config) {
    this(transport, server, config, System::nanoTime);
  }

  /** {@code clock} gives the time in nanoseconds (tests drive it by hand). */
  public NetClient(Transport transport, SocketAddress server, NetConfig config, LongSupplier clock) {
    this.transport = transport;
    this.server = server;
    this.config = config;
    this.clock = clock;
    this.in = ByteBuffer.allocate(config.maxPacketSize() + 1);
    this.passwordKey = config.encrypted() ? PacketCrypto.passwordKey(config.password(), config.protocolId()) : null;
  }

  public void setListener(NetListener listener) {
    this.listener = listener;
  }

  public void connect() {
    connect(new byte[0]);
  }

  /**
   * Starts connecting; {@code payload} (up to {@link NetConfig#MAX_CONNECT_PAYLOAD} bytes, such as the player's name)
   * reaches the server's {@link ConnectionFilter} and {@link Connection#connectPayload()}.
   */
  public void connect(byte[] payload) {
    if (this.closed) {
      throw new IllegalStateException("closed");
    }
    if (this.state == State.CONNECTING || this.state == State.CONNECTED) {
      throw new IllegalStateException("already " + this.state);
    }
    if (payload.length > NetConfig.MAX_CONNECT_PAYLOAD) {
      throw new IllegalArgumentException("connect payload of " + payload.length + " bytes, more than "
          + NetConfig.MAX_CONNECT_PAYLOAD);
    }
    this.payload = payload.clone();
    this.state = State.CONNECTING;
    this.connection = null;
    this.reason = null;
    this.message = "";
    this.clientSalt = this.random.nextLong();
    this.challenged = false;
    this.connectStart = this.clock.getAsLong();
    this.lastHandshake = Long.MIN_VALUE;
    this.keyPair = this.config.encrypted() ? PacketCrypto.newKeyPair() : null;
  }

  /** The connection to the server while connected (and after it ended), else null. */
  public Connection connection() {
    return this.connection;
  }

  /** This client's number on the server, or -1 if not connected. */
  public int id() {
    return this.state == State.CONNECTED ? this.connection.id() : -1;
  }

  public boolean isConnected() {
    return this.state == State.CONNECTED;
  }

  /** Why the client is {@link State#DISCONNECTED}, or null. */
  public DisconnectReason disconnectReason() {
    return this.reason;
  }

  /** The server's text for a kick or a denial, or empty. */
  public String disconnectMessage() {
    return this.message;
  }

  /** The server's address. */
  public SocketAddress serverAddress() {
    return this.server;
  }

  /** Queues a message for the server; false if not connected. See {@link Connection#send(byte[], Delivery)}. */
  public boolean send(byte[] data, Delivery delivery) {
    return this.state == State.CONNECTED && this.connection.send(data, delivery);
  }

  public boolean send(BitWriter message, Delivery delivery) {
    return send(message.toByteArray(), delivery);
  }

  /** Leaves the server ({@link DisconnectReason#LEFT}), or stops connecting. */
  public void disconnect() {
    if (this.state == State.CONNECTED) {
      end(DisconnectReason.LEFT, "", true);
    } else if (this.state == State.CONNECTING) {
      fail(DisconnectReason.LEFT, "");
    }
  }

  /** {@link #receive()}, then {@link #flush()}. */
  public void update() {
    receive();
    flush();
  }

  /** Handles the packets that arrived from the server, and gives up on a connection or attempt that timed out. */
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
      if (!this.server.equals(from) || this.in.remaining() > this.config.maxPacketSize()) {
        continue;
      }
      try {
        handle(this.in);
      } catch (NetDataException | BufferUnderflowException e) {
        this.malformedPackets++;
        log.trace("Dropped a malformed packet: {}", e.toString());
      }
    }
    final long now = this.clock.getAsLong();
    if (this.state == State.CONNECTING && now - this.connectStart > (long) (this.config.connectTimeout() * 1e9)) {
      fail(DisconnectReason.NO_ANSWER, "");
    } else if (this.state == State.CONNECTED) {
      if (this.connection.violated()) {
        end(DisconnectReason.PROTOCOL_ERROR, "", true);
      } else if (this.connection.timedOut(now)) {
        end(DisconnectReason.TIMED_OUT, "", false);
      }
    }
  }

  /** Sends handshake packets while connecting, else what was queued, acknowledgements and keep-alives. */
  public void flush() {
    if (this.closed) {
      return;
    }
    final long now = this.clock.getAsLong();
    if (this.state == State.CONNECTING) {
      if (this.lastHandshake == Long.MIN_VALUE || now - this.lastHandshake >= HANDSHAKE_RESEND) {
        sendHandshake(now);
      }
    } else if (this.state == State.CONNECTED) {
      if (this.connection.overflowed()) {
        end(DisconnectReason.OVERFLOW, "", true);
      } else {
        this.connection.flush(this.config.protocolId(), now);
      }
    }
  }

  /** Leaves the server if connected, and closes the transport. */
  @Override
  public void close() {
    if (!this.closed) {
      disconnect();
      this.closed = true;
      this.transport.close();
    }
  }

  @Override
  public void beforeSystems(Engine engine, long tick, double dt) {
    receive();
  }

  @Override
  public void afterStep(Engine engine, long tick, double dt) {
    flush();
  }

  private void handle(ByteBuffer p) {
    if (p.remaining() < Packets.HEADER || (p.getShort() & 0xFFFF) != Packets.MAGIC) {
      throw new NetDataException("not ours");
    }
    final int protocol = p.getInt();
    final byte type = p.get();
    if (type == Packets.DENY) { // a server of another version answers with its own protocol id
      if (this.state == State.CONNECTING && p.getLong() == this.clientSalt) {
        final var why = DisconnectReason.of(p.get());
        fail(why, Packets.getText(p));
      }
      return;
    }
    if (protocol != this.config.protocolId()) {
      return;
    }
    switch (type) {
      case Packets.CHALLENGE -> {
        if (this.state == State.CONNECTING && !this.challenged && p.getLong() == this.clientSalt) {
          this.cookie = p.getLong();
          this.challenged = true;
          sendHandshake(this.clock.getAsLong());
        }
      }
      case Packets.ACCEPT -> {
        final long salt = this.clientSalt ^ this.cookie;
        if (this.state == State.CONNECTING && this.challenged && p.getLong() == salt) {
          final int id = p.getShort() & 0xFFFF;
          PacketCrypto crypto = null;
          if (this.keyPair != null) {
            final byte[] serverPublic = new byte[PacketCrypto.PUBLIC_KEY];
            p.get(serverPublic);
            final byte[] confirmation = new byte[PacketCrypto.TAG];
            p.get(confirmation);
            final byte[] clientPublic = PacketCrypto.raw(this.keyPair);
            final var keys = PacketCrypto.keys(PacketCrypto.agree(this.keyPair.getPrivate(), serverPublic),
                this.passwordKey, this.clientSalt, this.cookie, clientPublic, serverPublic);
            if (!java.security.MessageDigest.isEqual(confirmation,
                keys.confirmation(NetServer.transcript(clientPublic, serverPublic, salt)))) {
              throw new NetDataException("the accept does not prove the keys"); // forged, or a different password
            }
            crypto = keys.forClient();
          }
          this.connection = new Connection(id, this.server, salt, this.config, this.transport, this.owner,
              new byte[0], this.clock.getAsLong(), crypto);
          this.state = State.CONNECTED;
          log.info("Connected to {} as client {}", this.server, id);
          this.listener.connected(this.connection);
        }
      }
      case Packets.DATA -> {
        if (this.state == State.CONNECTED && p.getLong() == this.connection.salt()) {
          this.connection.receive(p, this.clock.getAsLong());
        }
      }
      case Packets.DISCONNECT -> {
        if (this.state == State.CONNECTED && p.getLong() == this.connection.salt()) {
          final var body = this.connection.disconnectBody(p); // throws if it is not from the server
          final var why = DisconnectReason.of(body.get());
          end(why, Packets.getText(body), false);
        }
      }
      default -> throw new NetDataException("unexpected packet type " + type);
    }
  }

  private void sendHandshake(long now) {
    this.out.clear();
    if (this.challenged) {
      Packets.header(this.out, this.config.protocolId(), Packets.CHALLENGE_RESPONSE);
      this.out.putLong(this.clientSalt).putLong(this.cookie);
      Packets.putBytes(this.out, this.payload);
      if (this.keyPair != null) {
        final byte[] clientPublic = PacketCrypto.raw(this.keyPair);
        final boolean hasPassword = this.config.password() != null;
        this.out.put(clientPublic).put((byte) (hasPassword ? 1 : 0)).put(hasPassword
            ? PacketCrypto.proof(this.passwordKey, this.clientSalt, this.cookie, clientPublic)
            : new byte[PacketCrypto.PROOF]);
      }
    } else {
      Packets.header(this.out, this.config.protocolId(), Packets.CONNECT_REQUEST);
      this.out.putLong(this.clientSalt).put((byte) (this.config.encrypted() ? 1 : 0));
    }
    Packets.padTo(this.out, Packets.REQUEST_SIZE);
    this.out.flip();
    this.transport.send(this.server, this.out);
    this.lastHandshake = now;
  }

  private void end(DisconnectReason why, String text, boolean tell) {
    if (this.state != State.CONNECTED) {
      return;
    }
    if (tell) {
      this.connection.sendDisconnect(this.config.protocolId(), why, text);
    }
    this.connection.close(why, text);
    this.state = State.DISCONNECTED;
    this.reason = why;
    this.message = this.connection.disconnectMessage();
    log.info("Disconnected from {}: {}", this.server, why);
    this.listener.disconnected(this.connection, why, this.message);
  }

  private void fail(DisconnectReason why, String text) {
    this.state = State.DISCONNECTED;
    this.reason = why;
    this.message = text == null ? "" : text;
    log.info("Could not connect to {}: {}", this.server, why);
    this.listener.disconnected(null, why, this.message);
  }
}
