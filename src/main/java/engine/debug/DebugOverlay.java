package engine.debug;

import java.util.Locale;

import org.joml.Vector3f;

import com.jogamp.newt.event.KeyEvent;

import engine.core.Engine;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.physics.PhysicsStats;
import engine.render.Overlay;
import engine.render.RenderStats;
import engine.render.UiCanvas;
import engine.render.UiFont;

/** On-screen debug text: FPS, entities, draw statistics and camera position. Toggle with F3. */
public class DebugOverlay implements ISystem, Overlay {
  private final Engine engine;
  private boolean visible = true;
  private String text = "";
  private int lines = 1;
  private double sinceUpdate = 1;

  public DebugOverlay(Engine engine) {
    this.engine = engine;
    engine.input().bind("toggle_debug", KeyEvent.VK_F3);
  }

  @Override
  public void update(Scene scene, double dt) {
    if (this.engine.input().wasActionPressed("toggle_debug")) {
      this.visible = !this.visible;
    }
    this.sinceUpdate += dt;
    if (this.sinceUpdate < 0.25) {
      return; // text is rebuilt a few times per second, not every frame
    }
    this.sinceUpdate = 0;
    final var s = this.engine.backend().stats();
    final String cam = scene.activeCamera().map(c -> {
      final var p = c.entity().transform().worldPosition(new Vector3f());
      return String.format(Locale.ROOT, "%.1f %.1f %.1f", p.x, p.y, p.z);
    }).orElse("-");
    final var p = this.engine.physics().stats();
    this.text = String.format(Locale.ROOT, "%s %s\n%.0f fps\nentities %d\n"
        + "draw calls %d  instances %d\nculled %d  occluded %d (%d queries)\n"
        + "shadow casters %d (%d cascades, %d point)  lights %d\n"
        + "bodies %d (%d asleep)  pairs %d  contacts %d\ncamera %s",
        this.engine.config().title(), this.engine.config().version(), this.engine.fps(), scene.entities().size(),
        s.drawCalls, s.instances, s.culled, s.occluded, s.occlusionQueries, s.shadowCasters, s.shadowCascades,
        s.pointShadows, s.lights, p.bodies, p.sleepingBodies, p.candidatePairs, p.contacts, cam);
    this.lines = (int) this.text.chars().filter(c -> c == '\n').count() + 1;
  }

  @Override
  public void draw(UiCanvas ui, int width, int height) {
    if (!this.visible) {
      return;
    }
    final float pad = 6f;
    final var font = UiFont.MONOSPACED; // numbers keep their place while they change
    ui.drawRect(pad, pad, ui.textWidth(font, this.text, 1f) + 2 * pad, ui.lineHeight(font, 1f) * this.lines + 2 * pad,
        0f, 0f, 0f, 0.55f);
    ui.drawText(font, this.text, 2 * pad, 2 * pad, 1f, 1f, 1f, 1f, 1f);
  }
}
