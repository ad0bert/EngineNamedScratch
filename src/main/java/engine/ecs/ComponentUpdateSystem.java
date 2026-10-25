package engine.ecs;

/** Calls {@link Component#update(double)} on every enabled component. */
public class ComponentUpdateSystem implements ISystem {
  @Override
  public void update(Scene scene, double dt) {
    for (Entity e : scene.entities()) {
      // index loop: components may add siblings during update
      final var comps = e.components();
      for (int i = 0; i < comps.size(); i++) {
        final Component c = comps.get(i);
        if (c.isEnabled()) {
          c.update(dt);
        }
      }
    }
  }
}
