package engine.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.gl2.GLUT;

import engine.geometry.Point;

public class PointRendering implements GLEventListener {
    private GLU glu;
    private GLUT glut;
    private final Point point = new Point(1f, 1f, 1f);
    private float xrot, yrot, zrot;

    @Override
    public void display(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        //
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
        gl.glColor3f(1.0f, 1.0f, 1.0f);
        gl.glPushMatrix();
        gl.glLoadIdentity(); // Reset The View
        gl.glTranslatef(0f, 0f, -15.0f);
        gl.glRotatef(xrot, 1.0f, 1.0f, 1.0f);
        gl.glRotatef(yrot, 0.0f, 1.0f, 0.0f);
        gl.glRotatef(zrot, 0.0f, 0.0f, 1.0f);
        gl.glPointSize(10f);
        gl.glBegin(GL.GL_POINTS);
        for (int i = 0; i < 10; ++i) {
            gl.glColor3d(.5f, .7f, .1f);
            gl.glVertex3f(point.getX() + i, point.getY() + i, point.getZ() + i);
        }
        gl.glEnd();
        glut.glutSolidSphere(1.0f, 10, 10);
        gl.glPopMatrix();
        gl.glFlush();
        xrot += .1f;
        yrot += .1f;
        zrot += .1f;
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        glu = new GLU();
        glut = new GLUT();
        //
        gl.glShadeModel(GL2.GL_SMOOTH);
        gl.glEnable(GL2.GL_DEPTH_TEST);
        gl.glDepthFunc(GL2.GL_LEQUAL);
        gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
        // Enable lighting
        gl.glEnable(GL2.GL_LIGHTING);
        gl.glEnable(GL2.GL_LIGHT0);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glViewport(0, 0, width, height);
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glLoadIdentity();
        glu.gluPerspective(60.0, (float) width / (float) height, 1.0, 20.0);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
    }
}
