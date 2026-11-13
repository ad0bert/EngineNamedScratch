package engine.net.replication;

import java.util.Arrays;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.Quantize;

/**
 * An entity's replicated state as the network carries it: position in steps of 1/1024 m, packed rotation, the
 * {@link NetState} bytes. Host and client hold identical copies, so snapshots can describe changes from one.
 */
record QState(int x, int y, int z, long rotation, byte[] state) {
  static QState of(Entity entity) {
    final var t = entity.transform();
    return new QState(Quantize.position(t.position().x), Quantize.position(t.position().y),
        Quantize.position(t.position().z), Quantize.rotation(t.rotation()), Replication.stateOf(entity));
  }

  Vector3f position(Vector3f dest) {
    return dest.set(Quantize.position(this.x), Quantize.position(this.y), Quantize.position(this.z));
  }

  Quaternionf orientation(Quaternionf dest) {
    return Quantize.rotation(this.rotation, dest);
  }

  boolean samePlace(QState o) {
    return this.x == o.x && this.y == o.y && this.z == o.z;
  }

  /**
   * Writes this state as a change from {@code base} (null: in full). Flags say which parts follow; a position change
   * is three zig-zag varints, so slow movement takes a few bytes.
   */
  void write(BitWriter out, QState base) {
    final boolean move = base == null || !samePlace(base);
    final boolean turn = base == null || base.rotation != this.rotation;
    final boolean change = base == null ? this.state.length > 0 : !Arrays.equals(base.state, this.state);
    out.writeBoolean(move).writeBoolean(turn).writeBoolean(change);
    if (move) {
      if (base == null) {
        out.writeInt(this.x).writeInt(this.y).writeInt(this.z);
      } else {
        out.writeVarInt(Quantize.zigzag(this.x - base.x)).writeVarInt(Quantize.zigzag(this.y - base.y))
            .writeVarInt(Quantize.zigzag(this.z - base.z));
      }
    }
    if (turn) {
      out.writeBits(this.rotation, Quantize.ROTATION_BITS);
    }
    if (change) {
      out.writeBytes(this.state);
    }
  }

  /** Reads what {@link #write} wrote with the same base. */
  static QState read(BitReader in, QState base) {
    final boolean move = in.readBoolean();
    final boolean turn = in.readBoolean();
    final boolean change = in.readBoolean();
    int nx = base == null ? 0 : base.x;
    int ny = base == null ? 0 : base.y;
    int nz = base == null ? 0 : base.z;
    if (move) {
      if (base == null) {
        nx = in.readInt();
        ny = in.readInt();
        nz = in.readInt();
      } else {
        nx += Quantize.unzigzag(in.readVarInt());
        ny += Quantize.unzigzag(in.readVarInt());
        nz += Quantize.unzigzag(in.readVarInt());
      }
    }
    final long rot = turn ? in.readBits(Quantize.ROTATION_BITS) : base == null ? 0 : base.rotation;
    final byte[] st = change ? in.readBytes(Replication.MAX_DATA) : base == null ? new byte[0] : base.state;
    return new QState(nx, ny, nz, rot, st);
  }

  /** Whether {@code o} needs nothing sent: same place, rotation and state. */
  boolean sameAs(QState o) {
    return o != null && samePlace(o) && this.rotation == o.rotation && Arrays.equals(this.state, o.state);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof QState q && sameAs(q);
  }

  @Override
  public int hashCode() {
    return 31 * (31 * (31 * this.x + this.y) + this.z) + Long.hashCode(this.rotation) + Arrays.hashCode(this.state);
  }
}
