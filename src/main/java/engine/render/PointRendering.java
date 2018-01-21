package engine.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;

import engine.ecs.AbstractComponent;
import engine.ecs.Entity;
import engine.render.interfaces.IDrawable;

public class PointRendering extends AbstractComponent implements IDrawable {
    public PointRendering(Entity entity) {
        super(entity);
    }

    @Override
    public void draw(GL2 gl) {
        if (!isActive()) {
            return;
        }
        gl.glPointSize(10f);
        gl.glBegin(GL.GL_POINTS);
        for (int i = 0; i < 10; ++i) {
            // gl.glColor3d(.5f, .7f, .1f);
            gl.glVertex3f(this.getEntity().getPosition().getX() + i, this.getEntity().getPosition().getY() + i,
                    this.getEntity().getPosition().getZ() + i);
        }
        gl.glEnd();
    }

}
