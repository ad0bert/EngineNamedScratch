package engine.messaging;

public interface IMessageReceiver {
  void receiveMessage(MessageObject message);

  /** Id used for targeted messages; {@code null} if this receiver cannot be addressed individually. */
  default Long receiverId() {
    return null;
  }
}
