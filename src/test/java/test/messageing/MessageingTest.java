package test.messageing;

import org.junit.jupiter.api.Test;

import engine.messageing.MessageObject;
import engine.messageing.MessageQueueNames;
import engine.messageing.MessageServiceImpl;
import engine.messageing.interfaces.IMessageService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.val;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class MessageingTest {

  IMessageService service = new MessageServiceImpl();

  @Test
  public void test() {

    val reciver1 = new DummyMessageReciver(1);
    val reciver2 = new DummyMessageReciver(2);
    val reciver3 = new DummyMessageReciver(3);

    this.service.register(reciver1, MessageQueueNames.QUEUES.get("MOUSE"));
    this.service.register(reciver2, null);
    this.service.register(reciver3, MessageQueueNames.QUEUES.get("KEYBOARD"));

    this.service.send(MessageObject.builder().sender(this).transportedObject("Hello").build());
    this.service.send(MessageObject.builder().sender(this).queueId(MessageQueueNames.QUEUES.get("MOUSE"))
        .transportedObject("Hello1").build());
    this.service.send(MessageObject.builder().sender(this).reciverId(1).transportedObject("Hello2").build());
    this.service.send(MessageObject.builder().sender(this).queueId(MessageQueueNames.QUEUES.get("KEYBOARD"))
        .transportedObject("Hello3").build());
  }
}
