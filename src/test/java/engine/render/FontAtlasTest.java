package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FontAtlasTest {
  @Test
  void proportionalFontsHaveNarrowAndWideGlyphs() {
    final var atlas = new FontAtlas(UiFont.DEFAULT);
    assertTrue(atlas.advance('i') < atlas.advance('W'));
    final var mono = new FontAtlas(UiFont.MONOSPACED);
    assertEquals(mono.advance('i'), mono.advance('W'));
    assertTrue(atlas.lineHeight() >= 16);
  }

  @Test
  void glyphsBeyondAsciiAreAddedOnDemand() {
    final var atlas = new FontAtlas(UiFont.DEFAULT);
    final int before = atlas.version();
    final var umlaut = atlas.glyph('ü');
    assertTrue(atlas.version() > before, "rasterized now");
    assertSame(umlaut, atlas.glyph('ü'), "and cached");
    final int after = atlas.version();
    atlas.glyph('ü');
    assertEquals(after, atlas.version());
    assertTrue(umlaut.advance() > 0);
    // white pixels were drawn into the glyph's cell
    assertTrue(hasInk(atlas, umlaut));
  }

  @Test
  void unknownCharactersFallBackToAReplacementGlyph() {
    final var atlas = new FontAtlas(UiFont.DEFAULT);
    final var missing = atlas.glyph(0x10FFFD); // private use: no font draws it
    assertTrue(missing.advance() > 0);
    assertSame(missing, atlas.glyph(0x10FFFD));
  }

  @Test
  void theAtlasGrowsAndKeepsEarlierGlyphs() {
    final var atlas = new FontAtlas(new UiFont("SansSerif", UiFont.PLAIN, 64));
    final var a = atlas.glyph('A');
    final int height = atlas.height();
    for (int c = 0x100; c < 0x250; c++) { // Latin Extended: plenty of glyphs at 64 px
      atlas.glyph(c);
    }
    assertTrue(atlas.height() > height || atlas.width() > 512, "grew");
    assertSame(a, atlas.glyph('A'));
    assertTrue(hasInk(atlas, a), "the old pixels were copied");
    final float[] uv = new float[4];
    atlas.glyphUv(a, uv);
    assertEquals((float) a.x() / atlas.width(), uv[0], 1e-6f);
  }

  private static boolean hasInk(FontAtlas atlas, FontAtlas.Glyph g) {
    final var img = atlas.image();
    for (int y = g.y(); y < g.y() + g.height(); y++) {
      for (int x = g.x(); x < g.x() + g.width(); x++) {
        final int row = img.height() - 1 - y; // ImageData rows are bottom-up
        if ((img.rgba()[(row * img.width() + x) * 4 + 3] & 0xFF) > 128) {
          return true;
        }
      }
    }
    return false;
  }
}
