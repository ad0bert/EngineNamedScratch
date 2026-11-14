package engine.kits.toon;

import engine.core.Engine;
import engine.core.EnginePlugin;
import engine.render.PostPass;

/**
 * Ink outlines for the whole game, as a plugin: installs the outline pass ({@link ToonShading#addOutline}) and takes
 * it out again when the engine stops. Materials are made cel-shaded one by one with {@link ToonShading#apply}.
 *
 * <pre>{@code
 * public List<EnginePlugin> plugins() {
 *   return EnginePlugins.defaultsAnd(new ToonPlugin(1.5f));
 * }
 * }</pre>
 */
public final class ToonPlugin implements EnginePlugin {
  private final float width;
  private PostPass outline;

  /** Outlines of {@code width} pixels. */
  public ToonPlugin(float width) {
    this.width = width;
  }

  @Override
  public String id() {
    return "engine.kits.toon";
  }

  @Override
  public void install(Engine engine) {
    this.outline = ToonShading.addOutline(engine.backend().settings(), this.width);
  }

  @Override
  public void uninstall(Engine engine) {
    if (this.outline != null) {
      engine.backend().settings().postPasses.remove(this.outline);
      this.outline = null;
    }
  }

  /** The outline pass while installed (to change its colour, width or threshold), else null. */
  public PostPass outline() {
    return this.outline;
  }
}
