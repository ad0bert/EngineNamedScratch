package engine.net;

/** How a message travels. */
public enum Delivery {
  /**
   * Sent once with the next packet: may be lost, and may arrive after messages sent later. For state that is sent again
   * and again anyway (positions, inputs). At most one packet in size ({@link Connection#maxUnreliableSize()}).
   */
  UNRELIABLE,
  /**
   * Sent again until it arrives, and handed over in the order sent, exactly once. For events (spawns, chat, a level
   * change). Up to {@link NetConfig#maxMessageSize()}; larger than a packet, it is split and put back together.
   */
  RELIABLE
}
