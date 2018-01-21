package engine.ecs.components;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.opengl.glu.GLU;

import engine.ecs.AbstractComponent;
import engine.ecs.Entity;
import engine.geometry.Point;
import engine.messageing.MessageObject;
import engine.messageing.interfaces.IMessageReciver;

public class Camera extends AbstractComponent implements IMessageReciver {

    private Point offset;
    private Point viewDirection;
    private final Point UP = new Point(0f, 1f, 0f);
    private final Point oldPosition;

    public Camera(Entity entity) {
        super(entity);
        this.offset = new Point(0f, 0f, 0f);
        this.oldPosition = new Point(0, 0, 0);
        this.viewDirection = new Point(0f, 0f, -1f);
    }

    public void getWorldToViewMatrix(GLU glu) {
        glu.gluLookAt(this.getEntity().getPosition().getX(), this.getEntity().getPosition().getY(),
                this.getEntity().getPosition().getZ(),
                this.getEntity().getPosition().getX() + this.viewDirection.getX(),
                this.getEntity().getPosition().getY() + this.viewDirection.getY(),
                this.getEntity().getPosition().getZ() + this.viewDirection.getZ(), this.UP.getX(), this.UP.getY(),
                this.UP.getZ());
    }

    public Point getOffset() {
        return this.offset;
    }

    public void setOffset(Point offset) {
        this.offset = offset;
    }

    public Point getViewDirection() {
        return this.viewDirection;
    }

    public void setViewDirection(Point viewDirection) {
        this.viewDirection = viewDirection;
    }

    @Override
    public void reciveMessage(MessageObject message) {
        if (message.getTransportedObject() instanceof MouseEvent) {
            MouseEvent event = (MouseEvent) message.getTransportedObject();

            float x = this.oldPosition.getX() < event.getX() ? 0.01f : -0.01f;
            float y = this.oldPosition.getY() < event.getY() ? -0.01f : 0.01f;

            this.viewDirection.moveXY(x, y);
            this.oldPosition.setXYZ(event.getX(), event.getY(), 0);
        } else if (message.getTransportedObject() instanceof KeyEvent) {
            KeyEvent event = (KeyEvent) message.getTransportedObject();
            switch (event.getKeyChar()) {
            case 'w':
            case 'W':
                this.getEntity().getPosition().moveZ(-0.1f);
                break;
            case 'a':
            case 'A':
                this.getEntity().getPosition().moveX(-0.1f);
                break;
            case 's':
            case 'S':
                this.getEntity().getPosition().moveZ(0.1f);
                break;
            case 'd':
            case 'D':
                this.getEntity().getPosition().moveX(0.1f);
                break;
            }
        }
    }
}
