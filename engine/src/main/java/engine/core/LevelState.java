package engine.core;

import engine.resources.ResourceManager;

/**
 * A game state that owns the scene while it is on the stack: {@link #build} fills the scene on enter, and on exit
 * the scene is cleared and the resources first loaded during the level are released (through a
 * {@link ResourceManager#openScope() resource scope}). Going from one level to the next is
 * {@code engine.states().replace(new NextLevel())}.
 *
 * <p>
 * Systems, overlays and input bindings a level adds are not undone automatically: remove them in {@link #exit} (after
 * calling {@code super.exit}) or add them once in {@link Game#init}.
 */
public abstract class LevelState implements GameState {
  private ResourceManager.Scope scope;

  /** Creates the level's entities, lights and camera; the resources loaded here belong to the level. */
  protected abstract void build(Engine engine);

  @Override
  public void enter(Engine engine) {
    this.scope = engine.resources().openScope();
    build(engine);
  }

  @Override
  public void exit(Engine engine) {
    engine.scene().clear();
    engine.scene().flush(); // the entities go now, before their meshes and sounds are released
    if (this.scope != null) {
      this.scope.close();
      this.scope = null;
    }
  }
}
