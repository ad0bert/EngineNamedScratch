package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
  @TempDir
  Path dir;

  @Test
  void configReadsTheWindowOptionsAndDerivesAnId() {
    final var p = new Properties();
    p.setProperty("app.title", "My Game: The Sequel!");
    p.setProperty("app.fullscreen", "true");
    p.setProperty("app.vsync", "false");
    p.setProperty("app.updatesPerSecond", "50");
    final var c = EngineConfig.of(p);
    assertEquals("my-game-the-sequel", c.id());
    assertTrue(c.fullscreen());
    assertFalse(c.vsync());
    assertEquals(0.02, c.updateStep(), 1e-12);

    p.setProperty("app.id", "sequel");
    assertEquals("sequel", EngineConfig.of(p).id());
    final var old = new EngineConfig("Old Style", "1", 640, 480, 0.01);
    assertEquals("old-style", old.id());
    assertFalse(old.fullscreen());
    assertTrue(old.vsync());
    assertEquals(1024, old.withSize(1024, 768).width());
    assertTrue(old.withFullscreen(true).fullscreen());
    assertEquals("game", EngineConfig.idOf("!!!"));
  }

  @Test
  void userDirsFollowThePlatformConventions() {
    final var win = UserDirs.of("g", "Windows 11", "C:\\Users\\a", Map.of("APPDATA", "C:\\Users\\a\\AppData\\Roaming"));
    assertEquals(win.configPath(), win.dataPath());
    assertTrue(win.configPath().toString().endsWith("g"));

    final var mac = UserDirs.of("g", "Mac OS X", "/Users/a", Map.of());
    assertEquals(Path.of("/Users/a/Library/Application Support/g"), mac.configPath());

    final var linux = UserDirs.of("g", "Linux", "/home/a", Map.of());
    assertEquals(Path.of("/home/a/.config/g"), linux.configPath());
    assertEquals(Path.of("/home/a/.local/share/g"), linux.dataPath());

    final var xdg = UserDirs.of("g", "Linux", "/home/a", Map.of("XDG_CONFIG_HOME", "/c", "XDG_DATA_HOME", "/d"));
    assertEquals(Path.of("/c/g"), xdg.configPath());
    assertEquals(Path.of("/d/g"), xdg.dataPath());
  }

  @Test
  void settingsSurviveASaveAndForgiveBadValues() throws IOException {
    final var file = this.dir.resolve("sub/settings.properties");
    final var s = Settings.load(file);
    assertTrue(s.getBoolean("video.vsync", true), "a missing file gives defaults");
    s.set("video.vsync", false).set("video.width", 1280).set("audio.master", 0.5f).set("bad", "x");
    s.save();
    assertTrue(Files.exists(file));
    try (var files = Files.list(file.getParent())) {
      assertEquals(1, files.count(), "no temporary file left behind");
    }

    final var again = Settings.load(file);
    assertFalse(again.getBoolean("video.vsync", true));
    assertEquals(1280, again.getInt("video.width", 0));
    assertEquals(0.5f, again.getFloat("audio.master", 1f));
    assertEquals(7, again.getInt("bad", 7));
    assertEquals("x", again.get("bad", null));
    again.remove("bad");
    assertNotEquals("x", again.get("bad", "-"));
  }

  @Test
  void theDebugOverlayCanStartHiddenAndBeSwitched() {
    final var config = new EngineConfig("Test", "1", 8, 8, 1 / 60.0);
    final var shown = new Engine(config, e -> {
    });
    assertTrue(shown.isDebugOverlayVisible(), "visible by default");
    shown.setDebugOverlayVisible(false);
    assertFalse(shown.isDebugOverlayVisible());

    System.setProperty("engine.debugOverlay", "false");
    try {
      assertFalse(new Engine(config, e -> {
      }).isDebugOverlayVisible());
    } finally {
      System.clearProperty("engine.debugOverlay");
    }
  }

  @Test
  void theEngineKnowsItsOwnVersion() {
    assertFalse(Engine.VERSION.isBlank());
    assertNotEquals("unknown", Engine.VERSION, "version.properties is filtered into the build");
  }
}
