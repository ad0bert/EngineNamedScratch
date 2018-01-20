package engine.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;

import engine.ecs.AbstractComponent;
import engine.geometry.Point;
import engine.render.interfaces.IDrawable;

public class PointRendering extends AbstractComponent implements IDrawable {
    private final Point point = new Point(1f, 1f, 1f);

    @Override
    public void draw(GL2 gl) {
        if (!isActive()) {
            return;
        }
        gl.glPointSize(10f);
        gl.glBegin(GL.GL_POINTS);
        for (int i = 0; i < 10; ++i) {
            // gl.glColor3d(.5f, .7f, .1f);
            gl.glVertex3f(this.point.getX() + i, this.point.getY() + i, this.point.getZ() + i);
        }
        gl.glEnd();
    }

}
