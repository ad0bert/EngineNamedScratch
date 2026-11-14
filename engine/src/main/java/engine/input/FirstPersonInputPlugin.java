package engine.input;

import engine.core.Engine;
import engine.core.EnginePlugin;
import engine.serialization.ComponentRegistry;

/**
 * The bindings {@link FirstPersonController} and {@code ActionCommandSource} read (WASD and the arrows move, Space
 * jumps and flies up, Shift flies down, the gamepad's Y jumps; see {@link FirstPersonController#bindDefaults}), and
 * the controller's serializer. A default plugin; a game with other controls leaves it out and binds its own actions.
 */
public final class FirstPersonInputPlugin implements EnginePlugin {
  @Override
  public String id() {
    return "engine.first-person-input";
  }

  @Override
  public void install(Engine engine) {
    FirstPersonController.bindDefaults(engine.input());
  }

  @Override
  public void registerComponents(ComponentRegistry registry) {
    registry.register(new ComponentRegistry.FirstPersonSerializer());
  }
}
