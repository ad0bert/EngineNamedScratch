package engine.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BitStreamTest {
  @Test
  void valuesComeBackInOrder() {
    final var w = new BitWriter(1)
        .writeBoolean(true)
        .writeUnsigned(5, 3)
        .writeInt(-7, -10, 10)
        .writeInt(42)
        .writeLong(Long.MIN_VALUE + 3)
        .writeVarInt(300)
        .writeFloat(1.5f)
        .writeDouble(-2.25)
        .writeString("Grüße ✓", 64)
        .writeBytes(new byte[] { 1, 2, 3 })
        .writeInt(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE)
        .writeInt(5, 5, 5); // no bits at all
    final var r = new BitReader(w.toByteArray());
    assertTrue(r.readBoolean());
    assertEquals(5, r.readUnsigned(3));
    assertEquals(-7, r.readInt(-10, 10));
    assertEquals(42, r.readInt());
    assertEquals(Long.MIN_VALUE + 3, r.readLong());
    assertEquals(300, r.readVarInt());
    assertEquals(1.5f, r.readFloat());
    assertEquals(-2.25, r.readDouble());
    assertEquals("Grüße ✓", r.readString(64));
    assertArrayEquals(new byte[] { 1, 2, 3 }, r.readBytes(3));
    assertEquals(Integer.MIN_VALUE, r.readInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
    assertEquals(5, r.readInt(5, 5));
    assertFalse(r.hasMoreBytes());
  }

  @Test
  void smallValuesTakeFewBits() {
    final var w = new BitWriter().writeBoolean(true).writeInt(99, 0, 99);
    assertEquals(8, w.bitLength());
    assertEquals(1, w.toByteArray().length);
  }

  @Test
  void quantisedFloatsStayWithinHalfAStep() {
    final var random = new Random(1);
    for (int i = 0; i < 1000; i++) {
      final float v = random.nextFloat() * 200f - 100f;
      final var r = new BitReader(new BitWriter().writeFloat(v, -100f, 100f, 16).toByteArray());
      assertEquals(v, r.readFloat(-100f, 100f, 16), 200f / 65535f / 2f + 1e-4f);
    }
    final var clamped = new BitReader(new BitWriter().writeFloat(500f, 0f, 1f, 8).writeFloat(Float.NaN, 0f, 1f, 8)
        .toByteArray());
    assertEquals(1f, clamped.readFloat(0f, 1f, 8));
    assertEquals(0f, clamped.readFloat(0f, 1f, 8));
  }

  @Test
  void randomBitPatternsRoundTrip() {
    final var random = new Random(7);
    final var w = new BitWriter(1);
    final long[] values = new long[500];
    final int[] widths = new int[500];
    for (int i = 0; i < values.length; i++) {
      widths[i] = 1 + random.nextInt(64);
      values[i] = widths[i] == 64 ? random.nextLong() : random.nextLong() & ((1L << widths[i]) - 1);
      w.writeBits(values[i], widths[i]);
    }
    final var r = new BitReader(w.toByteArray());
    for (int i = 0; i < values.length; i++) {
      assertEquals(values[i], r.readBits(widths[i]), "value " + i);
    }
  }

  @Test
  void resetStartsOver() {
    final var w = new BitWriter().writeInt(-1);
    w.reset().writeBoolean(true);
    assertArrayEquals(new byte[] { 1 }, w.toByteArray());
  }

  @Test
  void badDataThrowsNetDataException() {
    assertThrows(NetDataException.class, () -> new BitReader(new byte[1]).readInt());
    final byte[] longString = new BitWriter().writeString("x".repeat(100), 100).toByteArray();
    assertThrows(NetDataException.class, () -> new BitReader(longString).readString(10));
    final byte[] claimsMore = new BitWriter().writeVarInt(1000).toByteArray();
    assertThrows(NetDataException.class, () -> new BitReader(claimsMore).readBytes(5000));
    final byte[] outOfRange = new BitWriter().writeUnsigned(15, 4).toByteArray();
    assertThrows(NetDataException.class, () -> new BitReader(outOfRange).readInt(0, 9));
    final byte[] endless = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1 };
    assertThrows(NetDataException.class, () -> new BitReader(endless).readVarInt());
  }

  @Test
  void writersRejectValuesThatDoNotFit() {
    final var w = new BitWriter();
    assertThrows(IllegalArgumentException.class, () -> w.writeUnsigned(8, 3));
    assertThrows(IllegalArgumentException.class, () -> w.writeInt(11, 0, 10));
    assertThrows(IllegalArgumentException.class, () -> w.writeVarInt(-1));
    assertThrows(IllegalArgumentException.class, () -> w.writeString("too long", 3));
  }

  @Test
  void sixteenBitCountersUnwrapAroundTheReference() {
    assertEquals(65536, Packets.unwrap(0, 65535));
    assertEquals(65535, Packets.unwrap(65535, 65536));
    assertEquals(70000, Packets.unwrap(70000 & 0xFFFF, 69990));
    assertEquals(-1, Packets.unwrap(0xFFFF, 0));
  }

  @Test
  void rotationsTakeFortySevenBitsAndComeBackClose() {
    final var random = new Random(4);
    for (int i = 0; i < 2000; i++) {
      final var q = new Quaternionf().rotateXYZ(random.nextFloat() * 7f, random.nextFloat() * 7f, random.nextFloat() * 7f);
      if (i % 2 == 0) {
        q.set(-q.x, -q.y, -q.z, -q.w); // the same rotation
      }
      final var w = new BitWriter().writeRotation(q);
      assertEquals(47, w.bitLength());
      final var back = new BitReader(w.toByteArray()).readRotation(new Quaternionf());
      final float dot = Math.abs(q.dot(back));
      assertTrue(dot > 0.99999f, "rotation " + i + " off by " + dot);
    }
    final var identity = new BitReader(new BitWriter().writeRotation(new Quaternionf(0, 0, 0, 0)).toByteArray())
        .readRotation(new Quaternionf());
    assertTrue(identity.equals(new Quaternionf(), 1e-4f), "a zero quaternion is sent as no rotation");
  }

  @Test
  void vectorsAndAlignment() {
    final var w = new BitWriter().writeBoolean(true).align().writeVector(new Vector3f(1, -2, 3.5f)).writeBits(1, 3)
        .align();
    assertEquals(8 + 96 + 8, w.bitLength());
    final var r = new BitReader(w.toByteArray());
    assertTrue(r.readBoolean());
    r.align();
    assertEquals(new Vector3f(1, -2, 3.5f), r.readVector(new Vector3f()));
    assertEquals(1, r.readBits(3));
    r.align();
    assertFalse(r.hasMoreBytes());
  }
}
