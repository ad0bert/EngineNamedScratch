package engine.serialization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.animation.AnimationClip;
import engine.audio.AudioClip;
import engine.ecs.Entity;
import engine.ecs.Transform;
import engine.input.InputState;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.Material;
import engine.render.ShaderParams;
import engine.render.Skin;
import engine.resources.ResourceManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * What serializers need besides the component itself: resources by key, the input state (for controllers), entity
 * references (written as the index of the entity in the file) and shared materials (written once, referred to by
 * index, so renderers that shared a material, and therefore an instanced draw call, still share it after loading).
 * One context is used for one write or one read.
 */
@Slf4j
public class SerializationContext {
  @Getter private final ResourceManager resources;
  @Getter private final InputState input;
  /** The material and prefab files of these resources. */
  @Getter private final AssetLibrary library;
  /** The prefab files being read around this one (to refuse a prefab that contains itself). */
  private final Set<String> prefabChain;

  // writing
  private final Map<Entity, Integer> ids = new IdentityHashMap<>();
  private final Map<Material, Integer> materialIds = new IdentityHashMap<>();
  private final List<Object> materialData = new ArrayList<>();
  private final Map<AnimationClip, Integer> clipIds = new IdentityHashMap<>();
  private final List<Map<String, Object>> clipData = new ArrayList<>();
  private final Map<Skin, Integer> skinIds = new IdentityHashMap<>();
  private final List<Map<String, Object>> skinData = new ArrayList<>();
  private final Map<Transform, Entity> byTransform = new IdentityHashMap<>();
  private final Set<Class<?>> warned = new HashSet<>();

  // reading
  private final Map<Integer, Entity> entities = new HashMap<>();
  private List<Object> materialSource = List.of();
  private List<Material> materials = new ArrayList<>();
  private List<Object> clipSource = List.of();
  private final Map<Integer, AnimationClip> clips = new HashMap<>();
  private List<Object> skinSource = List.of();
  private final Map<Integer, Skin> skins = new HashMap<>();
  private final List<Runnable> links = new ArrayList<>();

  /** @param resources may be null (then no mesh, texture or sound can be saved or loaded) */
  public SerializationContext(ResourceManager resources, InputState input) {
    this(resources, input, Set.of());
  }

  private SerializationContext(ResourceManager resources, InputState input, Set<String> prefabChain) {
    this.resources = resources;
    this.input = input;
    this.library = AssetLibrary.of(resources);
    this.prefabChain = prefabChain;
  }

  // ----- writing

  void assignIds(List<Entity> saved) {
    for (int i = 0; i < saved.size(); i++) {
      this.ids.put(saved.get(i), i);
      this.byTransform.put(saved.get(i).transform(), saved.get(i));
    }
  }

  /** The id an entity is written as, or null if it is not part of what is being saved. */
  public Integer entityId(Entity e) {
    return e == null ? null : this.ids.get(e);
  }

  /**
   * Writes the material once and returns its index. A material from a material file is written as its path (or as the
   * path and the fields it changes, see {@link AssetLibrary}).
   */
  public int materialId(Material m) {
    final var id = this.materialIds.get(m);
    if (id != null) {
      return id;
    }
    final int next = this.materialData.size();
    this.materialIds.put(m, next);
    final var reference = this.library.reference(m, this);
    if (reference != null) {
      this.materialData.add(reference);
      return next;
    }
    final Map<String, Object> data = new LinkedHashMap<>();
    this.materialData.add(data); // reserved before writing, in case fields refer back
    Fields.write(m, data, this, Set.of("sortKey"));
    if (!m.params.isEmpty()) {
      data.put("params", writeParams(m.params));
    }
    return next;
  }

