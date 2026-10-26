package engine.render.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorSpaceTest {
  @Test
  void srgbToLinearMatchesTheStandardCurve() {
    assertEquals(0f, GlSceneRenderer.srgbToLinear(0f), 0f);
    assertEquals(1f, GlSceneRenderer.srgbToLinear(1f), 1e-6f);
    assertEquals(0.2140f, GlSceneRenderer.srgbToLinear(0.5f), 1e-4f);
    assertEquals(0.04f / 12.92f, GlSceneRenderer.srgbToLinear(0.04f), 1e-7f, "the linear toe");
  }
}
