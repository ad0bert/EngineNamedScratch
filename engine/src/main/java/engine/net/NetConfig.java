package engine.net;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import engine.core.EngineConfig;

/**
 * Settings shared by a server and its clients. Times are in seconds.
 *
 * @param protocolId         identifies the game and its network version; a client only connects to a server with the
 *                           same id (otherwise {@link DisconnectReason#VERSION_MISMATCH}). {@link #forGame} derives it
 *                           from the game's id and version
 * @param maxClients         how many clients a server accepts (1..4096)
 * @param maxPacketSize      the largest datagram sent, in bytes; 1200 stays below the usual internet MTU
 * @param timeout            a connection ends when nothing arrived for this long
 * @param connectTimeout     a client gives up connecting after this long
 * @param keepAlive          a packet is sent at least this often, so the other side knows the connection is alive and
 *                           gets acknowledgements
 * @param maxMessageSize     the largest reliable message, in bytes
 * @param maxPendingReliable reliable message parts that may wait for acknowledgement before the connection ends with
 *                           {@link DisconnectReason#OVERFLOW}
 * @param encrypted          whether packets are encrypted and authenticated (AES-256-GCM, keys agreed with X25519
 *                           when connecting); server and clients must agree. On by default
 * @param password           null, or a password clients must know to join ({@link DisconnectReason#WRONG_PASSWORD}
 *                           otherwise). Needs {@code encrypted}. It is never sent: it goes into the keys, so without it
 *                           nobody in between can read or change the traffic either. Someone who records a
 *                           connection attempt could still try passwords offline, so use more than a word
 */
public record NetConfig(int protocolId, int maxClients, int maxPacketSize, double timeout, double connectTimeout,
    double keepAlive, int maxMessageSize, int maxPendingReliable, boolean encrypted, String password) {
  /** The most a client can send along when connecting ({@link NetClient#connect(byte[])}). */
  public static final int MAX_CONNECT_PAYLOAD = 512;

  public NetConfig {
    if (maxClients < 1 || maxClients > 4096) {
      throw new IllegalArgumentException("maxClients must be 1..4096: " + maxClients);
    }
    if (maxPacketSize < 600 || maxPacketSize > 65000) {
      throw new IllegalArgumentException("maxPacketSize must be 600..65000: " + maxPacketSize);
    }
    if (!(timeout > 0) || !(connectTimeout > 0) || !(keepAlive > 0)) {
      throw new IllegalArgumentException("times must be positive");
    }
    if (maxMessageSize < 1 || maxPendingReliable < 1) {
      throw new IllegalArgumentException("limits must be positive");
    }
    if (password != null && !encrypted) {
      throw new IllegalArgumentException("a password needs encryption");
    }
  }

  /** Keeps the password out of logs. */
  @Override
  public String toString() {
    return "NetConfig[protocolId=" + this.protocolId + ", maxClients=" + this.maxClients + ", maxPacketSize="
        + this.maxPacketSize + ", timeout=" + this.timeout + ", connectTimeout=" + this.connectTimeout
        + ", keepAlive=" + this.keepAlive + ", maxMessageSize=" + this.maxMessageSize + ", maxPendingReliable="
        + this.maxPendingReliable + ", encrypted=" + this.encrypted + ", password=" + (this.password == null ? "none"
            : "***") + "]";
  }

  /** Defaults: 8 clients, 1200-byte packets, 10 s timeout, 5 s to connect, 256 KiB messages, encrypted. */
  public static NetConfig of(int protocolId) {
    return new NetConfig(protocolId, 8, 1200, 10.0, 5.0, 0.1, 256 * 1024, 4096, true, null);
  }

  /**
   * The defaults with a protocol id made from the game's {@link EngineConfig#id()} and {@link EngineConfig#version()}:
   * only the same build of the same game can connect.
   */
  public static NetConfig forGame(EngineConfig game) {
    return of(protocolId(game.id() + "@" + game.version()));
  }

  /** A stable number for a text, e.g. {@code protocolId("mygame/3")}. */
  public static int protocolId(String text) {
    final var crc = new CRC32();
    crc.update(text.getBytes(StandardCharsets.UTF_8));
    return (int) crc.getValue();
  }

  public NetConfig withMaxClients(int value) {
    return new NetConfig(this.protocolId, value, this.maxPacketSize, this.timeout, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withMaxPacketSize(int value) {
    return new NetConfig(this.protocolId, this.maxClients, value, this.timeout, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withTimeout(double value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, value, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withConnectTimeout(double value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, value,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withKeepAlive(double value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, this.connectTimeout,
        value, this.maxMessageSize, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withMaxMessageSize(int value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, this.connectTimeout,
        this.keepAlive, value, this.maxPendingReliable, this.encrypted, this.password);
  }

  public NetConfig withMaxPendingReliable(int value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, value, this.encrypted, this.password);
  }

  public NetConfig withEncryption(boolean value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, value, value ? this.password : null);
  }

  /** Requires clients to know {@code value} (null: anyone may join). */
  public NetConfig withPassword(String value) {
    return new NetConfig(this.protocolId, this.maxClients, this.maxPacketSize, this.timeout, this.connectTimeout,
        this.keepAlive, this.maxMessageSize, this.maxPendingReliable, this.encrypted || value != null, value);
  }
}