  /**
   * Shader parameters as JSON: floats as numbers, vectors as arrays, ints as {@code {"int": n}}, matrices as
   * {@code {"mat4": [16]}}, textures as {@code {"texture": key}} (left out without a key).
   */
  Map<String, Object> writeParams(ShaderParams params) {
    final Map<String, Object> out = new LinkedHashMap<>();
    params.forEach((name, value) -> {
      switch (value) {
        case Float f -> out.put(name, f);
        case Integer i -> out.put(name, Map.of("int", i));
        case Vector2f v -> out.put(name, new float[] { v.x, v.y });
        case Vector3f v -> out.put(name, new float[] { v.x, v.y, v.z });
        case Vector4f v -> out.put(name, new float[] { v.x, v.y, v.z, v.w });
        case Matrix4f m -> out.put(name, Map.of("mat4", m.get(new float[16])));
        case GpuTexture t -> {
          final var key = key(t);
          if (key != null) {
            out.put(name, Map.of("texture", key));
          }
        }
        default -> {
        }
      }
    });
    return out;
  }

  void readParams(Object json, ShaderParams params) {
    for (var e : Json.asObject(json).entrySet()) {
      final String name = e.getKey();
      final Object v = e.getValue();
      if (v instanceof Number n) {
        params.set(name, n.floatValue());
      } else if (v instanceof Map<?, ?> m) {
        if (m.containsKey("int")) {
          params.set(name, Json.asInt(m.get("int")));
        } else if (m.containsKey("mat4")) {
          params.set(name, new Matrix4f().set(Json.asFloats(m.get("mat4"))));
        } else if (m.containsKey("texture")) {
          final var t = texture((String) m.get("texture"));
          if (t != null) {
            params.set(name, t);
          }
        }
      } else {
        final float[] a = Json.asFloats(v);
        switch (a.length) {
          case 2 -> params.set(name, new Vector2f(a[0], a[1]));
          case 3 -> params.set(name, new Vector3f(a[0], a[1], a[2]));
          case 4 -> params.set(name, new Vector4f(a[0], a[1], a[2], a[3]));
          default -> throw new IllegalArgumentException("shader parameter " + name + " has " + a.length + " numbers");
        }
      }
    }
  }

  List<Object> materialData() {
    return this.materialData;
  }

  /** Writes the clip once (shared by every animator that has it) and returns its index. */
  public int clipId(AnimationClip clip) {
    final var id = this.clipIds.get(clip);
    if (id != null) {
      return id;
    }
    final int next = this.clipData.size();
    this.clipIds.put(clip, next);
    this.clipData.add(ClipSerializer.write(clip));
    return next;
  }

  List<Map<String, Object>> clipData() {
    return this.clipData;
  }

  /**
   * Writes the skin once (renderers that shared it share it again after loading) and returns its index, or null
   * (with a warning) if one of its joints is not being saved.
   */
  public Integer skinId(Skin skin) {
    final var id = this.skinIds.get(skin);
    if (id != null) {
      return id;
    }
    final int[] joints = new int[skin.jointCount()];
    final float[] inverseBind = new float[16 * skin.jointCount()];
    final var m = new Matrix4f();
    for (int i = 0; i < joints.length; i++) {
      final var joint = entityId(this.byTransform.get(skin.joint(i)));
      if (joint == null) {
        log.warn("A skin's joint is not among the saved entities; the skin is not saved");
        return null;
      }
      joints[i] = joint;
      skin.inverseBind(i, m).get(inverseBind, 16 * i);
    }
    final int next = this.skinData.size();
    this.skinIds.put(skin, next);
    final Map<String, Object> data = new LinkedHashMap<>();
    data.put("joints", joints);
    data.put("inverseBind", inverseBind);
    this.skinData.add(data);
    return next;
  }

  List<Map<String, Object>> skinData() {
    return this.skinData;
  }

  /** The key of a mesh, texture or sound, or null (with a warning) if it has none. */
  public String key(Object resource) {
    if (resource == null) {
      return null;
    }
    final var key = this.resources != null ? this.resources.keyOf(resource) : null;
    if (key == null && this.warned.add(resource.getClass())) {
      log.warn("A {} has no resource key and is not saved (create it through the ResourceManager with a key)",
          resource.getClass().getSimpleName());
    }
    return key;
  }

