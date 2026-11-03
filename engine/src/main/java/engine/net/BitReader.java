package engine.net;

import java.nio.charset.StandardCharsets;

/**
 * Reads what a {@link BitWriter} wrote, in the same order. The data comes from the network, so every read checks it:
 * reading past the end, a length over its limit or a value out of range throws {@link NetDataException}.
 */
public final class BitReader {
  private final byte[] data;
  private final int offset;
  private final int bitLimit;
  private int bitPosition;

  public BitReader(byte[] data) {
    this(data, 0, data.length);
  }

  public BitReader(byte[] data, int offset, int length) {
    if (offset < 0 || length < 0 || offset + length > data.length) {
      throw new IllegalArgumentException("range outside the array");
    }
    this.data = data;
    this.offset = offset;
    this.bitLimit = length * 8;
  }

  public long readBits(int bits) {
    if (bits < 1 || bits > 64) {
      throw new IllegalArgumentException("bits must be 1..64: " + bits);
    }
    if (bits > remainingBits()) {
      throw new NetDataException("read past the end of the data");
    }
    long result = 0;
    int shift = 0;
    int remaining = bits;
    while (remaining > 0) {
      final int index = this.offset + (this.bitPosition >>> 3);
      final int bitOffset = this.bitPosition & 7;
      final int take = Math.min(8 - bitOffset, remaining);
      final int chunk = ((this.data[index] & 0xFF) >>> bitOffset) & ((1 << take) - 1);
      result |= (long) chunk << shift;
      shift += take;
      remaining -= take;
      this.bitPosition += take;
    }
    return result;
  }

  public boolean readBoolean() {
    return readBits(1) != 0;
  }

  public int readUnsigned(int bits) {
    if (bits < 1 || bits > 31) {
      throw new IllegalArgumentException("bits must be 1..31: " + bits);
    }
    return (int) readBits(bits);
  }

  /** A number written by {@link BitWriter#writeInt(int, int, int)} with the same range. */
  public int readInt(int min, int max) {
    final long range = (long) max - min;
    if (range < 0) {
      throw new IllegalArgumentException("max < min");
    }
    if (range == 0) {
      return min;
    }
    final long value = readBits(BitWriter.bitsFor(range));
    if (value > range) {
      throw new NetDataException("value out of range " + min + ".." + max);
    }
    return (int) (min + value);
  }

  public int readInt() {
    return (int) readBits(32);
  }

  public long readLong() {
    return readBits(64);
  }

  public int readVarInt() {
    int result = 0;
    for (int shift = 0; shift < 35; shift += 7) {
      final int b = (int) readBits(8);
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        if (result < 0 || (shift == 28 && b > 0x07)) {
          throw new NetDataException("varint too large");
        }
        return result;
      }
    }
    throw new NetDataException("varint too long");
  }

  public float readFloat() {
    return Float.intBitsToFloat((int) readBits(32));
  }

  public double readDouble() {
    return Double.longBitsToDouble(readBits(64));
  }

  /** A value written by {@link BitWriter#writeFloat(float, float, float, int)} with the same range and bits. */
  public float readFloat(float min, float max, int bits) {
    if (bits < 1 || bits > 31 || !(max > min)) {
      throw new IllegalArgumentException("need 1..31 bits and max > min");
    }
    final long steps = (1L << bits) - 1;
    return min + (max - min) * (readBits(bits) / (float) steps);
  }

  public String readString(int maxBytes) {
    return new String(readBytes(maxBytes), StandardCharsets.UTF_8);
  }

  /** Bytes written by {@link BitWriter#writeBytes}; throws if there are more than {@code maxBytes}. */
  public byte[] readBytes(int maxBytes) {
    final int length = readVarInt();
    if (length > maxBytes) {
      throw new NetDataException(length + " bytes, more than " + maxBytes);
    }
    if ((long) length * 8 > remainingBits()) {
      throw new NetDataException("read past the end of the data");
    }
    final byte[] out = new byte[length];
    for (int i = 0; i < length; i++) {
      out[i] = (byte) readBits(8);
    }
    return out;
  }

  public int remainingBits() {
    return this.bitLimit - this.bitPosition;
  }

  /** Whether at least one whole byte is left (the padding of the last byte does not count). */
  public boolean hasMoreBytes() {
    return remainingBits() >= 8;
  }
}
