package engine.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.ImageData;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.RenderBackend;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches GPU resources from the classpath (so it also works from inside a JAR). Must be used on the
 * graphics thread. Paths are classpath-relative, e.g. {@code objects/box.obj}.
 */
@Slf4j
public class ResourceManager {
  private final RenderBackend backend;
  private final Map<String, GpuTexture> textures = new HashMap<>();
  private final Map<String, GpuMesh> meshes = new HashMap<>();
  private final Map<String, Model> models = new HashMap<>();
  private final List<GpuMesh> ownedMeshes = new ArrayList<>();

  public ResourceManager(RenderBackend backend) {
    this.backend = backend;
  }

  public GpuTexture white() {
    return this.backend.whiteTexture();
  }

  /** Returns the cached texture, or the white texture (with a logged error) if loading fails. */
  public GpuTexture texture(String path) {
    final GpuTexture cached = this.textures.get(path);
    if (cached != null) {
      return cached;
    }
    GpuTexture t;
    try (InputStream in = open(path)) {
      t = this.backend.createTexture(ImageLoader.load(in, path));
      this.textures.put(path, t);
    } catch (IOException | RuntimeException e) {
      log.error("Could not load texture {}", path, e);
      t = white();
    }
    return t;
  }

  /**
   * Uploads generated pixels (procedural textures, normal maps from {@link NormalMaps}) under a unique key; asking
   * again with the same key returns the cached texture. The manager disposes it on shutdown.
   */
  public GpuTexture texture(String key, ImageData image) {
    return this.textures.computeIfAbsent(key, k -> this.backend.createTexture(image));
  }

  /** A single mesh from an OBJ file; materials are ignored. */
  public GpuMesh mesh(String objPath) {
    return this.meshes.computeIfAbsent(objPath, p -> {
      try (InputStream in = open(p)) {
        return this.backend.createMesh(ObjLoader.load(in, p));
      } catch (IOException | RuntimeException e) {
        throw new IllegalStateException("Could not load model " + p, e);
      }
    });
  }

  /** Uploads generated mesh data; the manager disposes it on shutdown. */
  public GpuMesh mesh(MeshData data) {
    final GpuMesh m = this.backend.createMesh(data);
    this.ownedMeshes.add(m);
    return m;
  }

  /** An OBJ with its MTL materials: one part per material, textures resolved relative to the OBJ. */
  public Model model(String objPath) {
    final Model cached = this.models.get(objPath);
    if (cached != null) {
      return cached;
    }
    final String dir = objPath.substring(0, objPath.lastIndexOf('/') + 1);
    try (InputStream in = open(objPath)) {
      final ObjLoader.ObjModel obj = ObjLoader.parse(in, objPath, true);
      Map<String, MtlLoader.MtlMaterial> mtl = Map.of();
      if (obj.mtllib() != null) {
        try (InputStream mi = open(dir + obj.mtllib())) {
          mtl = MtlLoader.parse(mi);
        } catch (IOException e) {
          log.warn("Could not read material library {} of {}: {}", obj.mtllib(), objPath, e.getMessage());
        }
      }
      final List<Model.Part> parts = new ArrayList<>();
      for (ObjLoader.Part p : obj.parts()) {
        final GpuMesh mesh = this.backend.createMesh(p.mesh());
        this.ownedMeshes.add(mesh);
        parts.add(new Model.Part(mesh, toMaterial(mtl.get(p.material()), dir)));
      }
      final Model model = new Model(parts);
      this.models.put(objPath, model);
      return model;
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Could not load model " + objPath, e);
    }
  }

  private Material toMaterial(MtlLoader.MtlMaterial m, String dir) {
    final Material out = new Material();
    if (m == null) {
      return out;
    }
    out.color(m.kd()[0], m.kd()[1], m.kd()[2], m.alpha());
    out.specular = m.specular();
    out.shininess = m.shininess();
    if (m.diffuseMap() != null) {
      out.texture = texture(dir + m.diffuseMap());
    }
    if (m.normalMap() != null) {
      out.normalMap = texture(dir + m.normalMap());
      out.normalScale = m.normalScale();
    }
    return out;
  }

  private static InputStream open(String path) throws IOException {
    final InputStream in = ResourceManager.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      throw new IOException("Resource not found: " + path);
    }
    return in;
  }

  public void dispose() {
    this.textures.values().forEach(GpuTexture::dispose);
    this.meshes.values().forEach(GpuMesh::dispose);
    this.ownedMeshes.forEach(GpuMesh::dispose);
    this.textures.clear();
    this.meshes.clear();
    this.ownedMeshes.clear();
    this.models.clear();
  }
}
