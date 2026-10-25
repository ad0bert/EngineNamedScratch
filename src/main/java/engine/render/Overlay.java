package engine.render;

/** 2D content drawn on top of the scene every frame (HUD, debug text). */
@FunctionalInterface
public interface Overlay {
  void draw(UiCanvas ui, int width, int height);
}