  // ----- reading

  void beginRead(List<Object> materialSource, List<Material> materialCache, List<Object> clipSource,
      List<Object> skinSource) {
    this.materialSource = materialSource;
    this.materials = materialCache;
    this.clipSource = clipSource;
    this.skinSource = skinSource;
    this.clips.clear();
    this.skins.clear();
  }

  /** The clip with this index, created on first use. */
  public AnimationClip clip(Object id) {
    if (id == null) {
      return null;
    }
    return this.clips.computeIfAbsent(Json.asInt(id),
        i -> ClipSerializer.read(Json.asObject(this.clipSource.get(i))));
  }

  /**
   * The skin with this index, created on first use. Its joints are entities, so call it once all of them exist
   * (from {@link #afterRead}).
   */
  public Skin skin(Object id) {
    if (id == null) {
      return null;
    }
    return this.skins.computeIfAbsent(Json.asInt(id), i -> {
      final var data = Json.asObject(this.skinSource.get(i));
      final int[] jointIds = Json.asInts(data.get("joints"));
      final float[] inverse = Json.asFloats(data.get("inverseBind"));
      final var joints = new Transform[jointIds.length];
      final var bind = new Matrix4f[jointIds.length];
      for (int j = 0; j < jointIds.length; j++) {
        final var e = this.entities.get(jointIds[j]);
        if (e == null) {
          throw new IllegalArgumentException("skin joint " + jointIds[j] + " is not in the file");
        }
        joints[j] = e.transform();
        bind[j] = new Matrix4f().set(inverse, 16 * j);
      }
      return new Skin(joints, bind);
    });
  }

  void registerEntity(int id, Entity e) {
    this.entities.put(id, e);
  }

  /** The entity read with this id, or null. */
  public Entity entity(Object id) {
    return id == null ? null : this.entities.get(Json.asInt(id));
  }

  /** Runs after all entities and components exist (to resolve references between them). */
  public void afterRead(Runnable link) {
    this.links.add(link);
  }

  void finishRead() {
    for (Runnable r : this.links) {
      r.run();
    }
    this.links.clear();
  }

  /**
   * The material with this index in the file's {@code materials}, created on first use; a string is the path of a
   * material file (its shared instance, see {@link AssetLibrary}).
   */
  public Material material(Object id) {
    if (id == null) {
      return null;
    }
    if (id instanceof String path) {
      return this.library.material(path, this);
    }
    final int i = Json.asInt(id);
    while (this.materials.size() <= i) {
      this.materials.add(null);
    }
    var m = this.materials.get(i);
    if (m == null) {
      if (i >= this.materialSource.size()) {
        throw new IllegalArgumentException("material " + i + " is not in the file");
      }
      final var source = this.materialSource.get(i);
      if (source instanceof String path) {
        m = this.library.material(path, this);
      } else {
        final var data = Json.asObject(source);
        if (data.get("file") instanceof String path) {
          m = this.library.derived(path, data, this);
        } else {
          m = new Material();
          Fields.read(m, data, this);
          if (data.containsKey("params")) {
            readParams(data.get("params"), m.params);
          }
        }
      }
      this.materials.set(i, m);
    }
    return m;
  }

