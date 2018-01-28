package engine.ecs.components;

import com.jogamp.opengl.glu.GLU;

import engine.geometry.Point;

public class Camera extends AbstractComponent {

    private Point offset;
    private Point viewDirection;
    private final Point UP = new Point(0f, 1f, 0f);

    public Camera() {
        this.offset = new Point(0f, 0f, 0f);
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

}
