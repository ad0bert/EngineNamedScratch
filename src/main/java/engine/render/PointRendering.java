package engine.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;

import engine.ecs.components.AbstractComponent;
import engine.render.interfaces.IDrawable;

public class PointRendering extends AbstractComponent implements IDrawable {

    @Override
    public void draw(GL2 gl) {
        if (!isActive()) {
            return;
        }
        applyRotation(gl);
        gl.glPointSize(10f);
        gl.glBegin(GL.GL_POINTS);
        for (int i = 0; i < 10; ++i) {
            // gl.glColor3d(.5f, .7f, .1f);
            gl.glVertex3f(this.getEntity().getPosition().getX() + i, this.getEntity().getPosition().getY() + i,
                    this.getEntity().getPosition().getZ() + i);
        }
        gl.glEnd();
    }

    private void applyRotation(final GL2 gl) {
        gl.glRotatef(this.getEntity().getRotation().getX(), 1, 0, 0);
        gl.glRotatef(this.getEntity().getRotation().getY(), 0, 1, 0);
        gl.glRotatef(this.getEntity().getRotation().getZ(), 0, 0, 1);
    }
}
