package test.messageing;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DummyMessageReciver implements IMessageReciver {

  int cnt;

  @Override
  public void reciveMessage(MessageObject message) {
    log.info("DummyMessageReciver: {}", cnt);
    log.info("sender: {}", message.getSender());
    log.info("transportedObject: {}", message.getTransportedObject());
    log.info("reciverId: {}", message.getReciverId());
  }
}
