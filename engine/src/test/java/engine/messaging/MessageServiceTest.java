package engine.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MessageServiceTest {

  static class Rec implements IMessageReceiver {
    final List<Object> got = new ArrayList<>();
    final Long id;

    Rec(Long id) {
      this.id = id;
    }

    @Override
    public void receiveMessage(MessageObject m) {
      this.got.add(m.getPayload());
    }

    @Override
    public Long receiverId() {
      return this.id;
    }
  }

  private static MessageObject msg(MessageQueue q, Long to, Object payload) {
    return MessageObject.builder().queue(q).receiverId(to).payload(payload).build();
  }

  @Test
  void queueMessagesReachOnlyQueueSubscribers() {
    final var svc = new MessageService();
    final var mouse = new Rec(null);
    final var keys = new Rec(null);
    final var any = new Rec(null);
    svc.register(mouse, MessageQueue.MOUSE);
    svc.register(keys, MessageQueue.KEYBOARD);
    svc.register(any, null);

    svc.send(msg(MessageQueue.MOUSE, null, "m"));

    assertEquals(List.of("m"), mouse.got);
    assertTrue(keys.got.isEmpty());
    assertTrue(any.got.isEmpty());
  }

  @Test
  void broadcastReachesEveryone() {
    final var svc = new MessageService();
    final var a = new Rec(null);
    final var b = new Rec(null);
    svc.register(a, MessageQueue.MOUSE);
    svc.register(b, null);

    svc.send(msg(null, null, "hello"));

    assertEquals(List.of("hello"), a.got);
    assertEquals(List.of("hello"), b.got);
  }

  @Test
  void targetedMessageReachesOnlyMatchingReceiver() {
    final var svc = new MessageService();
    final var a = new Rec(1L);
    final var b = new Rec(2L);
    svc.register(a, null);
    svc.register(b, null);

    svc.send(msg(null, 2L, "x"));

    assertTrue(a.got.isEmpty());
    assertEquals(List.of("x"), b.got);
  }

  @Test
  void sendingToEmptyQueueDoesNotThrow() {
    new MessageService().send(msg(MessageQueue.GAME, null, "nobody"));
  }

  @Test
  void unregisterWithNullQueueDoesNotThrowAndRemovesEverywhere() {
    final var svc = new MessageService();
    final var a = new Rec(null);
    svc.register(a, MessageQueue.MOUSE);

    assertTrue(svc.unregister(a, null));
    svc.send(msg(MessageQueue.MOUSE, null, "m"));
    svc.send(msg(null, null, "b"));

    assertTrue(a.got.isEmpty());
    assertFalse(svc.unregister(a, null));
  }

  @Test
  void receiverMayUnregisterWhileBeingNotified() {
    final var svc = new MessageService();
    final List<String> order = new ArrayList<>();
    final IMessageReceiver once = new IMessageReceiver() {
      @Override
      public void receiveMessage(MessageObject m) {
        order.add("once");
        svc.unregister(this, null);
      }
    };
    svc.register(once, MessageQueue.GAME);
    svc.register(m -> order.add("other"), MessageQueue.GAME);

    svc.send(msg(MessageQueue.GAME, null, 1));
    svc.send(msg(MessageQueue.GAME, null, 2));

    assertEquals(List.of("once", "other", "other"), order);
  }

  @Test
  void postedMessagesAreDeliveredOnDispatch() {
    final var svc = new MessageService();
    final var a = new Rec(null);
    svc.register(a, MessageQueue.KEYBOARD);

    svc.post(msg(MessageQueue.KEYBOARD, null, "k"));
    assertTrue(a.got.isEmpty());
    svc.dispatchPending();

    assertEquals(List.of("k"), a.got);
  }

  @Test
  void aFailingReceiverDoesNotBlockOthers() {
    final var svc = new MessageService();
    final var ok = new Rec(null);
    svc.register(m -> {
      throw new IllegalStateException("boom");
    }, MessageQueue.GAME);
    svc.register(ok, MessageQueue.GAME);

    svc.send(msg(MessageQueue.GAME, null, "x"));

    assertEquals(List.of("x"), ok.got);
  }
}
