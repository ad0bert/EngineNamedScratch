package engine.render;

import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.ecs.components.AbstractComponent;
import engine.render.interfaces.IDrawable;

public class CubeRendering extends AbstractComponent implements IDrawable {
    private Texture texture;
    private File textureFile;
    private float size;

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

        applyRotation(gl);

        gl.glBindTexture(GL2.GL_TEXTURE_2D, this.texture.getTextureObject(gl));
        gl.glBegin(GL2.GL_QUADS);
        // Front Face
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(-this.size, -this.size, this.size);
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(this.size, -this.size, this.size);
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(this.size, this.size, this.size);
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(-this.size, this.size, this.size);
        // Back Face
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(-this.size, -this.size, -this.size);
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(-this.size, this.size, -this.size);
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(this.size, this.size, -this.size);
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(this.size, -this.size, -this.size);
        // Top Face
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(-this.size, this.size, -this.size);
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(-this.size, this.size, this.size);
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(this.size, this.size, this.size);
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(this.size, this.size, -this.size);
        // Bottom Face
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(-this.size, -this.size, -this.size);
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(this.size, -this.size, -this.size);
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(this.size, -this.size, this.size);
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(-this.size, -this.size, this.size);
        // Right face
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(this.size, -this.size, -this.size);
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(this.size, this.size, -this.size);
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(this.size, this.size, this.size);
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(this.size, -this.size, this.size);
        // Left Face
        gl.glTexCoord2f(0.0f, 0.0f);
        gl.glVertex3f(-this.size, -this.size, -this.size);
        gl.glTexCoord2f(this.size, 0.0f);
        gl.glVertex3f(-this.size, -this.size, this.size);
        gl.glTexCoord2f(this.size, this.size);
        gl.glVertex3f(-this.size, this.size, this.size);
        gl.glTexCoord2f(0.0f, this.size);
        gl.glVertex3f(-this.size, this.size, -this.size);
        gl.glEnd();

        this.getEntity().getRotation().moveX(0.1f);
        this.getEntity().getRotation().moveY(0.1f);
        this.getEntity().getRotation().moveZ(0.1f);
    }

    private void applyRotation(final GL2 gl) {
        gl.glRotatef(this.getEntity().getRotation().getX(), 1, 0, 0);
        gl.glRotatef(this.getEntity().getRotation().getY(), 0, 1, 0);
        gl.glRotatef(this.getEntity().getRotation().getZ(), 0, 0, 1);
    }

    public CubeRendering(String texturePath, float size) {
        super();
        this.size = size;
        ClassLoader loader = CubeRendering.class.getClassLoader();
        this.textureFile = new File(loader.getResource(texturePath).getPath());
    }

}
