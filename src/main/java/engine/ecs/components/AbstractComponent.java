package engine.ecs.components;

import engine.ecs.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import lombok.AccessLevel;

@Data
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public abstract class AbstractComponent implements IComponent {

  boolean isActive;

  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  Entity entity;

}
