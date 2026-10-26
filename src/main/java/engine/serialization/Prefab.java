package engine.serialization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.render.Material;

/**
 * A saved entity hierarchy used as a template: every {@link #instantiate} creates new entities from it. The instances
 * share their materials (so their renderers still batch into one instanced draw call); change a material through
 * {@link #materials()} to change it on all of them. The first entity of the file is the root.
 */
public final class Prefab {
  private final Map<String, Object> template;
  private final SceneSerializer serializer;
  private final List<Material> materials = new ArrayList<>();

  public Prefab(Map<String, Object> template, SceneSerializer serializer) {
    this.template = template;
    this.serializer = serializer;
  }

  /** @throws IllegalArgumentException if the text is not a scene file */
  public static Prefab parse(String json, SceneSerializer serializer) {
    final var p = new Prefab(Json.asObject(Json.parse(json)), serializer);
    if (!SceneSerializer.FORMAT.equals(p.template.get("format"))) {
      throw new IllegalArgumentException("not a prefab file (format is " + p.template.get("format") + ")");
    }
    return p;
  }

  /** A prefab file from the classpath, e.g. {@code prefabs/lamp.json}. */
  public static Prefab load(String path, SceneSerializer serializer) throws IOException {
    try (InputStream in = Prefab.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Resource not found: " + path);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), serializer);
    }
  }

  /** Captures an entity and its descendants as they are now. */
  public static Prefab of(Entity root, Scene scene, SerializationContext ctx, SceneSerializer serializer) {
    // through text, so the template holds plain JSON values only
    return parse(Json.write(serializer.toJson(root, scene, ctx)), serializer);
  }

  /** The materials shared by all instances, once the first instance exists. */
  public List<Material> materials() {
    return this.materials;
  }

  /** Creates the entities (root first) and adds them to the scene. */
  public List<Entity> instantiate(Scene scene, SerializationContext ctx) {
    final var entities = this.serializer.read(this.template, ctx, this.materials);
    entities.forEach(scene::add);
    return entities;
  }

  /** Creates an instance with its root placed at {@code position}, turned by {@code rotation} (null keeps it). */
  public Entity instantiate(Scene scene, SerializationContext ctx, Vector3f position, Quaternionf rotation) {
    final var root = instantiate(scene, ctx).get(0);
    root.transform().position().set(position);
    if (rotation != null) {
      root.transform().rotation().set(rotation);
    }
    root.transform().snapshot(); // no interpolation from the template's position
    return root;
  }

  public String toJson() {
    return Json.write(this.template);
  }
}
