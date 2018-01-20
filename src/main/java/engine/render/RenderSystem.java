package engine.render;

import java.awt.DisplayMode;
import java.util.ArrayList;
import java.util.List;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.glu.GLU;

import engine.ecs.Entity;
import engine.ecs.IComponent;
import engine.render.interfaces.IDrawable;

public class RenderSystem implements GLEventListener {
    public static DisplayMode dm, dm_old;
    private final GLU glu = new GLU();
    private float xrot, yrot, zrot;

    private List<Entity> drawingList = new ArrayList<Entity>();

    @Override
    public void display(GLAutoDrawable drawable) {
        final GL2 gl = drawable.getGL().getGL2();
        gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
        gl.glLoadIdentity(); // Reset The View
        gl.glTranslatef(0f, 0f, -5.0f);
        gl.glRotatef(this.xrot, 1.0f, 1.0f, 1.0f);
        gl.glRotatef(this.yrot, 0.0f, 1.0f, 0.0f);
        gl.glRotatef(this.zrot, 0.0f, 0.0f, 1.0f);
        for (Entity entity : this.drawingList) {
            for (IComponent component : entity.getComponents()) {
                if (component instanceof IDrawable) {
                    ((IDrawable) component).draw(gl);
                }
            }
        }
        gl.glFlush();
        // change the speeds here
        this.xrot += .1f;
        this.yrot += .1f;
        this.zrot += .1f;
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        final GL2 gl = drawable.getGL().getGL2();
        gl.glShadeModel(GL2.GL_SMOOTH);
        gl.glClearColor(0f, 0f, 0f, 0f);
        gl.glClearDepth(1.0f);
        gl.glEnable(GL2.GL_DEPTH_TEST);
        gl.glDepthFunc(GL2.GL_LEQUAL);
        gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
        gl.glEnable(GL2.GL_TEXTURE_2D);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        final GL2 gl = drawable.getGL().getGL2();
        if (height <= 0) {
            height = 1;
        }
        final float h = (float) width / (float) height;
        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        this.glu.gluPerspective(45.0f, h, 1.0, 20.0);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glLoadIdentity();
    }

    public List<Entity> getDrawingList() {
        return this.drawingList;
    }

    public void setDrawingList(List<Entity> drawingList) {
        this.drawingList = drawingList;
    }

}
