package engine.debug;

import org.joml.Vector3f;

import engine.core.Engine;
import engine.ecs.ISystem;
import engine.ecs.Scene;

/** Shows FPS, entity count and camera position in the window title. Toggle with F3. */
public class DebugOverlay implements ISystem {
  private final Engine engine;
  private double sinceUpdate;
  private boolean visible = true;

  public DebugOverlay(Engine engine) {
    this.engine = engine;
    engine.input().bind("toggle_debug", com.jogamp.newt.event.KeyEvent.VK_F3);
  }

  @Override
  public void update(Scene scene, double dt) {
    if (this.engine.input().wasActionPressed("toggle_debug")) {
      this.visible = !this.visible;
      this.sinceUpdate = 1;
    }
    this.sinceUpdate += dt;
    if (this.sinceUpdate < 0.5) {
      return;
    }
    this.sinceUpdate = 0;
    final String base = this.engine.config().title();
    if (!this.visible) {
      this.engine.setTitle(base);
      return;
    }
    final String pos = scene.activeCamera().map(c -> {
      final Vector3f p = c.entity().transform().worldPosition(new Vector3f());
      return String.format("cam %.1f %.1f %.1f", p.x, p.y, p.z);
    }).orElse("no camera");
    this.engine.setTitle(String.format("%s | %.0f fps | %d entities | %s | F3 hides, Esc frees mouse", base,
        this.engine.fps(), scene.entities().size(), pos));
  }
}
