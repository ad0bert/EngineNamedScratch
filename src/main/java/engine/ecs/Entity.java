package engine.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class Entity {
  private static final AtomicLong NEXT_ID = new AtomicLong(1);

  private final long id = NEXT_ID.getAndIncrement();
  private final String name;
  private final Transform transform = new Transform();
  private final List<Component> components = new ArrayList<>();

  public Entity() {
    this(null);
  }

  public Entity(String name) {
    this.name = name != null ? name : "entity-" + this.id;
  }

  public long id() {
    return this.id;
  }

  public String name() {
    return this.name;
  }

  public Transform transform() {
    return this.transform;
  }

  public <T extends Component> T add(T component) {
    component.attach(this);
    this.components.add(component);
    return component;
  }

  public <T> Optional<T> find(Class<T> type) {
    for (Component c : this.components) {
      if (type.isInstance(c)) {
        return Optional.of(type.cast(c));
      }
    }
    return Optional.empty();
  }

  public <T> T get(Class<T> type) {
    return find(type).orElseThrow(() -> new IllegalStateException(this.name + " has no " + type.getSimpleName()));
  }

  public boolean has(Class<?> type) {
    return find(type).isPresent();
  }

  public List<Component> components() {
    return Collections.unmodifiableList(this.components);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Entity e && e.id == this.id;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(this.id);
  }

  @Override
  public String toString() {
    return "Entity[" + this.id + ":" + this.name + "]";
  }
}
