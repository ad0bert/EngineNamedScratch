package engine.serialization;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.render.Material;
import engine.resources.Assets;
import engine.resources.ResourceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Material files and prefab files that scene files refer to by path, loaded once per {@link ResourceManager} (so every
 * scene and prefab that names a material file gets the same {@link Material}, and its renderers still batch).
 *
 * <p>
 * A material file holds the same fields as an entry of a scene file's {@code materials}, plus an optional
 * {@code "file"} naming another material file it starts from:
 *
 * <pre>
 * { "format": "engine-material", "version": 1,
 *   "color": [0.8, 0.8, 0.85, 1], "pbr": true, "metallic": 1, "roughness": 0.3,
 *   "texture": "textures/steel.png", "fragmentShader": "shaders/brushed.frag", "params": { "streaks": 40 } }
 * </pre>
 *
 * A scene file uses it with a path in place of a material ({@code "materials": ["materials/steel.json"]}: the shared
 * instance) or as an object with {@code "file"} and fields that differ ({@code {"file": "materials/steel.json",
 * "roughness": 0.8}}: a material of its own that starts from the file). Code gets the shared instance with
 * {@link #material(String, SerializationContext)} ({@code engine.material(path)}); saving writes such materials as
 * their path again. With hot reloading, a changed material file is applied in place to every material made from it;
 * a changed prefab file is read again by the next nested instance.
 */
@Slf4j
public final class AssetLibrary {
  public static final String MATERIAL_FORMAT = "engine-material";
  public static final int MATERIAL_VERSION = 1;

  private static final Map<ResourceManager, AssetLibrary> LIBRARIES = Collections
      .synchronizedMap(new WeakHashMap<>());
  private static final AssetLibrary WITHOUT_RESOURCES = new AssetLibrary();
  private static final Assets CLASSPATH = new Assets();

  /** Parsed material files, by path. */
  private final Map<String, Map<String, Object>> materialFiles = new HashMap<>();
  /** The shared material of each file that was asked for as a whole. */
  private final Map<String, Material> shared = new LinkedHashMap<>();
  private final Map<Material, String> sharedPaths = new IdentityHashMap<>();
  /** Materials that start from a file and change some fields (weak: they belong to whoever uses them). */
  private final Map<Material, Derived> derived = new WeakHashMap<>();
  /** Parsed prefab files and the materials their nested instances share, by path. */
  private final Map<String, Map<String, Object>> prefabs = new HashMap<>();
  private final Map<String, List<Material>> prefabMaterials = new HashMap<>();

  private record Derived(String path, Map<String, Object> overrides) {
  }

  private AssetLibrary() {
  }

  /** The library of these resources (one per resource manager; null: one for contexts without resources). */
  public static AssetLibrary of(ResourceManager resources) {
    if (resources == null) {
      return WITHOUT_RESOURCES;
    }
    return LIBRARIES.computeIfAbsent(resources, r -> new AssetLibrary());
  }

  static String normalize(String path) {
    var p = path.replace('\\', '/');
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    return p;
  }

  private static Assets assets(SerializationContext ctx) {
    return ctx.resources() != null ? ctx.resources().assets() : CLASSPATH;
  }

  // ----- materials

  /**
   * The material of this file, shared by everyone who asks for it.
   *
   * @throws IllegalArgumentException if the file cannot be read or is not a material file
   */
  public synchronized Material material(String path, SerializationContext ctx) {
    final var p = normalize(path);
    final var cached = this.shared.get(p);
    if (cached != null) {
      return cached;
    }
    final var m = new Material();
    apply(p, m, ctx, new LinkedHashSet<>());
    this.shared.put(p, m);
    this.sharedPaths.put(m, p);
    return m;
  }

  /** A new material made from the file with {@code overrides} (fields as in a scene file) on top. */
  synchronized Material derived(String path, Map<String, Object> overrides, SerializationContext ctx) {
    final var p = normalize(path);
    final var m = new Material();
    apply(p, m, ctx, new LinkedHashSet<>());
    applyData(m, overrides, ctx);
    this.derived.put(m, new Derived(p, overrides));
    return m;
  }

  /** The file a material is the shared instance of, or null. */
  public synchronized String pathOf(Material m) {
    return this.sharedPaths.get(m);
  }

  /**
   * How a scene file refers to the material: the path of a shared one, {@code {"file": path, ...changed fields}} for
   * one made from a file, or null for a material of its own (written in full).
   */
  synchronized Object reference(Material m, SerializationContext ctx) {
    final var path = this.sharedPaths.get(m);
    if (path != null) {
      return path;
    }
    final var d = this.derived.get(m);
    if (d == null) {
      return null;
    }
    final var base = new Material();
    apply(d.path(), base, ctx, new LinkedHashSet<>());
    final Map<String, Object> baseData = new LinkedHashMap<>();
    Fields.write(base, baseData, ctx, Set.of("sortKey"));
    final Map<String, Object> data = new LinkedHashMap<>();
    Fields.write(m, data, ctx, Set.of("sortKey"));
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("file", d.path());
    data.forEach((k, v) -> {
      if (!Json.write(v).equals(Json.write(baseData.get(k)))) {
        out.put(k, v);
      }
    });
    final var params = ctx.writeParams(m.params);
    if (!Json.write(params).equals(Json.write(ctx.writeParams(base.params)))) {
      out.put("params", params);
    }
    return out;
  }

  /** Sets the fields of the file (after those of the file it starts from) on the material. */
  private void apply(String path, Material m, SerializationContext ctx, Set<String> chain) {
    if (!chain.add(path)) {
      throw new IllegalArgumentException("material file " + path + " starts from itself (" + chain + ")");
    }
    final var data = materialData(path, ctx);
    if (data.get("file") instanceof String base) {
      apply(normalize(base), m, ctx, chain);
    }
    try {
      applyData(m, data, ctx);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("material file " + path + ": " + e.getMessage(), e);
    }
  }

  private static void applyData(Material m, Map<String, Object> data, SerializationContext ctx) {
    Fields.read(m, data, ctx);
    if (data.containsKey("params")) {
      ctx.readParams(data.get("params"), m.params);
    }
  }

  private Map<String, Object> materialData(String path, SerializationContext ctx) {
    final var cached = this.materialFiles.get(path);
    if (cached != null) {
      return cached;
    }
    final var data = parseMaterial(path, assets(ctx));
    this.materialFiles.put(path, data);
    return data;
  }

  private static Map<String, Object> parseMaterial(String path, Assets assets) {
    final Map<String, Object> data;
    try {
      data = Json.asObject(Json.parse(assets.readString(path)));
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("material file " + path + ": " + e.getMessage(), e);
    }
    final var format = data.get("format");
    if (format != null && !MATERIAL_FORMAT.equals(format)) {
      throw new IllegalArgumentException(path + " is not a material file (format is " + format + ")");
    }
    final int version = Json.asInt(data.getOrDefault("version", 1.0));
    if (version > MATERIAL_VERSION) {
      throw new IllegalArgumentException("material file " + path + " has version " + version + ", newer than "
          + MATERIAL_VERSION);
    }
    return data;
  }

  /** Puts every supported field (and the shader parameters) back to a new material's values. */
  private static void reset(Material m) {
    final var fresh = new Material();
    try {
      for (Field f : Fields.of(Material.class)) {
        final Object v = f.get(fresh);
        switch (v) {
          case Vector2f a -> ((Vector2f) f.get(m)).set(a);
          case Vector3f a -> ((Vector3f) f.get(m)).set(a);
          case Vector4f a -> ((Vector4f) f.get(m)).set(a);
          case Quaternionf a -> ((Quaternionf) f.get(m)).set(a);
          case null, default -> f.set(m, v);
        }
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
    m.params.clear();
  }

  // ----- prefabs

  /** The parsed prefab file (not to be changed: copy it first). */
  synchronized Map<String, Object> prefab(String path, SerializationContext ctx) {
    final var p = normalize(path);
    final var cached = this.prefabs.get(p);
    if (cached != null) {
      return cached;
    }
    final Map<String, Object> template;
    try {
      template = Json.asObject(Json.parse(assets(ctx).readString(p)));
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("prefab " + p + ": " + e.getMessage(), e);
    }
    this.prefabs.put(p, template);
    return template;
  }

  /** The materials shared by the nested instances of this prefab file. */
  synchronized List<Material> prefabMaterials(String path) {
    return this.prefabMaterials.computeIfAbsent(normalize(path), p -> new ArrayList<>());
  }

  // ----- hot reloading

  /**
   * Applies a changed file: a material file is read again and applied in place to every material made from it (a
   * file that does not parse is logged and the materials keep their values); a prefab file is forgotten, so the next
   * nested instance reads it again (instances that exist stay as they are).
   *
   * @return how many materials and prefabs were affected
   */
  public synchronized int reload(String path, ResourceManager resources) {
    final var p = normalize(path);
    int count = 0;
    if (this.prefabs.remove(p) != null) {
      this.prefabMaterials.remove(p);
      count++;
    }
    if (!this.materialFiles.containsKey(p)) {
      return count;
    }
    final var assets = resources != null ? resources.assets() : CLASSPATH;
    try {
      this.materialFiles.put(p, parseMaterial(p, assets));
    } catch (IllegalArgumentException e) {
      log.error("Could not reload material {}; keeping the old values", p, e);
      return count;
    }
    // the materials of this file and of the files that start from it
    final var ctx = new SerializationContext(resources, null);
    for (var e : this.shared.entrySet()) {
      if (startsFrom(e.getKey(), p)) {
        count += reapply(e.getValue(), e.getKey(), Map.of(), ctx);
      }
    }
    for (var e : new ArrayList<>(this.derived.entrySet())) {
      if (startsFrom(e.getValue().path(), p)) {
        count += reapply(e.getKey(), e.getValue().path(), e.getValue().overrides(), ctx);
      }
    }
    return count;
  }

  /** Whether the material file is {@code changed} or starts from it (through its {@code "file"} chain). */
  private boolean startsFrom(String path, String changed) {
    final Set<String> seen = new LinkedHashSet<>();
    for (String p = path; p != null && seen.add(p); ) {
      if (p.equals(changed)) {
        return true;
      }
      final var data = this.materialFiles.get(p);
      p = data != null && data.get("file") instanceof String base ? normalize(base) : null;
    }
    return false;
  }

  private int reapply(Material m, String path, Map<String, Object> overrides, SerializationContext ctx) {
    try {
      reset(m);
      apply(path, m, ctx, new LinkedHashSet<>());
      applyData(m, overrides, ctx);
      return 1;
    } catch (RuntimeException e) {
      log.error("Could not apply material file {}", path, e);
      return 0;
    }
  }
}
