package engine.render.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ShaderIncludesTest {
  private static final Map<String, String> FILES = Map.of(
      "shaders/a.frag", "#version 330 core\n#include \"lib/b.glsl\"\nvoid main() {}\n",
      "shaders/lib/b.glsl", "#include \"c.glsl\"\nfloat b() { return c(); }\n",
      "shaders/lib/c.glsl", "float c() { return 1.0; }\n",
      "shaders/twice.frag", "#version 330 core\n#include \"lib/b.glsl\"\n#include \"lib/c.glsl\"\nvoid main() {}\n",
      "game/fx/glow.frag", "#version 330 core\n#include \"lib/c.glsl\"\n#include \"../common.glsl\"\nvoid main() {}\n",
      "game/common.glsl", "// common\n",
      "game/broken.frag", "#version 330 core\n\n#include \"nowhere.glsl\"\n",
      "game/loop.glsl", "#include \"loop.glsl\"\n");

  private static String read(String path) {
    return FILES.get(path);
  }

  @Test
  void includesAreExpandedOnceWithLineDirectives() {
    final var e = ShaderIncludes.expand("shaders/a.frag", ShaderIncludesTest::read);
    assertEquals(List.of("shaders/a.frag", "shaders/lib/b.glsl", "shaders/lib/c.glsl"), e.files());
    final String text = e.text();
    assertTrue(text.startsWith("#version 330 core\n#line 1 1\n#line 1 2\nfloat c()"), text);
    assertTrue(text.contains("float b()"));
    assertTrue(text.contains("#line 3 0\nvoid main()"), "back in a.frag at its line 3: " + text);
    assertEquals("0 = shaders/a.frag, 1 = shaders/lib/b.glsl, 2 = shaders/lib/c.glsl", e.legend());

    final var twice = ShaderIncludes.expand("shaders/twice.frag", ShaderIncludesTest::read).text();
    assertEquals(1, Pattern.compile("float c\\(\\)").matcher(twice).results().count(), "c.glsl only once");
    assertTrue(twice.contains("#line 4 0\nvoid main()"), twice);
  }

  @Test
  void pathsAreFoundNextToTheShaderThenInTheEngineLibraryThenAsGiven() {
    final var e = ShaderIncludes.expand("game/fx/glow.frag", ShaderIncludesTest::read);
    assertEquals(List.of("game/fx/glow.frag", "shaders/lib/c.glsl", "game/common.glsl"), e.files());
    assertEquals("shaders/lib/c.glsl", ShaderIncludes.resolve("x.frag", "shaders/lib/c.glsl", ShaderIncludesTest::read));
    assertEquals("shaders/lib/c.glsl", ShaderIncludes.resolve("shaders/a.frag", "/shaders/lib/c.glsl",
        ShaderIncludesTest::read));
    assertNull(ShaderIncludes.resolve("shaders/a.frag", "../../../x.glsl", ShaderIncludesTest::read));
    assertEquals("a/c", ShaderIncludes.normalize("a/./b/../c"));
    assertNull(ShaderIncludes.normalize("../a"));
  }

  @Test
  void missingFilesSayWhereTheyWereIncluded() {
    final var e = assertThrows(IllegalArgumentException.class,
        () -> ShaderIncludes.expand("game/broken.frag", ShaderIncludesTest::read));
    assertTrue(e.getMessage().contains("game/broken.frag:3") && e.getMessage().contains("nowhere.glsl"),
        e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> ShaderIncludes.expand("nope.frag", ShaderIncludesTest::read));
    // a file including itself is included once, not for ever
    assertFalse(ShaderIncludes.expand("game/loop.glsl", ShaderIncludesTest::read).text().contains("#include"));
  }

  /** Every shader the engine ships expands: its includes exist, and nothing is left unexpanded. */
  @Test
  void theEnginesShadersExpand() throws IOException, URISyntaxException {
    final Path root = Path.of(ShaderIncludesTest.class.getClassLoader().getResource("shaders/basic.frag").toURI())
        .getParent();
    final List<Path> shaders;
    try (Stream<Path> files = Files.walk(root)) {
      shaders = files.filter(p -> p.toString().endsWith(".vert") || p.toString().endsWith(".frag")).toList();
    }
    assertTrue(shaders.size() > 20, "found " + shaders.size());
    for (Path p : shaders) {
      final String resource = "shaders/" + root.relativize(p).toString().replace('\\', '/');
      final var e = ShaderIncludes.expand(resource, ShaderProgram::read);
      assertFalse(Pattern.compile("(?m)^\\s*#\\s*include").matcher(e.text()).find(), resource);
      assertTrue(e.text().startsWith("#version 330 core"), resource);
    }
    final var basic = ShaderIncludes.expand("shaders/basic.frag", ShaderProgram::read);
    assertEquals(List.of("shaders/basic.frag", "shaders/lib/surface.glsl", "shaders/lib/lighting.glsl"),
        basic.files());
    assertTrue(basic.text().contains("void shade(Surface s)"));
  }
}
