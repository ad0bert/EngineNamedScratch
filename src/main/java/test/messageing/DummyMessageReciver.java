package test.messageing;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;

public class DummyMessageReciver implements IMessageReciver {

    private final int cnt;

    public DummyMessageReciver(int cnt) {
        this.cnt = cnt;
    }

    @Override
    public void reciveMessage(MessageObject message) {
        System.out.println("DummyMessageReciver: " + cnt);
        System.out.println(message.getSender());
        System.out.println(message.getTransportedObject());
        System.out.println(message.getReciverId());
    }
}
