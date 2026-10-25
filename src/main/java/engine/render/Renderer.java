package engine.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;

import engine.ecs.Scene;

/**
 * Forward renderer: one shader, one directional light, per-entity draw calls. All GL access of the engine is confined
 * to the {@code engine.render} and {@code engine.resources} packages so another backend could replace them.
 */
public class Renderer {
  private ShaderProgram shader;
  private Texture whiteTexture;
  private final Light defaultLight = new Light();

  private final Matrix4f view = new Matrix4f();
  private final Matrix4f proj = new Matrix4f();
  private final Matrix4f viewProj = new Matrix4f();
  private final Matrix4f model = new Matrix4f();
  private final Matrix3f normalMatrix = new Matrix3f();
  private final Vector3f cameraPos = new Vector3f();
  private final Vector3f toLight = new Vector3f();

  public void init(GL3 gl, Texture whiteTexture) {
    this.whiteTexture = whiteTexture;
    this.shader = new ShaderProgram(gl, "shaders/basic.vert", "shaders/basic.frag");
    gl.glClearColor(0.53f, 0.7f, 0.9f, 1f);
    gl.glClearDepth(1.0);
    gl.glEnable(GL3.GL_DEPTH_TEST);
    gl.glDepthFunc(GL3.GL_LEQUAL);
    gl.glEnable(GL3.GL_PROGRAM_POINT_SIZE);
  }

  public void resize(GL3 gl, int width, int height) {
    gl.glViewport(0, 0, width, Math.max(height, 1));
  }

  public void render(GL3 gl, Scene scene, int width, int height) {
    gl.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);
    final var camera = scene.activeCamera();
    if (camera.isEmpty()) {
      return;
    }
    final Camera cam = camera.get();
    final float aspect = (float) width / Math.max(height, 1);
    cam.viewMatrix(this.view);
    cam.projectionMatrix(aspect, this.proj);
    this.proj.mul(this.view, this.viewProj);
    cam.entity().transform().worldPosition(this.cameraPos);

    final Light light = scene.components(Light.class).stream().findFirst().orElse(this.defaultLight);
    light.direction(this.toLight).negate();

    this.shader.use(gl);
    this.shader.set(gl, "uViewProj", this.viewProj);
    this.shader.set(gl, "uCameraPos", this.cameraPos);
    this.shader.set(gl, "uToLight", this.toLight);
    this.shader.set(gl, "uLightColor", new Vector3f(light.color).mul(light.intensity));
    this.shader.set(gl, "uAmbient", light.ambient);
    this.shader.set(gl, "uTexture", 0);

    for (MeshRenderer r : scene.components(MeshRenderer.class)) {
      r.entity().transform().worldMatrix(this.model);
      this.model.normal(this.normalMatrix);
      final Material m = r.material;
      final Texture tex = m.texture != null ? m.texture : this.whiteTexture;
      gl.glActiveTexture(GL3.GL_TEXTURE0);
      tex.bind(gl);
      this.shader.set(gl, "uModel", this.model);
      this.shader.set(gl, "uNormalMatrix", this.normalMatrix);
      this.shader.set(gl, "uColor", m.color);
      this.shader.set(gl, "uSpecular", m.specular);
      this.shader.set(gl, "uShininess", m.shininess);
      this.shader.set(gl, "uUnlit", m.unlit ? 1 : 0);
      this.shader.set(gl, "uFlipV", tex.getMustFlipVertically() ? 1 : 0);
      this.shader.set(gl, "uPointSize", m.pointSize);
      r.mesh.draw(gl);
    }
  }

  public void dispose(GL3 gl) {
    if (this.shader != null) {
      this.shader.dispose(gl);
    }
  }
}
