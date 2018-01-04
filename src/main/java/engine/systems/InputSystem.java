package engine.systems;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageService;

public class InputSystem implements KeyListener, MouseListener {
    private final IMessageService service;

    public InputSystem(IMessageService service) {
        this.service = service;
    }

    private void sendObject(Object toSend) {
        this.service.send(new MessageObject(this, null, toSend));
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseEntered(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseExited(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mousePressed(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseWheelMoved(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void keyPressed(KeyEvent event) {
        sendObject(event);
    }

    @Override
    public void keyReleased(KeyEvent event) {
        sendObject(event);
    }

}
