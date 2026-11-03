package engine.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes values bit by bit into a growing buffer, for compact network messages: a flag takes one bit, a number from 0
 * to 99 seven. Read them back in the same order with {@link BitReader}. The last byte is padded with zeros.
 *
 * <pre>{@code
 * var w = new BitWriter().writeBits(MSG_CHAT, 4).writeString(text, 200);
 * client.send(w.toByteArray(), Delivery.RELIABLE);
 * }</pre>
 */
public final class BitWriter {
  private byte[] buffer;
  private int bitPosition;

  public BitWriter() {
    this(64);
  }

  public BitWriter(int initialBytes) {
    this.buffer = new byte[Math.max(1, initialBytes)];
  }

  /** The lowest {@code bits} bits of {@code value} (1 to 64). */
  public BitWriter writeBits(long value, int bits) {
    if (bits < 1 || bits > 64) {
      throw new IllegalArgumentException("bits must be 1..64: " + bits);
    }
    ensure(this.bitPosition + bits);
    int remaining = bits;
    while (remaining > 0) {
      final int index = this.bitPosition >>> 3;
      final int offset = this.bitPosition & 7;
      final int take = Math.min(8 - offset, remaining);
      final int chunk = (int) (value & ((1 << take) - 1));
      this.buffer[index] |= (byte) (chunk << offset);
      value >>>= take;
      remaining -= take;
      this.bitPosition += take;
    }
    return this;
  }

  public BitWriter writeBoolean(boolean value) {
    return writeBits(value ? 1 : 0, 1);
  }

  /** An unsigned number that fits in {@code bits} bits (0 to 2^bits - 1). */
  public BitWriter writeUnsigned(int value, int bits) {
    if (bits < 1 || bits > 31) {
      throw new IllegalArgumentException("bits must be 1..31: " + bits);
    }
    if (value < 0 || value >>> bits != 0) {
      throw new IllegalArgumentException(value + " does not fit in " + bits + " unsigned bits");
    }
    return writeBits(value, bits);
  }

  /** A number from {@code min} to {@code max}, in as few bits as that range needs. */
  public BitWriter writeInt(int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(value + " is outside " + min + ".." + max);
    }
    final long range = (long) max - min;
    return range == 0 ? this : writeBits((long) value - min, bitsFor(range));
  }

  public BitWriter writeInt(int value) {
    return writeBits(value, 32);
  }

  public BitWriter writeLong(long value) {
    return writeBits(value, 64);
  }

  /**
   * A non-negative number in 8 bits per 7 of its value: small numbers (lengths, counts) take one or two bytes.
   */
  public BitWriter writeVarInt(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("negative: " + value);
    }
    while ((value & ~0x7F) != 0) {
      writeBits((value & 0x7F) | 0x80, 8);
      value >>>= 7;
    }
    return writeBits(value, 8);
  }

  public BitWriter writeFloat(float value) {
    return writeBits(Float.floatToIntBits(value) & 0xFFFFFFFFL, 32);
  }

  public BitWriter writeDouble(double value) {
    return writeBits(Double.doubleToLongBits(value), 64);
  }

  /**
   * {@code value} clamped to {@code min..max} and stored in {@code bits} bits (1 to 31): the error is at most
   * {@code (max - min) / (2^bits - 1) / 2}. Read it with {@link BitReader#readFloat(float, float, int)}.
   */
  public BitWriter writeFloat(float value, float min, float max, int bits) {
    if (bits < 1 || bits > 31 || !(max > min)) {
      throw new IllegalArgumentException("need 1..31 bits and max > min");
    }
    final long steps = (1L << bits) - 1;
    final float t = Float.isNaN(value) ? 0f : Math.clamp((value - min) / (max - min), 0f, 1f);
    return writeBits(Math.round(t * steps), bits);
  }

  /** UTF-8 with its length in front; throws if it takes more than {@code maxBytes}. */
  public BitWriter writeString(String value, int maxBytes) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxBytes) {
      throw new IllegalArgumentException("string of " + bytes.length + " bytes, more than " + maxBytes);
    }
    return writeBytes(bytes);
  }

  /** The bytes with their length in front. */
  public BitWriter writeBytes(byte[] bytes) {
    writeVarInt(bytes.length);
    for (byte b : bytes) {
      writeBits(b, 8);
    }
    return this;
  }

  /** How many bits have been written. */
  public int bitLength() {
    return this.bitPosition;
  }

  /** How many bytes {@link #toByteArray()} returns. */
  public int byteLength() {
    return (this.bitPosition + 7) >>> 3;
  }

  /** What was written, the last byte padded with zero bits. */
  public byte[] toByteArray() {
    return Arrays.copyOf(this.buffer, byteLength());
  }

  /** Empties the writer to use it again. */
  public BitWriter reset() {
    Arrays.fill(this.buffer, 0, byteLength(), (byte) 0);
    this.bitPosition = 0;
    return this;
  }

  /** Bits needed for the numbers 0..range. */
  static int bitsFor(long range) {
    return 64 - Long.numberOfLeadingZeros(range);
  }

  private void ensure(int bits) {
    final int bytes = (bits + 7) >>> 3;
    if (bytes > this.buffer.length) {
      this.buffer = Arrays.copyOf(this.buffer, Math.max(bytes, this.buffer.length * 2));
    }
  }
}
