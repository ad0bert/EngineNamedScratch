package engine.net;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * One end of a connection between a {@link NetServer} and a {@link NetClient}: the server has one per client, a client
 * one to the server. Messages go out with the next {@code flush()} of their server or client, which packs them into as
 * few packets as fit {@link NetConfig#maxPacketSize()}.
 *
 * <p>
 * Each packet has a sequence number and acknowledges the last 33 packets received from the other side. From that the
 * connection knows which packets arrived: reliable messages that were in lost packets are sent again (after about one
 * round trip), and it measures the round-trip time and the packet loss. Reliable messages are numbered and handed over
 * in order; large ones are sent in parts and put back together.
 *
 * <p>
 * Use it on the update thread only.
 */
public final class Connection {
  static final int SENT_BUFFER = 1024;
  static final int RECEIVED_BUFFER = 1024;
  /** Reliable parts that may be on their way at once; the receiver buffers as many that arrived early. */
  static final int RELIABLE_WINDOW = 512;
  private static final int MAX_PACKETS_PER_FLUSH = 64;
  private static final long NANOS = 1_000_000_000L;

  /** The server or client that owns the connection. */
  interface Owner {
    void deliver(Connection connection, byte[] data, Delivery delivery);

    void disconnectRequested(Connection connection, String message);
  }

  private static final class Outgoing {
    final long id;
    final byte[] part;
    final boolean more;
    long lastSent = Long.MIN_VALUE;
    boolean acked;

    Outgoing(long id, byte[] part, boolean more) {
      this.id = id;
      this.part = part;
      this.more = more;
    }
  }

  private static final class SentPacket {
    long sequence = -1;
    long time;
    boolean acked;
    final List<Outgoing> reliable = new ArrayList<>();
  }

  private record Incoming(long id, byte[] data, boolean more) {
  }

  /** Server: the client's number (0..maxClients-1), the same on both sides. */
  @Getter private final int id;
  private final SocketAddress address;
  private final long salt;
  private final NetConfig config;
  private final Transport transport;
  private final Owner owner;
  private final byte[] connectPayload;
  private final ByteBuffer out;
  private final int maxPart;

  private boolean connected = true;
  private DisconnectReason reason;
  private String message = "";
  /** Anything the game wants to keep with the connection, such as its player. */
  @Getter @Setter private Object userData;

  // sending
  private long nextSequence;
  private final SentPacket[] sent = new SentPacket[SENT_BUFFER];
  private final ArrayList<Outgoing> reliable = new ArrayList<>(); // not acknowledged yet, oldest first
  private long nextReliableId;
  private final List<byte[]> unreliable = new ArrayList<>();
  private long lastSendTime;
  private boolean overflow;

  // receiving
  private final long[] received = new long[RECEIVED_BUFFER];
  private long latestReceived = -1;
  private boolean ackPending;
  private long lastReceiveTime;
  private long expectedReliable;
  private final Incoming[] early = new Incoming[RELIABLE_WINDOW];
  private byte[] assembly = new byte[0];
  private int assemblyLength;
  private boolean violation;

  // statistics
  private double rtt = -1;
  private double loss;
  private long lossCursor;
  private long latestAck = -1;
  @Getter private long packetsSent;
  @Getter private long packetsReceived;
  @Getter private long bytesSent;
  @Getter private long bytesReceived;
  private long rateWindowStart;
  private long rateBytesSent;
  private long rateBytesReceived;
  private double sendRate;
  private double receiveRate;

  /** Null when the connection is not encrypted. */
  private final PacketCrypto crypto;
  /** Server: the end of the accept message (public key, confirmation) to send again if the first was lost. */
  byte[] acceptExtra = new byte[0];

  Connection(int id, SocketAddress address, long salt, NetConfig config, Transport transport, Owner owner,
      byte[] connectPayload, long now) {
    this(id, address, salt, config, transport, owner, connectPayload, now, null);
  }

  Connection(int id, SocketAddress address, long salt, NetConfig config, Transport transport, Owner owner,
      byte[] connectPayload, long now, PacketCrypto crypto) {
    this.crypto = crypto;
    this.id = id;
    this.address = address;
    this.salt = salt;
    this.config = config;
    this.transport = transport;
    this.owner = owner;
    this.connectPayload = connectPayload;
    this.out = ByteBuffer.allocate(config.maxPacketSize());
    this.maxPart = config.maxPacketSize() - Packets.DATA_HEADER - Packets.ENTRY_OVERHEAD
        - (crypto == null ? 0 : PacketCrypto.TAG);
    for (int i = 0; i < SENT_BUFFER; i++) {
      this.sent[i] = new SentPacket();
    }
    Arrays.fill(this.received, -1);
    this.lastReceiveTime = now;
    this.lastSendTime = now - (long) (config.keepAlive() * NANOS); // the first flush sends
    this.rateWindowStart = now;
  }

  /**
   * Queues a message for the next flush. Returns false if the connection has ended (the message is dropped). Throws if
   * an unreliable message is larger than {@link #maxUnreliableSize()} or a reliable one than
   * {@link NetConfig#maxMessageSize()}.
   */
  public boolean send(byte[] data, Delivery delivery) {
    if (delivery == Delivery.UNRELIABLE) {
      if (data.length > this.maxPart) {
        throw new IllegalArgumentException("unreliable message of " + data.length + " bytes, more than "
            + this.maxPart + "; send it reliably");
      }
    } else if (data.length > this.config.maxMessageSize()) {
      throw new IllegalArgumentException("message of " + data.length + " bytes, more than maxMessageSize "
          + this.config.maxMessageSize());
    }
    if (!this.connected || this.overflow) {
      return false;
    }
    if (delivery == Delivery.UNRELIABLE) {
      this.unreliable.add(data.clone());
      return true;
    }
    final int parts = Math.max(1, (data.length + this.maxPart - 1) / this.maxPart);
    if (this.reliable.size() + parts > this.config.maxPendingReliable()) {
      this.overflow = true; // the owner ends the connection at the next flush
      return false;
    }
    for (int i = 0; i < parts; i++) {
      final int from = i * this.maxPart;
      final byte[] part = Arrays.copyOfRange(data, from, Math.min(data.length, from + this.maxPart));
      this.reliable.add(new Outgoing(this.nextReliableId++, part, i < parts - 1));
    }
    return true;
  }

  /** Writes the message's bytes; see {@link #send(byte[], Delivery)}. */
  public boolean send(BitWriter message, Delivery delivery) {
    return send(message.toByteArray(), delivery);
  }

  /** The largest unreliable message, in bytes: what fits in one packet. */
  public int maxUnreliableSize() {
    return this.maxPart;
  }

  /**
   * Ends the connection: a server kicks this client ({@link DisconnectReason#KICKED}, with {@code message} for it to
   * show), a client leaves ({@link DisconnectReason#LEFT}). The other side is told at once; the listener's
   * {@code disconnected} is called before this returns.
   */
  public void disconnect(String message) {
    if (this.connected) {
      this.owner.disconnectRequested(this, message);
    }
  }

  public void disconnect() {
    disconnect("");
  }

  public boolean isConnected() {
    return this.connected;
  }

  /** Why it ended, or null while connected. */
  public DisconnectReason disconnectReason() {
    return this.reason;
  }

  /** The text that came with the end (a kick message), or empty. */
  public String disconnectMessage() {
    return this.message;
  }

  /** The other side's address. */
  public SocketAddress address() {
    return this.address;
  }

  /** Server: what the client sent along when connecting ({@link NetClient#connect(byte[])}). */
  public byte[] connectPayload() {
    return this.connectPayload.clone();
  }

  /** Smoothed round-trip time in seconds (0 before the first acknowledgement). */
  public double rtt() {
    return Math.max(0, this.rtt);
  }

  /** Smoothed fraction of the packets sent that did not arrive (0..1). */
  public double packetLoss() {
    return this.loss;
  }

  /** Bytes sent per second, over the last second or so (packet headers included, UDP/IP headers not). */
  public double bytesSentPerSecond() {
    return this.sendRate;
  }

  public double bytesReceivedPerSecond() {
    return this.receiveRate;
  }

  /** Reliable message parts sent but not acknowledged yet. */
  public int pendingReliable() {
    return this.reliable.size();
  }

  @Override
  public String toString() {
    return "Connection[" + this.id + " " + this.address + (this.connected ? "" : " " + this.reason) + "]";
  }

  // ---- used by the server and client ----

  long salt() {
    return this.salt;
  }

  boolean overflowed() {
    return this.overflow;
  }

  boolean violated() {
    return this.violation;
  }

  boolean timedOut(long now) {
    return now - this.lastReceiveTime > (long) (this.config.timeout() * NANOS);
  }

  /** Marks the connection as ended; nothing is sent or delivered any more. */
  void close(DisconnectReason why, String text) {
    this.connected = false;
    this.reason = why;
    this.message = text == null ? "" : text;
    this.reliable.clear();
    this.unreliable.clear();
  }

  /**
   * Tells the other side, three times in case of loss. A DISCONNECT has a sequence number like DATA, so that encrypted
   * ones are authenticated (nobody else can end the connection).
   */
  void sendDisconnect(int protocolId, DisconnectReason why, String text) {
    final byte[] bytes = Packets.text(text);
    for (int i = 0; i < 3; i++) {
      final long sequence = this.nextSequence++;
      this.out.clear();
      Packets.header(this.out, protocolId, Packets.DISCONNECT);
      this.out.putLong(this.salt).putShort((short) sequence);
      final int headerEnd = this.out.position();
      this.out.put((byte) why.code);
      Packets.putBytes(this.out, bytes);
      if (this.crypto != null) {
        this.crypto.seal(this.out, headerEnd, sequence);
      }
      this.out.flip();
      this.transport.send(this.address, this.out);
    }
  }

  /**
   * The reason and text of a DISCONNECT packet positioned after its salt; throws {@link NetDataException} if it is not
   * authentic.
   */
  ByteBuffer disconnectBody(ByteBuffer packet) {
    final int wire = packet.getShort() & 0xFFFF;
    if (this.crypto == null) {
      return packet;
    }
    final long sequence = this.latestReceived < 0 ? wire : Packets.unwrap(wire, this.latestReceived);
    return this.crypto.open(packet, packet.position(), sequence);
  }

  /** A DATA packet's body after the salt. Throws {@link NetDataException} if it is malformed. */
  void receive(ByteBuffer body, long now) {
    final int wireSequence = body.getShort() & 0xFFFF;
    final int wireAck = body.getShort() & 0xFFFF;
    final int ackBits = body.getInt();
    final long sequence = this.latestReceived < 0 ? wireSequence : Packets.unwrap(wireSequence, this.latestReceived);
    if (sequence < 0 || this.received[(int) (sequence % RECEIVED_BUFFER)] == sequence
        || (this.latestReceived >= 0 && sequence <= this.latestReceived - RECEIVED_BUFFER)) {
      return; // a duplicate, or too old to tell
    }
    final int size = body.limit();
    if (this.crypto != null) {
      body = this.crypto.open(body, body.position(), sequence); // throws if changed, replayed or made up
    }
    // read everything first: a malformed packet changes nothing
    final List<Object> entries = new ArrayList<>();
    while (body.hasRemaining()) {
      final byte kind = body.get();
      if (kind == Packets.UNRELIABLE) {
        entries.add(Packets.getBytes(body, this.maxPart));
      } else if (kind == Packets.RELIABLE) {
        final int wireId = body.getShort() & 0xFFFF;
        final int flags = body.get();
        entries.add(new Incoming(Packets.unwrap(wireId, this.expectedReliable), Packets.getBytes(body, this.maxPart),
            (flags & 1) != 0));
      } else {
        throw new NetDataException("unknown entry " + kind);
      }
    }
    this.lastReceiveTime = now;
    this.packetsReceived++;
    this.bytesReceived += size;
    this.rateBytesReceived += size;
    this.received[(int) (sequence % RECEIVED_BUFFER)] = sequence;
    if (sequence > this.latestReceived) {
      this.latestReceived = sequence;
    }
    this.ackPending = true;
    applyAcks(wireAck, ackBits, now);

    for (Object entry : entries) {
      if (!this.connected) {
        return;
      }
      if (entry instanceof byte[] data) {
        this.owner.deliver(this, data, Delivery.UNRELIABLE);
      } else {
        final var part = (Incoming) entry;
        final long ahead = part.id - this.expectedReliable;
        if (ahead >= 0 && ahead < RELIABLE_WINDOW) {
          this.early[(int) (part.id % RELIABLE_WINDOW)] = part;
        }
        deliverReliable();
      }
    }
  }

  private void deliverReliable() {
    while (this.connected && !this.violation) {
      final int slot = (int) (this.expectedReliable % RELIABLE_WINDOW);
      final var part = this.early[slot];
      if (part == null || part.id != this.expectedReliable) {
        return;
      }
      this.early[slot] = null;
      this.expectedReliable++;
      if (this.assemblyLength + part.data.length > this.config.maxMessageSize()) {
        this.violation = true; // the owner ends the connection
        return;
      }
      if (!part.more && this.assemblyLength == 0) {
        this.owner.deliver(this, part.data, Delivery.RELIABLE);
        continue;
      }
      if (this.assembly.length < this.assemblyLength + part.data.length) {
        this.assembly = Arrays.copyOf(this.assembly,
            Math.max(this.assemblyLength + part.data.length, this.assembly.length * 2));
      }
      System.arraycopy(part.data, 0, this.assembly, this.assemblyLength, part.data.length);
      this.assemblyLength += part.data.length;
      if (!part.more) {
        final byte[] whole = Arrays.copyOf(this.assembly, this.assemblyLength);
        this.assemblyLength = 0;
        if (this.assembly.length > 64 * 1024) {
          this.assembly = new byte[0]; // do not keep a large buffer around
        }
        this.owner.deliver(this, whole, Delivery.RELIABLE);
      }
    }
  }

  private void applyAcks(int wireAck, int ackBits, long now) {
    if (this.nextSequence == 0) {
      return; // nothing sent yet
    }
    final long ack = Packets.unwrap(wireAck, this.nextSequence - 1);
    if (ack < 0 || ack >= this.nextSequence) {
      return;
    }
    acknowledge(ack, now);
    for (int i = 0; i < 32; i++) {
      if ((ackBits & (1 << i)) != 0) {
        acknowledge(ack - 1 - i, now);
      }
    }
    int done = 0;
    while (done < this.reliable.size() && this.reliable.get(done).acked) {
      done++;
    }
    if (done > 0) {
      this.reliable.subList(0, done).clear();
    }
    if (ack > this.latestAck) {
      this.latestAck = ack;
      // packets that fell out of the 33 acknowledged ones are decided: count them for the loss estimate
      final long decided = ack - 33;
      this.lossCursor = Math.max(this.lossCursor, decided - SENT_BUFFER + 1);
      for (; this.lossCursor <= decided; this.lossCursor++) {
        final var p = this.sent[(int) (this.lossCursor % SENT_BUFFER)];
        if (p.sequence == this.lossCursor) {
          this.loss += ((p.acked ? 0.0 : 1.0) - this.loss) * 0.02;
        }
      }
    }
  }

  private void acknowledge(long sequence, long now) {
    if (sequence < 0) {
      return;
    }
    final var p = this.sent[(int) (sequence % SENT_BUFFER)];
    if (p.sequence != sequence || p.acked) {
      return;
    }
    p.acked = true;
    for (Outgoing o : p.reliable) {
      o.acked = true;
    }
    p.reliable.clear();
    final double sample = (now - p.time) / 1e9;
    this.rtt = this.rtt < 0 ? sample : this.rtt + (sample - this.rtt) * 0.1;
  }

  /** Sends what is queued, parts that are due again, and acknowledgements or a keep-alive if needed. */
  void flush(int protocolId, long now) {
    updateRates(now);
    if (!this.connected) {
      return;
    }
    final long resend = (long) (Math.clamp(this.rtt < 0 ? 0.1 : this.rtt * 1.25 + 0.03, 0.03, 1.0) * NANOS);
    final List<Outgoing> due = new ArrayList<>();
    if (!this.reliable.isEmpty()) {
      final long windowEnd = this.reliable.get(0).id + RELIABLE_WINDOW;
      for (Outgoing o : this.reliable) {
        if (o.id >= windowEnd) {
          break;
        }
        if (!o.acked && (o.lastSent == Long.MIN_VALUE || now - o.lastSent >= resend)) {
          due.add(o);
        }
      }
    }
    final boolean keepAlive = now - this.lastSendTime >= (long) (this.config.keepAlive() * NANOS);
    int nextDue = 0;
    int nextUnreliable = 0;
    int packets = 0;
    while (packets < MAX_PACKETS_PER_FLUSH) {
      final boolean content = nextDue < due.size() || nextUnreliable < this.unreliable.size();
      if (!content && (packets > 0 || (!this.ackPending && !keepAlive))) {
        break;
      }
      final long sequence = this.nextSequence++;
      final var record = this.sent[(int) (sequence % SENT_BUFFER)];
      record.sequence = sequence;
      record.time = now;
      record.acked = false;
      record.reliable.clear();
      this.out.clear();
      if (this.crypto != null) {
        this.out.limit(this.out.capacity() - PacketCrypto.TAG); // room for the tag
      }
      Packets.header(this.out, protocolId, Packets.DATA);
      this.out.putLong(this.salt).putShort((short) sequence);
      writeAcks();
      final int headerEnd = this.out.position();
      while (nextDue < due.size()) {
        final var o = due.get(nextDue);
        if (this.out.remaining() < Packets.ENTRY_OVERHEAD + o.part.length) {
          break;
        }
        this.out.put(Packets.RELIABLE).putShort((short) o.id).put((byte) (o.more ? 1 : 0));
        Packets.putBytes(this.out, o.part);
        o.lastSent = now;
        record.reliable.add(o);
        nextDue++;
      }
      while (nextUnreliable < this.unreliable.size()) {
        final byte[] data = this.unreliable.get(nextUnreliable);
        if (this.out.remaining() < 1 + Packets.varIntSize(data.length) + data.length) {
          break;
        }
        this.out.put(Packets.UNRELIABLE);
        Packets.putBytes(this.out, data);
        nextUnreliable++;
      }
      if (this.crypto != null) {
        this.out.limit(this.out.capacity());
        this.crypto.seal(this.out, headerEnd, sequence);
      }
      this.out.flip();
      final int size = this.out.remaining();
      this.transport.send(this.address, this.out);
      this.packetsSent++;
      this.bytesSent += size;
      this.rateBytesSent += size;
      packets++;
    }
    this.unreliable.clear(); // what did not fit in this flush is out of date by the next one
    if (packets > 0) {
      this.lastSendTime = now;
      this.ackPending = false;
    }
  }

  private void writeAcks() {
    final long latest = this.latestReceived;
    if (latest < 0) {
      this.out.putShort((short) 0xFFFF).putInt(0); // acknowledges "sequence -1": nothing
      return;
    }
    int bits = 0;
    for (int i = 0; i < 32; i++) {
      final long s = latest - 1 - i;
      if (s >= 0 && this.received[(int) (s % RECEIVED_BUFFER)] == s) {
        bits |= 1 << i;
      }
    }
    this.out.putShort((short) latest).putInt(bits);
  }

  private void updateRates(long now) {
    final long elapsed = now - this.rateWindowStart;
    if (elapsed >= NANOS) {
      this.sendRate = this.rateBytesSent * 1e9 / elapsed;
      this.receiveRate = this.rateBytesReceived * 1e9 / elapsed;
      this.rateBytesSent = 0;
      this.rateBytesReceived = 0;
      this.rateWindowStart = now;
    }
  }
}
