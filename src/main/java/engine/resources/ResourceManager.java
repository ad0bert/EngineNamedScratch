package engine.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import engine.audio.AudioBackend;
import engine.audio.AudioClip;
import engine.audio.AudioData;
import engine.audio.NullAudioBackend;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.ImageData;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshFactory;
import engine.render.RenderBackend;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches GPU resources and sounds from the classpath (so it also works from inside a JAR). Must be used on
 * the graphics thread. Paths are classpath-relative, e.g. {@code objects/box.obj}.
 *
 * <p>
 * Every cached resource has a key ({@link #keyOf}) from which {@code ...ByKey} gets it back, which is how scene files
 * refer to meshes, textures and sounds: a path, a model part ({@code objects/a.obj#1}), a generated shape
 * ({@code cube:0.5}, see {@link #shape}) or the key a generated resource was registered under (those can only be
 * found again while they are cached, i.e. after the game generated them).
 */
@Slf4j
public class ResourceManager {
  private final RenderBackend backend;
  private final Map<String, GpuTexture> textures = new HashMap<>();
  private final Map<String, GpuMesh> meshes = new HashMap<>();
  private final Map<String, Model> models = new HashMap<>();
  private final List<GpuMesh> ownedMeshes = new ArrayList<>();
  private final AudioBackend audio;
  private final Map<String, AudioClip> sounds = new HashMap<>();
  private final Map<Object, String> keys = new IdentityHashMap<>();
  private final Map<String, GpuMesh> keyedMeshes = new HashMap<>();

  /** Without sound: clips are created on a silent backend. */
  public ResourceManager(RenderBackend backend) {
    this(backend, new NullAudioBackend());
  }

  public ResourceManager(RenderBackend backend, AudioBackend audio) {
    this.backend = backend;
    this.audio = audio;
  }

  public GpuTexture white() {
    return this.backend.whiteTexture();
  }

  /**
   * A colour texture (sRGB: albedo, emissive); returns the cached texture, or the white texture (with a logged
   * error) if loading fails.
   */
  public GpuTexture texture(String path) {
    return load(path, true);
  }

  /** A data texture (used as stored: normal, height, specular, roughness maps); see {@link #texture(String)}. */
  public GpuTexture dataTexture(String path) {
    return load(path, false);
  }

  private GpuTexture load(String path, boolean srgb) {
    final var key = srgb ? path : path + "|linear";
    final var cached = this.textures.get(key);
    if (cached != null) {
      return cached;
    }
    GpuTexture t;
    try (InputStream in = open(path)) {
      t = this.backend.createTexture(ImageLoader.load(in, path), srgb);
      this.textures.put(key, t);
      this.keys.put(t, key);
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
    return this.textures.computeIfAbsent(key, k -> remember(this.backend.createTexture(image, true), k));
  }

  /** Like {@link #texture(String, ImageData)} for data (normal maps, height maps...), which is used as stored. */
  public GpuTexture dataTexture(String key, ImageData image) {
    return this.textures.computeIfAbsent(key, k -> remember(this.backend.createTexture(image, false), k));
  }

  /** A single mesh from an OBJ file; materials are ignored. */
  public GpuMesh mesh(String objPath) {
    return this.meshes.computeIfAbsent(objPath, p -> {
      try (InputStream in = open(p)) {
        return remember(this.backend.createMesh(ObjLoader.load(in, p)), p);
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

  /**
   * Uploads generated mesh data under a key (so scene files can refer to it); asking again with the same key returns
   * the cached mesh.
   */
  public GpuMesh mesh(String key, MeshData data) {
    final var cached = this.keyedMeshes.get(key);
    if (cached != null) {
      return cached;
    }
    final var m = mesh(data);
    this.keyedMeshes.put(key, m);
    return remember(m, key);
  }

  /**
   * A generated shape from a spec, cached: {@code cube:size}, {@code sphere:radius,segments,rings},
   * {@code quad:width,height} or {@code quad:width,height,uRepeat,vRepeat}.
   *
   * @throws IllegalArgumentException for an unknown or malformed spec
   */
  public GpuMesh shape(String spec) {
    final var cached = this.keyedMeshes.get(spec);
    if (cached != null) {
      return cached;
    }
    final int colon = spec.indexOf(':');
    if (colon < 0) {
      throw new IllegalArgumentException("not a shape spec: " + spec);
    }
    final var args = spec.substring(colon + 1).split(",");
    final float[] v = new float[args.length];
    for (int i = 0; i < args.length; i++) {
      v[i] = Float.parseFloat(args[i].trim());
    }
    final var data = switch (spec.substring(0, colon).toLowerCase(Locale.ROOT)) {
    case "cube" -> MeshFactory.cube(v[0]);
    case "sphere" -> MeshFactory.sphere(v[0], v.length > 1 ? (int) v[1] : 24, v.length > 2 ? (int) v[2] : 12);
    case "quad" -> v.length >= 4 ? MeshFactory.quad(v[0], v[1], v[2], v[3]) : MeshFactory.quad(v[0], v[1]);
    default -> throw new IllegalArgumentException("unknown shape " + spec);
    };
    return mesh(spec, data);
  }

  /** The key a cached mesh, texture or sound can be found again by, or null if it was not created through here. */
  public String keyOf(Object resource) {
    return resource == null ? null : this.keys.get(resource);
  }

  /** A mesh by its {@link #keyOf key}: OBJ path, model part, shape spec or registered key; null if unknown. */
  public GpuMesh meshByKey(String key) {
    final var cached = this.keyedMeshes.get(key);
    if (cached != null) {
      return cached;
    }
    final int hash = key.lastIndexOf('#');
    if (hash > 0) {
      final var parts = model(key.substring(0, hash)).parts();
      final int index = Integer.parseInt(key.substring(hash + 1));
      return index < parts.size() ? parts.get(index).mesh() : null;
    }
    if (key.toLowerCase(Locale.ROOT).endsWith(".obj")) {
      return mesh(key);
    }
    if (key.indexOf(':') > 0) {
      try {
        return shape(key);
      } catch (IllegalArgumentException e) {
        log.warn("Unknown mesh {}", key);
        return null;
      }
    }
    log.warn("Unknown mesh {} (generated meshes must be created before they are looked up)", key);
    return null;
  }

  /** A texture by its {@link #keyOf key}: a cached one, or a path to load (a {@code |linear} suffix: as data). */
  public GpuTexture textureByKey(String key) {
    final var cached = this.textures.get(key);
    if (cached != null) {
      return cached;
    }
    if (key.endsWith("|linear")) {
      return dataTexture(key.substring(0, key.length() - "|linear".length()));
    }
    if (key.indexOf('|') >= 0 || key.startsWith("gen:")) {
      log.warn("Texture {} is generated and not cached; using white", key);
      return white();
    }
    return texture(key);
  }

  /** A sound by its {@link #keyOf key}: a cached one, or a path to load (a {@code |stream} suffix: as music). */
  public AudioClip soundByKey(String key) {
    final var cached = this.sounds.get(key);
    if (cached != null) {
      return cached;
    }
    if (key.endsWith("|stream")) {
      return music(key.substring(0, key.length() - "|stream".length()));
    }
    if (key.startsWith("gen:")) {
      log.warn("Sound {} is generated and not cached", key);
      return null;
    }
    return sound(key);
  }

  private <T> T remember(T resource, String key) {
    this.keys.put(resource, key);
    return resource;
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
        remember(mesh, objPath + "#" + parts.size());
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
      out.specularMap = dataTexture(dir + m.specularMap());
    }
    out.emissive.set(m.ke()[0], m.ke()[1], m.ke()[2]);
    if (m.emissiveMap() != null) {
      out.emissiveMap = texture(dir + m.emissiveMap());
      if (out.emissive.lengthSquared() == 0f) {
        out.emissive.set(1f); // the map alone decides (exporters often write Ke 0 next to a map_Ke)
      }
    }
    if (m.heightMap() != null) {
      out.heightMap = dataTexture(dir + m.heightMap());
    }
    if (m.ka() != null) {
      out.ambient.set(m.ka()[0], m.ka()[1], m.ka()[2]);
    }
    out.uvScale.set(m.uvScale()[0], m.uvScale()[1]);
    out.uvOffset.set(m.uvOffset()[0], m.uvOffset()[1]);
    out.clampUv = m.clamp();
    if (!Float.isNaN(m.ior()) && m.ior() > 1f) {
      final float r = (m.ior() - 1f) / (m.ior() + 1f);
      out.reflectance = r * r; // Fresnel at normal incidence: 1.5 (glass) gives 0.04
    }
    if (m.pbr()) {
      out.pbr = true;
      out.roughness = Float.isNaN(m.roughness()) ? 1f : m.roughness();
      out.metallic = Float.isNaN(m.metallic()) ? 0f : m.metallic();
      if (m.roughnessMap() != null || m.metallicMap() != null) {
        out.metalRoughMap = metalRough(m.roughnessMap() != null ? dir + m.roughnessMap() : null,
            m.metallicMap() != null ? dir + m.metallicMap() : null);
        // the map scales the values: a map alone means the full range
        out.roughness = m.roughnessMap() != null && Float.isNaN(m.roughness()) ? 1f : out.roughness;
        out.metallic = m.metallicMap() != null && Float.isNaN(m.metallic()) ? 1f : out.metallic;
      }
    }
    return out;
  }

  /** Separate roughness and metallic images (map_Pr, map_Pm) combined into the glTF layout (green, blue). */
  private GpuTexture metalRough(String roughnessPath, String metallicPath) {
    final var key = roughnessPath + "|metalRough=" + metallicPath;
    final var cached = this.textures.get(key);
    if (cached != null) {
      return cached;
    }
    final var rough = roughnessPath != null ? image(roughnessPath) : null;
    final var metal = metallicPath != null ? image(metallicPath) : null;
    final var size = rough != null ? rough : metal;
    if (size == null) {
      return white();
    }
    final int w = size.width();
    final int h = size.height();
    final byte[] rgba = new byte[w * h * 4];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        final int o = (y * w + x) * 4;
        rgba[o] = (byte) 255;
        rgba[o + 1] = rough != null ? brightness(rough, x, y, w, h) : (byte) 255;
        rgba[o + 2] = metal != null ? brightness(metal, x, y, w, h) : (byte) 255;
        rgba[o + 3] = (byte) 255;
      }
    }
    return dataTexture(key, new ImageData(w, h, rgba));
  }

  /** Brightness of the pixel at x, y of a {@code w x h} grid, sampled from an image of any size. */
  private static byte brightness(ImageData img, int x, int y, int w, int h) {
    final int o = ((y * img.height() / h) * img.width() + x * img.width() / w) * 4;
    final byte[] p = img.rgba();
    return (byte) (((p[o] & 0xFF) + (p[o + 1] & 0xFF) + (p[o + 2] & 0xFF)) / 3);
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
    return dataTexture(key, normals);
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

  /**
   * A sound (WAV, AIFF, AU or Ogg Vorbis), decoded completely; for short effects. Returns the cached clip, or null
   * (with a logged error) if loading fails; playing null does nothing.
   */
  public AudioClip sound(String path) {
    final var cached = this.sounds.get(path);
    if (cached != null) {
      return cached;
    }
    try (InputStream in = open(path)) {
      final var clip = remember(this.audio.createClip(path, AudioLoader.load(in, path)), path);
      this.sounds.put(path, clip);
      return clip;
    } catch (IOException | RuntimeException e) {
      log.error("Could not load sound {}", path, e);
      return null;
    }
  }

  /** Uploads generated sound under a unique key; asking again with the same key returns the cached clip. */
  public AudioClip sound(String key, AudioData data) {
    return this.sounds.computeIfAbsent(key, k -> remember(this.audio.createClip(k, data), k));
  }

  /**
   * A long sound (music, ambience) that is decoded while it plays: an Ogg Vorbis file stays compressed in memory and
   * every voice playing it decodes its own stream. Other formats are decoded completely, as by {@link #sound}.
   */
  public AudioClip music(String path) {
    final var key = path + "|stream";
    final var cached = this.sounds.get(key);
    if (cached != null) {
      return cached;
    }
    try (InputStream in = open(path)) {
      final byte[] bytes = in.readAllBytes();
      final AudioClip clip;
      if (AudioLoader.isOgg(bytes)) {
        final int channels;
        final float duration;
        try (var probe = new VorbisDecoder(bytes)) {
          channels = probe.channels();
          final float d = VorbisDecoder.duration(bytes, probe.sampleRate());
          duration = Float.isNaN(d) ? Float.POSITIVE_INFINITY : d;
        }
        clip = this.audio.createStreamingClip(path, channels, duration, () -> new VorbisDecoder(bytes));
      } else {
        clip = this.audio.createClip(path, AudioLoader.decode(bytes, path));
      }
      this.sounds.put(key, remember(clip, key));
      return clip;
    } catch (IOException | RuntimeException e) {
      log.error("Could not load music {}", path, e);
      return null;
    }
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
    this.sounds.values().forEach(AudioClip::dispose);
    this.sounds.clear();
    this.textures.clear();
    this.meshes.clear();
    this.ownedMeshes.clear();
    this.models.clear();
    this.keys.clear();
    this.keyedMeshes.clear();
  }
}
