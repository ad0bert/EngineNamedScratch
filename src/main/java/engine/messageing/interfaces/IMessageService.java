package engine.messageing.interfaces;

import engine.messageing.MessageObject;

public interface IMessageService {
    public void send(MessageObject messageObject);
    public boolean register(IMessageReciver toRegister, Integer id);
    public boolean unregister(IMessageReciver toDeRegister, Integer from);
}
