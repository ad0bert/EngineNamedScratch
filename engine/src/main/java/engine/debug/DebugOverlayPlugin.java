package engine.debug;

import com.jogamp.newt.event.KeyEvent;

import engine.core.Engine;
import engine.core.EnginePlugin;

/**
 * The on-screen {@link DebugOverlay} (FPS, entities, draw and physics statistics, the profiler), switched with the
 * "toggle_debug" action (F3). A default plugin. It starts visible unless {@code -Dengine.debugOverlay=false}; a game
 * for players usually hides it:
 *
 * <pre>{@code
 * engine.plugin(DebugOverlayPlugin.class).ifPresent(debug -> debug.setVisible(false));
 * }</pre>
 */
public final class DebugOverlayPlugin implements EnginePlugin {
  /** The action that shows and hides the text. */
  public static final String ACTION = "toggle_debug";

  private volatile boolean visible = Boolean.parseBoolean(System.getProperty("engine.debugOverlay", "true"));
  private DebugOverlay overlay;

  @Override
  public String id() {
    return "engine.debug-overlay";
  }

  @Override
  public void install(Engine engine) {
    engine.input().bind(ACTION, KeyEvent.VK_F3);
    this.overlay = new DebugOverlay(engine);
    this.overlay.setVisible(this.visible);
    engine.addSystem(this.overlay);
    engine.addOverlay(this.overlay);
  }

  @Override
  public void uninstall(Engine engine) {
    if (this.overlay != null) {
      engine.removeSystem(this.overlay);
      engine.removeOverlay(this.overlay);
      this.overlay = null;
    }
  }

  /** Shows or hides the text. Safe to call from any thread, also before the engine runs. */
  public void setVisible(boolean value) {
    this.visible = value;
    final var o = this.overlay;
    if (o != null) {
      o.setVisible(value);
    }
  }

  public boolean isVisible() {
    final var o = this.overlay;
    return o != null ? o.isVisible() : this.visible;
  }
}
