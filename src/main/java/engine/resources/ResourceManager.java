package engine.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureData;
import com.jogamp.opengl.util.texture.TextureIO;

import engine.render.Mesh;
import engine.render.MeshData;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches GPU resources from the classpath. All calls must happen on the GL thread. Textures are read from
 * streams so loading also works from inside a JAR.
 */
@Slf4j
public class ResourceManager {
  private final GL3 gl;
  private final Map<String, Texture> textures = new HashMap<>();
  private final Map<String, Mesh> meshes = new HashMap<>();
  private final List<Mesh> ownedMeshes = new ArrayList<>();
  private Texture white;

  public ResourceManager(GL3 gl) {
    this.gl = gl;
  }

  /** A 1x1 white texture, used when a material has no texture. */
  public Texture white() {
    if (this.white == null) {
      final ByteBuffer pixel = ByteBuffer.allocateDirect(4);
      pixel.put(new byte[] { (byte) 255, (byte) 255, (byte) 255, (byte) 255 }).flip();
      final TextureData data = new TextureData(this.gl.getGLProfile(), GL3.GL_RGBA, 1, 1, 0, GL3.GL_RGBA,
          GL3.GL_UNSIGNED_BYTE, false, false, false, pixel, null);
      this.white = TextureIO.newTexture(this.gl, data);
    }
    return this.white;
  }

  /** Returns the cached texture, or the white texture (with a logged error) if loading fails. */
  public Texture texture(String path) {
    return this.textures.computeIfAbsent(path, p -> {
      try (InputStream in = open(p)) {
        // GL-bound overload: uploads the pixels immediately
        final Texture t = TextureIO.newTexture(this.gl,
            TextureIO.newTextureData(this.gl.getGLProfile(), in, true, extension(p)));
        t.bind(this.gl);
        this.gl.glGenerateMipmap(GL3.GL_TEXTURE_2D);
        t.setTexParameteri(this.gl, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
        t.setTexParameteri(this.gl, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
        t.setTexParameteri(this.gl, GL3.GL_TEXTURE_WRAP_S, GL3.GL_REPEAT);
        t.setTexParameteri(this.gl, GL3.GL_TEXTURE_WRAP_T, GL3.GL_REPEAT);
        return t;
      } catch (IOException | RuntimeException e) {
        log.error("Could not load texture {}", p, e);
        return white();
      }
    });
  }

  public Mesh mesh(String objPath) {
    return this.meshes.computeIfAbsent(objPath, p -> {
      try (InputStream in = open(p)) {
        return new Mesh(this.gl, ObjLoader.load(in, p));
      } catch (IOException | RuntimeException e) {
        throw new IllegalStateException("Could not load model " + p, e);
      }
    });
  }

  /** Uploads generated mesh data; the manager disposes it on shutdown. */
  public Mesh mesh(MeshData data) {
    final Mesh m = new Mesh(this.gl, data);
    this.ownedMeshes.add(m);
    return m;
  }

  private static InputStream open(String path) throws IOException {
    final InputStream in = ResourceManager.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IOException("Resource not found: " + path);
    }
    return in;
  }

  private static String extension(String path) {
    final int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
  }

  public void dispose() {
    this.textures.values().stream().filter(t -> t != this.white).forEach(t -> t.destroy(this.gl));
    this.meshes.values().forEach(m -> m.dispose(this.gl));
    this.ownedMeshes.forEach(m -> m.dispose(this.gl));
    if (this.white != null) {
      this.white.destroy(this.gl);
    }
    this.textures.clear();
    this.meshes.clear();
    this.ownedMeshes.clear();
    this.white = null;
  }
}
