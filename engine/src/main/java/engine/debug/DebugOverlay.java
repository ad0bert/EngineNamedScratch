package engine.debug;

import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * On-screen debug text: FPS, entities, draw statistics and camera position, then the {@link Profiler}: a graph of the
 * last frame times (the line is 60 fps) and the sections that took longest on average. Toggle with F3; whether it
 * shows is {@link Engine#setDebugOverlayVisible} (start hidden with {@code -Dengine.debugOverlay=false}).
 */
public class DebugOverlay implements ISystem, Overlay {
  private final Engine engine;
  private String text = "";
  private int lines = 1;
  private double sinceUpdate = 1;

  public DebugOverlay(Engine engine) {
    this.engine = engine;
    engine.input().bind("toggle_debug", KeyEvent.VK_F3);
  }

  @Override
  public boolean runsWhilePaused() {
    return true;
  }

  @Override
  public void update(Scene scene, double dt) {
    if (this.engine.input().wasActionPressed("toggle_debug")) {
      this.engine.setDebugOverlayVisible(!this.engine.isDebugOverlayVisible());
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
    this.text = String.format(Locale.ROOT, "%s %s (engine %s)%s\n%.0f fps\nentities %d\n"
        + "draw calls %d  instances %d\nculled %d  occluded %d (%d queries)\n"
        + "shadow casters %d (%d cascades, %d local, %d maps reused)  lights %d\nparticles %d (%d emitters)\n"
        + "bodies %d (%d asleep)  pairs %d  contacts %d\nsounds %d (+%d virtual) on %s\ncamera %s",
        this.engine.config().title(), this.engine.config().version(), Engine.VERSION,
        this.engine.isPaused() ? "  paused" : "", this.engine.fps(), scene.entities().size(),
        s.drawCalls, s.instances, s.culled, s.occluded, s.occlusionQueries, s.shadowCasters, s.shadowCascades,
        s.pointShadows, s.cachedShadowMaps, s.lights, s.particles, s.particleEmitters, p.bodies, p.sleepingBodies,
        p.candidatePairs, p.contacts,
        this.engine.audio().audibleCount(), this.engine.audio().virtualCount(), this.engine.audio().backend().name(),
        cam);
    this.text += profile(this.engine.profiler());
    this.lines = (int) this.text.chars().filter(c -> c == '\n').count() + 1;
  }

  /** Frame times and the slowest sections, averaged over the last second or so. */
  static String profile(Profiler profiler) {
    final int frames = 60;
    final var sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "\nframe %.2f ms avg  %.2f max", profiler.averageFrameMs(frames),
        profiler.maxFrameMs(frames)));
    final var sections = new ArrayList<>(profiler.sections());
    sections.sort(Comparator.comparingDouble((String n) -> -profiler.average(n, frames)));
    for (int i = 0; i < Math.min(5, sections.size()); i++) {
      final var name = sections.get(i);
      sb.append(String.format(Locale.ROOT, "\n  %-14s %6.2f ms", name, profiler.average(name, frames)));
    }
    return sb.toString();
  }

  @Override
  public void draw(UiCanvas ui, int width, int height) {
    if (!this.engine.isDebugOverlayVisible()) {
      return;
    }
    final float pad = 6f;
    final var font = UiFont.MONOSPACED; // numbers keep their place while they change
    ui.drawRect(pad, pad, ui.textWidth(font, this.text, 1f) + 2 * pad, ui.lineHeight(font, 1f) * this.lines + 2 * pad,
        0f, 0f, 0f, 0.55f);
    ui.drawText(font, this.text, 2 * pad, 2 * pad, 1f, 1f, 1f, 1f, 1f);
    drawGraph(ui, pad, 3 * pad + ui.lineHeight(font, 1f) * this.lines);
  }

  /** One bar per frame, newest on the right: green under 60 fps, yellow under 30, red above; 1 px per 0.5 ms. */
  private void drawGraph(UiCanvas ui, float x, float y) {
    final var profiler = this.engine.profiler();
    final float h = 66f; // 33 ms
    final float w = Profiler.HISTORY;
    ui.drawRect(x, y, w + 2, h + 2, 0f, 0f, 0f, 0.55f);
    final int n = profiler.frames();
    for (int i = 0; i < n; i++) {
      final float ms = profiler.frameMs(i);
      final float bar = Math.min(h, ms * 2f);
      final float r = ms > 16.7f ? 1f : 0.3f;
      final float g = ms > 33.4f ? 0.3f : 1f;
      ui.drawRect(x + 1 + w - 1 - i, y + 1 + h - bar, 1f, bar, r, g, 0.3f, 0.85f);
    }
    ui.drawRect(x + 1, y + 1 + h - 33.3f, w, 1f, 1f, 1f, 1f, 0.5f); // 16.7 ms
  }
}
