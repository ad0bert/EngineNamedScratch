package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaterialTest {
  @Test
  void glassIsTransparentWithItsAlphaHeadOn() {
    final var m = new Material().color(0.1f, 0.2f, 0.3f, 1f).pbr(0f, 0.05f).glass(0.04f);
    assertTrue(m.glass);
    assertTrue(m.transparent, "glass is drawn with the transparent surfaces");
    assertEquals(0.04f, m.color.w);
    assertEquals(0.1f, m.color.x, "the tint is kept");
    assertTrue(m.pbr);
  }

  @Test
  void plainMaterialsAreNotGlass() {
    assertFalse(new Material().color(1f, 1f, 1f, 0.5f).glass);
  }
}
