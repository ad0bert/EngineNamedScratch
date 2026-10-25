package engine.ecs;

import java.util.ArrayList;
import java.util.List;

import engine.ecs.components.IComponent;
import engine.geometry.Point;
import engine.render.interfaces.IDrawable;
import engine.util.IdFactory;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.val;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public abstract class Entity {

  @EqualsAndHashCode.Include
  final long id = IdFactory.getNextId();
  Point position = new Point(0f, 0f, 0f);
  Point rotation = new Point(0f, 0f, 0f);

  List<IComponent> components = new ArrayList<>();

  public void addComponent(IComponent component) {
    this.components.add(component);
    component.setEntity(this);
  }

  public void activateComponent(Class<?> clazz) {
    for (val comp : this.components) {
      if (clazz.isAssignableFrom(comp.getClass())) {
        comp.setActive(true);
      }
    }
  }

  public void deactivateComponent(Class<IDrawable> clazz) {
    for (val comp : this.components) {
      if (clazz.isAssignableFrom(comp.getClass())) {
        comp.setActive(false);
      }
    }
  }
}
