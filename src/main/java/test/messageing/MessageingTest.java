package test.messageing;

import org.junit.Test;

import engine.messageing.MessageObject;
import engine.messageing.MessageQueueNames;
import engine.messageing.MessageServiceImpl;
import engine.messageing.interfaces.IMessageReciver;
import engine.messageing.interfaces.IMessageService;

public class MessageingTest {

    IMessageService service = new MessageServiceImpl();

    @Test
    public void test() {

        IMessageReciver reciver1 = new DummyMessageReciver(1);
        IMessageReciver reciver2 = new DummyMessageReciver(2);
        IMessageReciver reciver3 = new DummyMessageReciver(3);

        this.service.register(reciver1, MessageQueueNames.QUEUES.get("MOUSE"));
        this.service.register(reciver2, null);
        this.service.register(reciver3, MessageQueueNames.QUEUES.get("KEYBOARD"));

        this.service.send(new MessageObject(this, null, null, "Hello"));
        this.service.send(new MessageObject(this, null, MessageQueueNames.QUEUES.get("MOUSE"), "Hello1"));
        this.service.send(new MessageObject(this, 1, null, "Hello2"));
        this.service.send(new MessageObject(this, null, MessageQueueNames.QUEUES.get("KEYBOARD"), "Hello3"));
    }
}
