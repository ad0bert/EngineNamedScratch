package engine.serialization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import engine.ecs.Component;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.ecs.Transform;
import engine.render.Material;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Saves entities (names, transform hierarchy, components) to JSON and creates them again. The file looks like:
 *
 * <pre>
 * { "format": "engine-scene", "version": 1,
 *   "materials": [ { "color": [1, 1, 1, 1], "texture": "textures/cubeFace.png", ... } ],
 *   "entities": [
 *     { "name": "crate", "parent": null,
 *       "transform": { "position": [0, 1, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] },
 *       "components": [ { "type": "MeshRenderer", "properties": { "mesh": "cube:0.5", "material": 0 } },
 *                       { "type": "Rigidbody", "enabled": false, "properties": { "mass": 1, ... } } ] } ] }
 * </pre>
 *
 * Entities are referred to by their index in {@code entities} (parents, joint partners). Resources are referred to by
 * their {@link engine.resources.ResourceManager#keyOf key}. Components without a registered serializer are skipped.
 */
@Slf4j
public class SceneSerializer {
  public static final String FORMAT = "engine-scene";
  public static final int VERSION = 1;

  @Getter private final ComponentRegistry registry;
  private final Set<Class<?>> skipped = new HashSet<>();

  public SceneSerializer(ComponentRegistry registry) {
    this.registry = registry;
  }

  /** With the engine's components ({@link ComponentRegistry#defaults()}). */
  public SceneSerializer() {
    this(ComponentRegistry.defaults());
  }

  /** Every entity of the scene (after its pending adds). */
  public Map<String, Object> toJson(Scene scene, SerializationContext ctx) {
    scene.flush();
    return toJson(scene.entities(), ctx);
  }

  /** An entity and all its descendants (found among the scene's entities), e.g. for a prefab. */
  public Map<String, Object> toJson(Entity root, Scene scene, SerializationContext ctx) {
    scene.flush();
    final Map<Transform, Entity> byTransform = new IdentityHashMap<>();
    for (Entity e : scene.entities()) {
      byTransform.put(e.transform(), e);
    }
    byTransform.put(root.transform(), root);
    final List<Entity> out = new ArrayList<>();
    collect(root.transform(), byTransform, out);
    return toJson(out, ctx);
  }

  private static void collect(Transform t, Map<Transform, Entity> byTransform, List<Entity> out) {
    final var e = byTransform.get(t);
    if (e != null) {
      out.add(e);
    }
    for (Transform child : t.children()) {
      collect(child, byTransform, out);
    }
  }

  /** The given entities; parents and references outside the list are dropped (the entity keeps its local pose). */
  public Map<String, Object> toJson(List<Entity> entities, SerializationContext ctx) {
    ctx.assignIds(entities);
    final Map<Transform, Entity> byTransform = new IdentityHashMap<>();
    for (Entity e : entities) {
      byTransform.put(e.transform(), e);
    }
    final List<Object> list = new ArrayList<>();
    for (Entity e : entities) {
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", e.name());
      final var parent = e.transform().parent();
      m.put("parent", parent != null ? ctx.entityId(byTransform.get(parent)) : null);
      final var t = e.transform();
      final Map<String, Object> tm = new LinkedHashMap<>();
      tm.put("position", new float[] { t.position().x, t.position().y, t.position().z });
      tm.put("rotation", new float[] { t.rotation().x, t.rotation().y, t.rotation().z, t.rotation().w });
      tm.put("scale", new float[] { t.scale().x, t.scale().y, t.scale().z });
      m.put("transform", tm);
      final List<Object> comps = new ArrayList<>();
      for (Component c : e.components()) {
        final var data = writeComponent(c, ctx);
        if (data != null) {
          comps.add(data);
        }
      }
      m.put("components", comps);
      list.add(m);
    }
    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("format", FORMAT);
    root.put("version", VERSION);
    root.put("materials", new ArrayList<Object>(ctx.materialData()));
    root.put("entities", list);
    return root;
  }

  @SuppressWarnings("unchecked")
  private <T extends Component> Map<String, Object> writeComponent(T c, SerializationContext ctx) {
    final var s = this.registry.forClass((Class<T>) c.getClass());
    if (s == null) {
      if (this.skipped.add(c.getClass())) {
        log.info("{} has no serializer and is not saved", c.getClass().getSimpleName());
      }
      return null;
    }
    final var properties = s.write(c, ctx);
    if (properties == null) {
      return null;
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", s.type());
    if (!c.isEnabled()) {
      out.put("enabled", false);
    }
    out.put("properties", properties);
    return out;
  }

  public String write(Scene scene, SerializationContext ctx) {
    return Json.write(toJson(scene, ctx));
  }

  public void save(Scene scene, SerializationContext ctx, Path file) throws IOException {
    Files.writeString(file, write(scene, ctx), StandardCharsets.UTF_8);
  }

  /**
   * Creates the entities of a scene file (not yet added to any scene), in file order, with their hierarchy and
   * components.
   *
   * @throws IllegalArgumentException if the text is not a scene file or a component cannot be created
   */
  public List<Entity> read(String json, SerializationContext ctx) {
    return read(Json.asObject(Json.parse(json)), ctx, new ArrayList<>());
  }

  /** Like {@link #read(String, SerializationContext)}; materials are taken from (and added to) the cache. */
  List<Entity> read(Map<String, Object> root, SerializationContext ctx, List<Material> materialCache) {
    if (!FORMAT.equals(root.get("format"))) {
      throw new IllegalArgumentException("not a scene file (format is " + root.get("format") + ")");
    }
    final int version = Json.asInt(root.getOrDefault("version", 1.0));
    if (version > VERSION) {
      throw new IllegalArgumentException("scene file version " + version + " is newer than " + VERSION);
    }
    ctx.beginRead(root.containsKey("materials") ? Json.asList(root.get("materials")) : List.of(), materialCache);
    final var list = Json.asList(root.get("entities"));
    final List<Entity> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      final var m = Json.asObject(list.get(i));
      final var e = new Entity((String) m.get("name"));
      ctx.registerEntity(i, e);
      out.add(e);
    }
    for (int i = 0; i < list.size(); i++) {
      final var m = Json.asObject(list.get(i));
      final var e = out.get(i);
      if (m.get("parent") != null) {
        e.transform().setParent(out.get(Json.asInt(m.get("parent"))).transform());
      }
      if (m.get("transform") != null) {
        final var tm = Json.asObject(m.get("transform"));
        final float[] p = Json.asFloats(tm.get("position"));
        final float[] r = Json.asFloats(tm.get("rotation"));
        final float[] s = Json.asFloats(tm.get("scale"));
        e.transform().position().set(p[0], p[1], p[2]);
        final var q = e.transform().rotation().set(r[0], r[1], r[2], r[3]);
        if (Math.abs(q.lengthSquared() - 1f) > 1e-5f) {
          q.normalize(); // hand-edited; files written by the engine keep their exact values
        }
        e.transform().scale().set(s[0], s[1], s[2]);
      }
      for (Object o : Json.asList(m.getOrDefault("components", List.of()))) {
        readComponent(e, Json.asObject(o), ctx);
      }
    }
    ctx.finishRead();
    return out;
  }

  private void readComponent(Entity e, Map<String, Object> data, SerializationContext ctx) {
    final var type = (String) data.get("type");
    final var s = this.registry.forType(type);
    if (s == null) {
      log.warn("Unknown component type {} on {} is ignored", type, e.name());
      return;
    }
    final Component c;
    try {
      c = s.read(data.containsKey("properties") ? Json.asObject(data.get("properties")) : Map.of(), ctx);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException(type + " on " + e.name() + ": " + ex.getMessage(), ex);
    }
    e.add(c);
    if (Boolean.FALSE.equals(data.get("enabled"))) {
      c.setEnabled(false);
    }
  }

  /** Reads a scene file and adds its entities to the scene. */
  public List<Entity> load(String json, Scene scene, SerializationContext ctx) {
    final var entities = read(json, ctx);
    entities.forEach(scene::add);
    return entities;
  }

  public List<Entity> load(Path file, Scene scene, SerializationContext ctx) throws IOException {
    return load(Files.readString(file, StandardCharsets.UTF_8), scene, ctx);
  }
}
