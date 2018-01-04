package engine.systems;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageService;

public class InputSystem implements KeyListener, MouseListener, MouseMotionListener {
    private final IMessageService service;

    public InputSystem(IMessageService service) {
        this.service = service;
    }

    private void sendObject(Object toSend) {
        service.send(new MessageObject(this, null, toSend));
    }

    @Override
    public void keyPressed(KeyEvent event) {
        sendObject(event);
    }

    @Override
    public void keyReleased(KeyEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseClicked(MouseEvent event) {
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
    public void mousePressed(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void keyTyped(KeyEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        sendObject(event);
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        sendObject(event);
    }
}
