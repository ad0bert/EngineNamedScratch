package engine.serialization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import engine.ecs.Component;

/**
 * Serializes a component through its public fields (see {@link Fields}). Subclasses can add what is not in a field by
 * overriding {@link #writeExtra} and {@link #readExtra}.
 */
public class FieldSerializer<T extends Component> implements ComponentSerializer<T> {
  private final String type;
  private final Class<T> componentClass;
  private final Function<SerializationContext, T> factory;
  private final Set<String> skip;

  /**
   * @param factory creates a component with default settings (the fields are then overwritten)
   * @param skip    names of public fields not to save
   */
  public FieldSerializer(String type, Class<T> componentClass, Function<SerializationContext, T> factory,
      String... skip) {
    this.type = type;
    this.componentClass = componentClass;
    this.factory = factory;
    this.skip = Set.of(skip);
  }

  @Override
  public String type() {
    return this.type;
  }

  @Override
  public Class<T> componentClass() {
    return this.componentClass;
  }

  @Override
  public Map<String, Object> write(T component, SerializationContext ctx) {
    final Map<String, Object> out = new LinkedHashMap<>();
    Fields.write(component, out, ctx, this.skip);
    writeExtra(component, out, ctx);
    return out;
  }

  @Override
  public T read(Map<String, Object> data, SerializationContext ctx) {
    final T c = this.factory.apply(ctx);
    Fields.read(c, data, ctx);
    readExtra(c, data, ctx);
    return c;
  }

  protected void writeExtra(T component, Map<String, Object> out, SerializationContext ctx) {
  }

  protected void readExtra(T component, Map<String, Object> data, SerializationContext ctx) {
  }
}
