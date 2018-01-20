package engine.render;

import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.ecs.AbstractComponent;
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
    }

    public CubeRendering(String texturePath, float size) {// "textures/cubeFace.png"
        this.size = size;
        ClassLoader loader = CubeRendering.class.getClassLoader();
        this.textureFile = new File(loader.getResource(texturePath).getPath());
    }

}
