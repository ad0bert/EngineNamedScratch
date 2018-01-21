package engine.render;

import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.fixedfunc.GLMatrixFunc;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.ecs.AbstractComponent;
import engine.ecs.Entity;
import engine.render.interfaces.IDrawable;
import engine.util.ObjectLoader;

public class ObjectRendering extends AbstractComponent implements IDrawable {

    private ObjectLoader objectLoader;
    private File textureFile;
    private Texture texture;

    @Override
    public void draw(GL2 gl) {
        if (!this.isActive()) {
            return;
        }
        if (this.texture == null) {
            try {
                this.texture = TextureIO.newTexture(this.textureFile, true);
            } catch (GLException | IOException e) {
                e.printStackTrace();
            }

        }
        gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
        gl.glPushMatrix(); // remember current matrix
        gl.glTranslatef(this.getEntity().getPosition().getX(), this.getEntity().getPosition().getX(),
                this.getEntity().getPosition().getX());
        gl.glBindTexture(GL2.GL_TEXTURE_2D, this.texture.getTextureObject(gl));
        this.objectLoader.drawModel(gl);
        gl.glPopMatrix(); // restore matrix
    }

    public ObjectRendering(String objectPath, String texturePath, Entity entity) {
        super(entity);
        this.objectLoader = new ObjectLoader(objectPath);
        ClassLoader loader = CubeRendering.class.getClassLoader();
        this.textureFile = new File(loader.getResource(texturePath).getPath());
    }

}
