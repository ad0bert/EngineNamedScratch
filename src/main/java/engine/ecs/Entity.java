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
    touch();
    return component;
  }

  // Bumped whenever a component is added or enabled/disabled anywhere; invalidates Scene's query caches.
  private static volatile int structureVersion;

  static int structureVersion() {
    return structureVersion;
  }

  /**
   * Marks the component structure as changed so cached scene queries are rebuilt. Only needed by components that
   * change something other than {@link Component#setEnabled} which affects how they are grouped or queried.
   */
  public static synchronized void touch() {
    structureVersion++;
  }

  public <T> Optional<T> find(Class<T> type) {
    return Optional.ofNullable(findOrNull(type));
  }

  /** Like {@link #find} but returns null instead of an empty Optional; for hot paths. */
  public <T> T findOrNull(Class<T> type) {
    for (int i = 0; i < this.components.size(); i++) {
      final Component c = this.components.get(i);
      if (type.isInstance(c)) {
        return type.cast(c);
      }
    }
    return null;
  }

  public <T> T get(Class<T> type) {
    return find(type).orElseThrow(() -> new IllegalStateException(this.name + " has no " + type.getSimpleName()));
  }

  public boolean has(Class<?> type) {
    return find(type).isPresent();
  }

  public int componentCount() {
    return this.components.size();
  }

  public Component componentAt(int index) {
    return this.components.get(index);
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
