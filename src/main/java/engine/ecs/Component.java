package engine.ecs;

import lombok.Getter;

public abstract class Component {
  @Getter private Entity entity;
  private boolean enabled = true;

  public boolean isEnabled() {
    return this.enabled;
  }

  public void setEnabled(boolean enabled) {
    if (this.enabled != enabled) {
      this.enabled = enabled;
      Entity.touch();
    }
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
