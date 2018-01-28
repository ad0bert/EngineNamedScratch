package engine.render;

import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.ecs.components.AbstractComponent;
import engine.render.interfaces.IDrawable;

public class SpriteRenderer extends AbstractComponent implements IDrawable {
    private Texture texture;
    private File textureFile;

    @Override
    public void draw(final GL2 gl) {
        if (!this.isActive()) {
            return;
        }
        if (this.texture == null) {
            try {
                this.texture = TextureIO.newTexture(this.textureFile, true);
            } catch (GLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION); // reset the projection
        // // matrix
        // gl.glLoadIdentity();
        // gl.glOrtho(0, 0, 0, 0, 0, 1);// change to orthographic projection
        // gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);// reset the modelview
        // matrix
        // gl.glLoadIdentity();
        // gl.glDisable(GL.GL_DEPTH_TEST);// disable depth test
        // gl.glBindTexture(GL2.GL_TEXTURE_2D,
        // this.texture.getTextureObject(gl));
        // gl.glBegin(GL2.GL_QUADS);
        // gl.glTexCoord2d(0, 0);
        // gl.glVertex2d(-.05, -.05);
        // gl.glTexCoord2d(1, 0);
        // gl.glVertex2d(.05, -.05);
        // gl.glTexCoord2d(1, 1);
        // gl.glVertex2d(.05, .05);
        // gl.glTexCoord2d(0, 1);
        // gl.glVertex2d(-.05, .05);
        // gl.glEnd();
    }

    public SpriteRenderer(String texturePath) {
        super();
        ClassLoader loader = SpriteRenderer.class.getClassLoader();
        this.textureFile = new File(loader.getResource(texturePath).getPath());
    }

}
