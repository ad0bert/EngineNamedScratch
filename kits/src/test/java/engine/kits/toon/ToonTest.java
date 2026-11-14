package engine.kits.toon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import engine.core.Engine;
import engine.core.EngineConfig;
import engine.core.EnginePlugin;
import engine.core.Game;
import engine.render.Material;
import engine.render.PostPass;

class ToonTest {
  @Test
  void theShadersShipInTheJar() {
    for (String s : List.of(ToonShading.SHADER, ToonShading.OUTLINE)) {
      assertNotNull(ToonTest.class.getClassLoader().getResource(s), s);
    }
  }

  @Test
  void materialsBecomeCelShaded() {
    final var m = ToonShading.apply(new Material().color(1f, 0.5f, 0.2f, 1f), 4, 0.9f);
    assertEquals(ToonShading.SHADER, m.fragmentShader);
    assertNull(m.vertexShader, "the engine's vertex shader");
    assertEquals(4f, m.params.get("uBands"));
    assertEquals(0.5f, m.params.get("uSoftness"), "clamped");
    assertThrows(IllegalArgumentException.class, () -> ToonShading.apply(new Material(), 0));
  }

  @Test
  void thePluginAddsTheOutlineAndTakesItOut() {
    final var plugin = new ToonPlugin(2f);
    final var engine = new Engine(new EngineConfig("Toon test", "1", 8, 8, 1 / 60.0), new Game() {
      @Override
      public List<EnginePlugin> plugins() {
        return List.of(plugin);
      }

      @Override
      public void init(Engine e) {
      }
    });
    engine.startHeadless();
    final var passes = engine.backend().settings().postPasses;
    assertEquals(1, passes.size());
    final PostPass outline = passes.getFirst();
    assertSame(outline, plugin.outline());
    assertEquals(ToonShading.OUTLINE, outline.fragmentShader);
    assertEquals(PostPass.Stage.DISPLAY, outline.stage);
    assertEquals(2f, outline.params.get("uWidth"));
    assertTrue(engine.hasPlugin("engine.kits.toon"));
    engine.stop();
    assertTrue(passes.isEmpty(), "removed when the engine stops");
  }
}
