package engine.render;

import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.ecs.AbstractComponent;
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
        gl.glBindTexture(GL2.GL_TEXTURE_2D, this.texture.getTextureObject(gl));
        this.objectLoader.drawModel(gl);
    }

    public ObjectRendering(String objectPath, String texturePath) { // "objects/capsule/capsule0.jpg"
                                                                    // "objects/capsule/capsule.obj"
        this.objectLoader = new ObjectLoader(objectPath);
        ClassLoader loader = CubeRendering.class.getClassLoader();
        this.textureFile = new File(loader.getResource(texturePath).getPath());
    }

}
