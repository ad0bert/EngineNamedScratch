package engine.input;

import com.jogamp.newt.event.KeyEvent;

import engine.core.Engine;
import engine.core.EnginePlugin;
import engine.core.StepHook;

/**
 * Frees a captured mouse pointer when the "release_mouse" action is pressed (bound to Esc). A default plugin; a game
 * that uses Esc for its own menu leaves it out and calls {@link Engine#captureMouse} itself.
 */
public final class MouseReleasePlugin implements EnginePlugin {
  /** The action that frees the pointer. */
  public static final String ACTION = "release_mouse";

  private final StepHook hook = new StepHook() {
    @Override
    public void beforeSystems(Engine engine, long tick, double dt) {
      if (engine.input().wasActionPressed(ACTION)) {
        engine.captureMouse(false);
      }
    }
  };

  @Override
  public String id() {
    return "engine.mouse-release";
  }

  @Override
  public void install(Engine engine) {
    engine.input().bind(ACTION, KeyEvent.VK_ESCAPE);
    engine.addStepHook(this.hook);
  }

  @Override
  public void uninstall(Engine engine) {
    engine.removeStepHook(this.hook);
  }
}
