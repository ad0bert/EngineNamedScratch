package game;

import engine.core.Engine;
import engine.core.GameState;
import engine.render.UiCanvas;

/** Pushed with P (or Start on a gamepad): the scene stands still under a dimmed screen until P is pressed again. */
final class PauseState implements GameState {
  @Override
  public void enter(Engine engine) {
    engine.audio().mixer().setMuted(true); // sounds would go on while the scene stands still
  }

  @Override
  public void exit(Engine engine) {
    engine.audio().mixer().setMuted(false);
  }

  @Override
  public void update(Engine engine, double dt) {
    if (engine.input().wasActionPressed("pause")) {
      engine.states().pop();
    }
  }

  @Override
  public boolean simulates() {
    return false;
  }

  @Override
  public void draw(Engine engine, UiCanvas ui, int width, int height) {
    ui.drawRect(0f, 0f, width, height, 0f, 0f, 0f, 0.45f);
    final var title = "Paused";
    final float scale = 3f;
    ui.drawText(title, (width - ui.textWidth(title, scale)) / 2f, height / 2f - ui.lineHeight(scale), scale, 1f, 1f,
        1f, 1f);
    final var hint = "P or Start to continue";
    ui.drawText(hint, (width - ui.textWidth(hint, 1.2f)) / 2f, height / 2f + 8f, 1.2f, 0.85f, 0.85f, 0.85f, 1f);
  }
}
