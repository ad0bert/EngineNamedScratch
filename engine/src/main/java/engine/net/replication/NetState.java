package engine.net.replication;

import engine.net.BitReader;
import engine.net.BitWriter;

/**
 * Implemented by components whose state the host shares with clients besides the transform: a door's angle, a
 * player's health, a lamp being on. The host calls {@link #writeState} after every step it sends a snapshot in, and
 * sends the bytes when they changed; clients call {@link #readState} on their copy when the snapshot's moment is
 * reached (in step with the interpolated movement). Both must read and write the same fields in the same order.
 *
 * <p>
 * The components of an entity are written in the order they were added, so the spawner must build the entity the same
 * way on the host and on clients.
 */
public interface NetState {
  void writeState(BitWriter out);

  void readState(BitReader in);
}
