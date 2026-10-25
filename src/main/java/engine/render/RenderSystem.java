package engine.render;

import java.util.ArrayList;
import java.util.List;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.fixedfunc.GLMatrixFunc;
import com.jogamp.opengl.glu.GLU;

import engine.ecs.Entity;
import engine.ecs.components.Camera;
import engine.ecs.components.IComponent;
import engine.render.interfaces.IDrawable;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.val;

@Data
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RenderSystem implements GLEventListener {
  final GLU glu = new GLU();
  Camera camera;
  List<Entity> drawingList = new ArrayList<>();

  @Override
  public void display(GLAutoDrawable drawable) {
    val gl = drawable.getGL().getGL2();
    gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
    gl.glLoadIdentity(); // Reset The View
    this.camera.getWorldToViewMatrix(this.glu);
    for (val entity : this.drawingList) {
      for (val component : entity.getComponents()) {
        if (component instanceof IDrawable) {
          gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
          gl.glPushMatrix(); // remember current matrix
          ((IDrawable) component).draw(gl);
          gl.glPopMatrix(); // restore matrix
        }
      }
    }
    gl.glFlush();
  }

  @Override
  public void dispose(GLAutoDrawable drawable) {
  }

  @Override
  public void init(GLAutoDrawable drawable) {
    val gl = drawable.getGL().getGL2();
    gl.glShadeModel(GL2.GL_SMOOTH);
    gl.glClearColor(1f, 1f, 1f, 0f);
    gl.glClearDepth(1.0f);
    gl.glEnable(GL2.GL_DEPTH_TEST);
    gl.glDepthFunc(GL2.GL_LEQUAL);
    gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
    gl.glEnable(GL2.GL_TEXTURE_2D);
  }

  @Override
  public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
    val gl = drawable.getGL().getGL2();
    if (height <= 0) {
      height = 1;
    }
    val h = (float) width / (float) height;
    gl.glViewport(0, 0, width, height);
    gl.glMatrixMode(GL2.GL_PROJECTION);
    gl.glLoadIdentity();
    this.glu.gluPerspective(45.0f, h, .50, 20.0);
    gl.glMatrixMode(GL2.GL_MODELVIEW);
    gl.glLoadIdentity();
  }

}
