package engine.serialization;

import java.util.Map;

import engine.ecs.Component;

/**
 * Saves and restores one kind of component. A component type opts in to scene files by registering a serializer in a
 * {@link ComponentRegistry}; for components whose state is in public fields, {@link FieldSerializer} does it by
 * reflection. The component's enabled flag and the type name are handled by {@link SceneSerializer}.
 */
public interface ComponentSerializer<T extends Component> {
  /** The name written to the file, e.g. {@code "Rigidbody"}; keep it stable when renaming the class. */
  String type();

  Class<T> componentClass();

  /** The component's state, or null to leave it out (e.g. a renderer whose mesh has no resource key). */
  Map<String, Object> write(T component, SerializationContext ctx);

  /**
   * Creates the component from its data. References to other entities must be resolved with
   * {@link SerializationContext#afterRead}, since the entity may come later in the file.
   */
  T read(Map<String, Object> data, SerializationContext ctx);
}
