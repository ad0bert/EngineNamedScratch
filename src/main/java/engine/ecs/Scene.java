package engine.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import engine.render.Camera;

/** Holds entities. Adds and removes are deferred to {@link #flush()} so systems can mutate during iteration. */
public class Scene {
  private final List<Entity> entities = new ArrayList<>();
  private final List<Entity> toAdd = new ArrayList<>();
  private final List<Entity> toRemove = new ArrayList<>();

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
      this.entities.removeAll(this.toRemove);
      this.toRemove.clear();
    }
    if (!this.toAdd.isEmpty()) {
      for (Entity e : this.toAdd) {
        if (!this.entities.contains(e)) {
          this.entities.add(e);
          e.transform().snapshot(); // no interpolation smear from the origin
        }
      }
      this.toAdd.clear();
    }
  }

  public List<Entity> entities() {
    return Collections.unmodifiableList(this.entities);
  }

  /** All enabled components of the given type. */
  public <T extends Component> List<T> components(Class<T> type) {
    final List<T> out = new ArrayList<>();
    for (Entity e : this.entities) {
      for (Component c : e.components()) {
        if (c.isEnabled() && type.isInstance(c)) {
          out.add(type.cast(c));
        }
      }
    }
    return out;
  }

  public Stream<Entity> stream() {
    return this.entities.stream();
  }

  public Optional<Camera> activeCamera() {
    return components(Camera.class).stream().findFirst();
  }
}
