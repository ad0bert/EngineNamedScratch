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

import engine.animation.AnimationClip;
import engine.audio.AudioClip;
import engine.ecs.Entity;
import engine.ecs.Transform;
import engine.input.InputState;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.Material;
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

  // writing
  private final Map<Entity, Integer> ids = new IdentityHashMap<>();
  private final Map<Material, Integer> materialIds = new IdentityHashMap<>();
  private final List<Map<String, Object>> materialData = new ArrayList<>();
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
    this.resources = resources;
    this.input = input;
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

  /** Writes the material once and returns its index. */
  public int materialId(Material m) {
    final var id = this.materialIds.get(m);
    if (id != null) {
      return id;
    }
    final int next = this.materialData.size();
    this.materialIds.put(m, next);
    final Map<String, Object> data = new LinkedHashMap<>();
    this.materialData.add(data); // reserved before writing, in case fields refer back
    Fields.write(m, data, this, Set.of("sortKey"));
    return next;
  }

  List<Map<String, Object>> materialData() {
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

  /** The material with this index, created on first use. */
  public Material material(Object id) {
    if (id == null) {
      return null;
    }
    final int i = Json.asInt(id);
    while (this.materials.size() <= i) {
      this.materials.add(null);
    }
    var m = this.materials.get(i);
    if (m == null) {
      m = new Material();
      this.materials.set(i, m);
      Fields.read(m, Json.asObject(this.materialSource.get(i)), this);
    }
    return m;
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
