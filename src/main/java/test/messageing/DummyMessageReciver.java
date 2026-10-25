package test.messageing;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DummyMessageReciver implements IMessageReciver {

  int cnt;

  @Override
  public void reciveMessage(MessageObject message) {
    System.out.println("DummyMessageReciver: " + cnt);
    System.out.println(message.getSender());
    System.out.println(message.getTransportedObject());
    System.out.println(message.getReciverId());
  }
}