  /**
   * Creates the entities of a nested prefab (root first, not yet added to a scene) for an entry of a scene file that
   * has {@code "prefab": path}. The entry's {@code name}, {@code transform} and {@code components} apply to the
   * prefab's root, and {@code overrides} (by entity name) to its other entities: a listed component changes the
   * properties it names on the prefab's component of that type ({@code "removed": true} takes it away), or is added
   * if there is none. Material and entity indices in overrides are those of the prefab file; a material may also be
   * given as the path of a material file.
   */
  List<Entity> nestedPrefab(String path, Map<String, Object> entry, SceneSerializer serializer) {
    final var p = AssetLibrary.normalize(path);
    if (this.prefabChain.contains(p)) {
      throw new IllegalArgumentException("prefab " + p + " contains itself (" + this.prefabChain + ")");
    }
    try {
      final var template = Json.asObject(copy(this.library.prefab(p, this)));
      if (!(template.get("entities") instanceof List<?> list) || list.isEmpty()) {
        throw new IllegalArgumentException("it has no entities");
      }
      final var entities = Json.asList(template.get("entities"));
      final var root = Json.asObject(entities.get(0));
      if (entry.get("name") != null) {
        root.put("name", entry.get("name"));
      }
      override(root, entry);
      if (entry.get("overrides") != null) {
        for (var e : Json.asObject(entry.get("overrides")).entrySet()) {
          override(byName(entities, e.getKey()), Json.asObject(e.getValue()));
        }
      }
      final Set<String> chain = new java.util.LinkedHashSet<>(this.prefabChain);
      chain.add(p);
      return serializer.read(template, new SerializationContext(this.resources, this.input, chain),
          this.library.prefabMaterials(p));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("prefab " + p + ": " + e.getMessage(), e);
    }
  }

  private static Map<String, Object> byName(List<Object> entities, String name) {
    for (Object o : entities) {
      final var m = Json.asObject(o);
      if (name.equals(m.get("name"))) {
        return m;
      }
    }
    throw new IllegalArgumentException("no entity named " + name + " to override");
  }

  /** Applies an override's transform and components to an entity of a prefab template. */
  private static void override(Map<String, Object> entity, Map<String, Object> changes) {
    if (changes.get("transform") != null) {
      entity.put("transform", copy(changes.get("transform")));
    }
    if (changes.get("components") == null) {
      return;
    }
    final List<Object> components = entity.get("components") != null ? Json.asList(entity.get("components"))
        : new ArrayList<>();
    entity.put("components", components);
    for (Object o : Json.asList(changes.get("components"))) {
      final var change = Json.asObject(o);
      final var type = change.get("type");
      Map<String, Object> existing = null;
      for (Object c : components) {
        if (java.util.Objects.equals(Json.asObject(c).get("type"), type)) {
          existing = Json.asObject(c);
          break;
        }
      }
      if (Boolean.TRUE.equals(change.get("removed"))) {
        if (existing != null) {
          components.remove(existing);
        }
      } else if (existing == null) {
        components.add(copy(change));
      } else {
        if (change.containsKey("enabled")) {
          existing.put("enabled", change.get("enabled"));
        }
        if (change.get("properties") != null) {
          final Map<String, Object> properties = existing.get("properties") != null
              ? Json.asObject(existing.get("properties"))
              : new LinkedHashMap<>();
          properties.putAll(Json.asObject(copy(change.get("properties"))));
          existing.put("properties", properties);
        }
      }
    }
  }

  /** A deep copy of parsed JSON (maps and lists; other values are immutable). */
  @SuppressWarnings("unchecked")
  private static Object copy(Object json) {
    if (json instanceof Map<?, ?> m) {
      final Map<String, Object> out = new LinkedHashMap<>();
      ((Map<String, Object>) m).forEach((k, v) -> out.put(k, copy(v)));
      return out;
    }
    if (json instanceof List<?> l) {
      final List<Object> out = new ArrayList<>(l.size());
      l.forEach(v -> out.add(copy(v)));
      return out;
    }
    return json;
  }

  public GpuMesh mesh(String key) {
    return key == null || this.resources == null ? null : this.resources.meshByKey(key);
  }

  public GpuTexture texture(String key) {
    return key == null || this.resources == null ? null : this.resources.textureByKey(key);
  }

  public AudioClip sound(String key) {
    return key == null || this.resources == null ? null : this.resources.soundByKey(key);
  }
}
