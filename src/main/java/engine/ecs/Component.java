package engine.ecs;

public abstract class Component {
  private Entity entity;
  private boolean enabled = true;

  public Entity entity() {
    return this.entity;
  }

  public boolean isEnabled() {
    return this.enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** Called once when added to an entity. */
  protected void onAttach() {
  }

  /** Called every fixed update step for enabled components of entities in a scene. */
  public void update(double dt) {
  }

  final void attach(Entity entity) {
    if (this.entity != null) {
      throw new IllegalStateException("Component already attached to " + this.entity);
    }
    this.entity = entity;
    onAttach();
  }
}
