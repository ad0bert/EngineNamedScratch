package engine.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import engine.render.Camera;
import lombok.Getter;

/**
 * Holds entities. Adds and removes are deferred to {@link #flush()} so systems can mutate during iteration.
 * Component queries are cached until the scene's entities or any component's presence/enabled flag change.
 */
public class Scene {
  private final List<Entity> entities = new ArrayList<>();
  private final Set<Entity> entitySet = new HashSet<>();
  private final List<Entity> toAdd = new ArrayList<>();
  private final Set<Entity> toRemove = new HashSet<>();

  /** Increases whenever entities were added to or removed from the scene. */
  @Getter private int version;
  private final Map<Class<?>, List<?>> queryCache = new HashMap<>();
  private int cachedSceneVersion = -1;
  private int cachedGlobalVersion = -1;

  public Entity add(Entity entity) {
    this.toAdd.add(entity);
    return entity;
  }

  public void remove(Entity entity) {
    this.toRemove.add(entity);
  }

  /** Applies pending adds and removes. Called by the engine at the start of every update step. */
  public void flush() {
    if (!this.toRemove.isEmpty()) {
      if (this.entities.removeIf(this.toRemove::contains)) {
        this.version++;
      }
      this.entitySet.removeAll(this.toRemove);
      this.toRemove.clear();
    }
    if (!this.toAdd.isEmpty()) {
      for (Entity e : this.toAdd) {
        if (this.entitySet.add(e)) {
          this.entities.add(e);
          e.transform().snapshot(); // no interpolation smear from the origin
          this.version++;
        }
      }
      this.toAdd.clear();
    }
  }

  public List<Entity> entities() {
    return Collections.unmodifiableList(this.entities);
  }

  /**
   * All enabled components of the given type. The returned list is a read-only snapshot: it stays valid (and is the
   * same instance) until something changes, after which a new list is returned. Do not modify it.
   */
  @SuppressWarnings("unchecked")
  public <T extends Component> List<T> components(Class<T> type) {
    if (this.cachedSceneVersion != this.version || this.cachedGlobalVersion != Entity.structureVersion()) {
      this.queryCache.clear();
      this.cachedSceneVersion = this.version;
      this.cachedGlobalVersion = Entity.structureVersion();
    }
    var cached = this.queryCache.get(type);
    if (cached == null) {
      final List<T> out = new ArrayList<>();
      for (Entity e : this.entities) {
        for (Component c : e.components()) {
          if (c.isEnabled() && type.isInstance(c)) {
            out.add(type.cast(c));
          }
        }
      }
      cached = Collections.unmodifiableList(out);
      this.queryCache.put(type, cached);
    }
    return (List<T>) cached;
  }

  public Stream<Entity> stream() {
    return this.entities.stream();
  }

  public Optional<Camera> activeCamera() {
    final var cameras = components(Camera.class);
    return cameras.isEmpty() ? Optional.empty() : Optional.of(cameras.get(0));
  }
}
