package engine.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import engine.audio.ImpactSoundPlugin;
import engine.debug.DebugOverlayPlugin;
import engine.input.FirstPersonInputPlugin;
import engine.input.MouseReleasePlugin;

/** The plugins that come with the engine, and checks for a list of plugins. */
public final class EnginePlugins {
  private EnginePlugins() {
  }

  /**
   * What a game gets unless it chooses otherwise: Esc frees the mouse ({@link MouseReleasePlugin}), first-person key
   * bindings ({@link FirstPersonInputPlugin}), collision sounds ({@link ImpactSoundPlugin}) and the F3 debug text
   * ({@link DebugOverlayPlugin}). New instances on every call.
   */
  public static List<EnginePlugin> defaults() {
    return List.of(new MouseReleasePlugin(), new FirstPersonInputPlugin(), new ImpactSoundPlugin(),
        new DebugOverlayPlugin());
  }

  /** {@link #defaults()} without the plugins of the given classes. */
  @SafeVarargs
  public static List<EnginePlugin> defaultsWithout(Class<? extends EnginePlugin>... left) {
    final List<EnginePlugin> list = new ArrayList<>();
    outer: for (EnginePlugin p : defaults()) {
      for (Class<? extends EnginePlugin> c : left) {
        if (c.isInstance(p)) {
          continue outer;
        }
      }
      list.add(p);
    }
    return List.copyOf(list);
  }

  /** {@link #defaults()} followed by {@code more}. */
  public static List<EnginePlugin> defaultsAnd(EnginePlugin... more) {
    final List<EnginePlugin> list = new ArrayList<>(defaults());
    list.addAll(List.of(more));
    return List.copyOf(list);
  }

  /**
   * Checks a list in install order: ids are unique and every plugin's {@link EnginePlugin#requires} comes earlier.
   *
   * @throws IllegalArgumentException if they are not
   */
  public static void check(Collection<? extends EnginePlugin> plugins) {
    final Set<String> seen = new HashSet<>();
    for (EnginePlugin p : plugins) {
      for (String needed : p.requires()) {
        if (!seen.contains(needed)) {
          throw new IllegalArgumentException("plugin " + p.id() + " needs " + needed + " to be installed before it");
        }
      }
      if (!seen.add(p.id())) {
        throw new IllegalArgumentException("plugin " + p.id() + " is in the list twice");
      }
    }
  }
}
