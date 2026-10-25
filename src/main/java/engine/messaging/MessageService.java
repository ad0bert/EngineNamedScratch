package engine.messaging;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe message service. Receiver lists are copy-on-write, so receivers may (un)register while a message is
 * being delivered.
 */
@Slf4j
public class MessageService implements IMessageService {
  private final Map<MessageQueue, List<IMessageReceiver>> queues = new EnumMap<>(MessageQueue.class);
  private final List<IMessageReceiver> all = new CopyOnWriteArrayList<>();
  private final Queue<MessageObject> pending = new ConcurrentLinkedQueue<>();

  public MessageService() {
    for (MessageQueue q : MessageQueue.values()) {
      this.queues.put(q, new CopyOnWriteArrayList<>());
    }
  }

  @Override
  public void send(MessageObject message) {
    Objects.requireNonNull(message, "message");
    final List<IMessageReceiver> targets = message.getQueue() == null ? this.all
        : this.queues.get(message.getQueue());
    for (IMessageReceiver receiver : targets) {
      if (message.getReceiverId() != null && !message.getReceiverId().equals(receiver.receiverId())) {
        continue;
      }
      try {
        receiver.receiveMessage(message);
      } catch (RuntimeException e) {
        log.error("Receiver {} failed to handle message {}", receiver, message, e);
      }
    }
  }

  @Override
  public void post(MessageObject message) {
    this.pending.add(Objects.requireNonNull(message, "message"));
  }

  @Override
  public void dispatchPending() {
    MessageObject m;
    while ((m = this.pending.poll()) != null) {
      send(m);
    }
  }

  @Override
  public boolean register(IMessageReceiver receiver, MessageQueue queue) {
    Objects.requireNonNull(receiver, "receiver");
    if (!this.all.contains(receiver)) {
      this.all.add(receiver);
    }
    if (queue == null) {
      return true;
    }
    final List<IMessageReceiver> list = this.queues.get(queue);
    return !list.contains(receiver) && list.add(receiver);
  }

  @Override
  public boolean unregister(IMessageReceiver receiver, MessageQueue queue) {
    if (queue == null) {
      boolean ok = this.all.remove(receiver);
      for (List<IMessageReceiver> list : this.queues.values()) {
        ok |= list.remove(receiver);
      }
      return ok;
    }
    return this.queues.get(queue).remove(receiver);
  }
}
