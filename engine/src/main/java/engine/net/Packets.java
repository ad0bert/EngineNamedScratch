package engine.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The packet format. Every packet starts with a 7-byte header: a 16-bit magic number (the engine's packet format), the
 * 32-bit {@link NetConfig#protocolId()} and a type byte. Then, by type:
 *
 * <pre>
 * CONNECT_REQUEST     client salt (8), zero padding up to REQUEST_SIZE
 * CHALLENGE           client salt (8), cookie (8)
 * CHALLENGE_RESPONSE  client salt (8), cookie (8), payload (varint length + bytes), padding up to REQUEST_SIZE
 * ACCEPT              session salt (8), client id (2)
 * DENY                client salt (8), reason (1), message (varint length + UTF-8)
 * DATA                session salt (8), sequence (2), ack (2), ack bits (4), entries until the end:
 *                       1 = unreliable: length (varint), bytes
 *                       2 = reliable part: id (2), flags (1: bit 0 = more parts follow), length (varint), bytes
 * DISCONNECT          session salt (8), reason (1), message (varint length + UTF-8)
 * </pre>
 *
 * Requests are padded so that no answer is larger than the request that caused it: a forged sender address cannot
 * turn the server into an amplifier. Numbers are big-endian.
 */
final class Packets {
  static final int MAGIC = 0xE5C1;
  static final int HEADER = 7;
  static final byte CONNECT_REQUEST = 1;
  static final byte CHALLENGE = 2;
  static final byte CHALLENGE_RESPONSE = 3;
  static final byte ACCEPT = 4;
  static final byte DENY = 5;
  static final byte DATA = 6;
  static final byte DISCONNECT = 7;
  /** Size of handshake requests, at least as large as any answer to them. */
  static final int REQUEST_SIZE = 600;
  /** DATA header after the common header: salt, sequence, ack, ack bits. */
  static final int DATA_HEADER = HEADER + 8 + 2 + 2 + 4;
  /** Largest overhead of one entry: kind, id, flags, length up to 2^21. */
  static final int ENTRY_OVERHEAD = 1 + 2 + 1 + 3;
  static final byte UNRELIABLE = 1;
  static final byte RELIABLE = 2;
  static final int MAX_TEXT = 200;

  private Packets() {
  }

  static void header(ByteBuffer b, int protocolId, byte type) {
    b.putShort((short) MAGIC).putInt(protocolId).put(type);
  }

  static void padTo(ByteBuffer b, int size) {
    while (b.position() < size) {
      b.put((byte) 0);
    }
  }

  static void putVarInt(ByteBuffer b, int value) {
    while ((value & ~0x7F) != 0) {
      b.put((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    b.put((byte) value);
  }

  static int varIntSize(int value) {
    int size = 1;
    while ((value & ~0x7F) != 0) {
      value >>>= 7;
      size++;
    }
    return size;
  }

  static int getVarInt(ByteBuffer b) {
    int result = 0;
    for (int shift = 0; shift < 35; shift += 7) {
      if (!b.hasRemaining()) {
        throw new NetDataException("cut short");
      }
      final int x = b.get() & 0xFF;
      result |= (x & 0x7F) << shift;
      if ((x & 0x80) == 0) {
        if (result < 0) {
          throw new NetDataException("varint too large");
        }
        return result;
      }
    }
    throw new NetDataException("varint too long");
  }

  static byte[] getBytes(ByteBuffer b, int maxLength) {
    final int length = getVarInt(b);
    if (length > maxLength || length > b.remaining()) {
      throw new NetDataException("bad length " + length);
    }
    final byte[] out = new byte[length];
    b.get(out);
    return out;
  }

  static void putBytes(ByteBuffer b, byte[] data) {
    putVarInt(b, data.length);
    b.put(data);
  }

  /** UTF-8, cut at a character boundary to at most {@link #MAX_TEXT} bytes. */
  static byte[] text(String s) {
    if (s == null) {
      return new byte[0];
    }
    String t = s;
    byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
    while (bytes.length > MAX_TEXT) {
      t = t.substring(0, t.offsetByCodePoints(0, t.codePointCount(0, t.length()) - 1));
      bytes = t.getBytes(StandardCharsets.UTF_8);
    }
    return bytes;
  }

  static String getText(ByteBuffer b) {
    return new String(getBytes(b, MAX_TEXT), StandardCharsets.UTF_8);
  }

  /** Expands a 16-bit number on the wire to the full counter closest to {@code reference}. */
  static long unwrap(int wire, long reference) {
    final int delta = (short) (wire - (int) reference); // -32768..32767
    return reference + delta;
  }
}
