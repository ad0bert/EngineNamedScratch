package engine.systems;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;

import engine.messageing.MessageObject;
import engine.messageing.MessageQueueNames;
import engine.messageing.interfaces.IMessageService;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InputSystem implements KeyListener, MouseListener {
  IMessageService service;

  private void sendObject(Object toSend, Integer queue) {
    this.service.send(MessageObject.builder().sender(this).queueId(queue).transportedObject(toSend).build());
  }

  @Override
  public void mouseClicked(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseDragged(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseEntered(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseExited(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseMoved(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mousePressed(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseReleased(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void mouseWheelMoved(MouseEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("MOUSE"));
  }

  @Override
  public void keyPressed(KeyEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("KEYBOARD"));
  }

  @Override
  public void keyReleased(KeyEvent event) {
    sendObject(event, MessageQueueNames.QUEUES.get("KEYBOARD"));
  }

}
