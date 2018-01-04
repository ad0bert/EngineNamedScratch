package test.messageing;

import org.junit.Test;

import engine.messageing.MessageObject;
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

        service.register(reciver1, 0xFF00);
        service.register(reciver2, null);
        service.register(reciver3, 0xEF01);

        service.send(new MessageObject(this, null, "Hello"));
        service.send(new MessageObject(this, 0xFF00, "Hello1"));
        service.send(new MessageObject(this, 0xEF01, "Hello2"));
        service.send(new MessageObject(this, 0xEF02, "Hello3"));
    }
}
