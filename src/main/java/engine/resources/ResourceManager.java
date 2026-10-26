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
    final var cached = this.textures.get(path);
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
    final var m = this.backend.createMesh(data);
    this.ownedMeshes.add(m);
    return m;
  }

  /** An OBJ with its MTL materials: one part per material, textures resolved relative to the OBJ. */
  public Model model(String objPath) {
    final var cached = this.models.get(objPath);
    if (cached != null) {
      return cached;
    }
    final var dir = objPath.substring(0, objPath.lastIndexOf('/') + 1);
    try (InputStream in = open(objPath)) {
      final var obj = ObjLoader.parse(in, objPath, true);
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
        final var mesh = this.backend.createMesh(p.mesh());
        this.ownedMeshes.add(mesh);
        parts.add(new Model.Part(mesh, toMaterial(mtl.get(p.material()), dir)));
      }
      final var model = new Model(parts);
      this.models.put(objPath, model);
      return model;
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Could not load model " + objPath, e);
    }
  }

  private Material toMaterial(MtlLoader.MtlMaterial m, String dir) {
    final var out = new Material();
    if (m == null) {
      return out;
    }
    out.color(m.kd()[0], m.kd()[1], m.kd()[2], m.alpha());
    out.specular = m.specular();
    out.shininess = m.shininess();
    if (m.alphaMap() != null) {
      out.texture = withAlpha(m.diffuseMap() != null ? dir + m.diffuseMap() : null, dir + m.alphaMap());
      if (m.alpha() >= 1f) {
        out.alphaCutoff = 0.5f; // a mask on an opaque material: cut-out, so it stays sorted and casts shadows
      }
    } else if (m.diffuseMap() != null) {
      out.texture = texture(dir + m.diffuseMap());
    }
    if (m.normalMap() != null) {
      out.normalMap = normalMap(dir + m.normalMap(), m.normalScale());
      out.normalScale = m.normalScale();
    }
    if (m.specularMap() != null) {
      out.specularMap = texture(dir + m.specularMap());
    }
    out.emissive.set(m.ke()[0], m.ke()[1], m.ke()[2]);
    if (m.emissiveMap() != null) {
      out.emissiveMap = texture(dir + m.emissiveMap());
      if (out.emissive.lengthSquared() == 0f) {
        out.emissive.set(1f); // the map alone decides (exporters often write Ke 0 next to a map_Ke)
      }
    }
    if (m.heightMap() != null) {
      out.heightMap = texture(dir + m.heightMap());
    }
    return out;
  }

  /**
   * A normal map, or a grey height (bump) map converted into one: MTL files use the same statements for both, so
   * the pixels decide. The conversion is cached under its own key.
   */
  private GpuTexture normalMap(String path, float bumpScale) {
    final var key = path + "|normals";
    final var cached = this.textures.get(key);
    if (cached != null) {
      return cached;
    }
    final var image = image(path);
    if (image == null) {
      return white();
    }
    final var normals = NormalMaps.looksLikeHeightMap(image)
        ? NormalMaps.fromHeights(NormalMaps.heights(image), image.width(), image.height(), 3f * bumpScale)
        : image;
    return texture(key, normals);
  }

  /** The diffuse texture (or white) with its alpha replaced by the brightness of a mask image (map_d). */
  private GpuTexture withAlpha(String diffusePath, String maskPath) {
    final var key = diffusePath + "|alpha=" + maskPath;
    final var cached = this.textures.get(key);
    if (cached != null) {
      return cached;
    }
    final var mask = image(maskPath);
    final var base = diffusePath != null ? image(diffusePath) : null;
    if (mask == null) {
      return base != null ? texture(diffusePath) : white();
    }
    final int w = base != null ? base.width() : mask.width();
    final int h = base != null ? base.height() : mask.height();
    final byte[] rgba = base != null ? base.rgba().clone() : new byte[w * h * 4];
    for (int y = 0; y < h; y++) {
      final int my = y * mask.height() / h;
      for (int x = 0; x < w; x++) {
        final int mo = (my * mask.width() + x * mask.width() / w) * 4;
        final int o = (y * w + x) * 4;
        if (base == null) {
          rgba[o] = rgba[o + 1] = rgba[o + 2] = (byte) 255;
        }
        final byte[] mp = mask.rgba();
        rgba[o + 3] = (byte) (((mp[mo] & 0xFF) + (mp[mo + 1] & 0xFF) + (mp[mo + 2] & 0xFF)) / 3);
      }
    }
    return texture(key, new ImageData(w, h, rgba));
  }

  /** Decoded pixels of a classpath image, or null (with a logged error). */
  private static ImageData image(String path) {
    try (InputStream in = open(path)) {
      return ImageLoader.load(in, path);
    } catch (IOException | RuntimeException e) {
      log.error("Could not load image {}", path, e);
      return null;
    }
  }

  private static InputStream open(String path) throws IOException {
    final var in = ResourceManager.class.getClassLoader().getResourceAsStream(path);
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
