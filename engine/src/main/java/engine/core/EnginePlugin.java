package engine.core;

import java.util.List;

import engine.serialization.ComponentRegistry;

/**
 * A piece of functionality that is installed into an engine as a whole: systems, step hooks, overlays, input
 * bindings, message receivers and component serializers that belong together. The engine's core (scene, rendering,
 * physics, sound, input plumbing) is always there; everything a game may or may not want comes as a plugin, and so
 * does what games and their authors add to share between games.
 *
 * <p>
 * The game chooses its plugins ({@link Game#plugins()}, by default {@link EnginePlugins#defaults()}). The engine
 * installs them in that order, after its own systems and before {@link Game#init}, and uninstalls them in the
 * opposite order after {@link Game#shutdown}. Both happen on the update thread.
 *
 * <pre>{@code
 * public final class WeatherPlugin implements EnginePlugin {
 *   private final Weather weather = new Weather();
 *
 *   public void install(Engine engine) {
 *     engine.addSystem(this.weather);
 *     engine.input().bind("toggle_rain", KeyEvent.VK_R);
 *   }
 *
 *   public void uninstall(Engine engine) {
 *     engine.removeSystem(this.weather);
 *   }
 *
 *   public void registerComponents(ComponentRegistry registry) {
 *     registry.register(new FieldSerializer<>("RainZone", RainZone.class, ctx -> new RainZone()));
 *   }
 * }
 * }</pre>
 */
public interface EnginePlugin {
  /** A unique name, e.g. {@code "com.example.weather"}; by default the class name. */
  default String id() {
    return getClass().getName();
  }

  /** The plugin's version, for {@link Engine#pluginSignature()}; empty by default. */
  default String version() {
    return "";
  }

  /** The ids of plugins that must be installed before this one (earlier in the list). */
  default List<String> requires() {
    return List.of();
  }

  /**
   * Whether the plugin changes the simulation or what goes over the network, so that a host and its clients must run
   * the same version of it ({@link Engine#pluginSignature()}). False by default: a debug display does not matter to
   * the other side.
   */
  default boolean networked() {
    return false;
  }

  /** Adds the plugin's systems, hooks, overlays, bindings and receivers to {@code engine}. */
  void install(Engine engine);

  /** Takes out what {@link #install} added; called when the engine shuts down. Nothing by default. */
  default void uninstall(Engine engine) {
  }

  /**
   * Registers serializers for the plugin's components, so that scenes and prefabs with them can be saved and loaded
   * ({@link Engine#components()}). Nothing by default.
   */
  default void registerComponents(ComponentRegistry registry) {
  }
}
