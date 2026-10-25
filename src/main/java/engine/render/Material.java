package engine.render;

import org.joml.Vector4f;

import com.jogamp.opengl.util.texture.Texture;

/** Surface description. A {@code null} texture renders the plain colour. */
public class Material {
  public Texture texture;
  public final Vector4f color = new Vector4f(1f, 1f, 1f, 1f);
  public float specular = 0.3f;
  public float shininess = 32f;
  /** Skip lighting and output texture * colour directly. */
  public boolean unlit;
  public float pointSize = 6f;

  public Material() {
  }

  public Material(Texture texture) {
    this.texture = texture;
  }

  public Material color(float r, float g, float b, float a) {
    this.color.set(r, g, b, a);
    return this;
  }

  public Material unlit() {
    this.unlit = true;
    return this;
  }
}
