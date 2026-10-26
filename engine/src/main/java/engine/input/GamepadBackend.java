package engine.input;

/**
 * Reads gamepads from the platform and writes them into an {@link InputState} (slots, buttons and axes in the
 * {@link Gamepad} layout). {@code engine.input.jinput} implements it with JInput; {@link #NONE} is used when no
 * implementation can be loaded, so a game simply sees no gamepads.
 */
public interface GamepadBackend {
  GamepadBackend NONE = new GamepadBackend() {
    @Override
    public void poll(InputState state) {
    }

    @Override
    public void rescan() {
    }

    @Override
    public void dispose() {
    }
  };

  /** Reads the current state of every pad; called by the engine at the start of each fixed step. */
  void poll(InputState state);

  /** Looks for pads plugged in since the last scan (done again at the next {@link #poll}). */
  void rescan();

  void dispose();
}
