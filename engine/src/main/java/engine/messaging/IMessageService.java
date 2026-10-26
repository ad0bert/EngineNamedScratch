package engine.messaging;

public interface IMessageService {
  /** Delivers immediately on the calling thread. */
  void send(MessageObject message);

  /** Thread-safe: queues the message for delivery on the next {@link #dispatchPending()}. */
  void post(MessageObject message);

  /** Delivers all posted messages; call from the update thread. */
  void dispatchPending();

  /** Registers for a queue; {@code queue == null} registers for broadcasts only. Broadcasts reach everyone. */
  boolean register(IMessageReceiver receiver, MessageQueue queue);

  /** Removes the receiver from the given queue; {@code null} removes it from everything. */
  boolean unregister(IMessageReceiver receiver, MessageQueue queue);
}
