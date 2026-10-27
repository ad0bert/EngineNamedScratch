package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetsTest {
  @TempDir
  Path dir;

  @Test
  void classpathOnlyByDefault() throws IOException {
    final var assets = new Assets();
    assertTrue(assets.exists("objects/box.obj"));
    assertTrue(assets.exists("/objects/box.obj"), "a leading slash is ignored");
    assertTrue(assets.readString("objects/box.obj").contains("v "));
    assertNull(assets.file("objects/box.obj"));
    assertFalse(assets.exists("nothing/here.txt"));
    assertThrows(IOException.class, () -> assets.open("nothing/here.txt"));
  }

  @Test
  void rootsOverrideTheClasspathLastAddedFirst() throws IOException {
    final var base = Files.createDirectories(this.dir.resolve("base"));
    final var mod = Files.createDirectories(this.dir.resolve("mod"));
    Files.createDirectories(base.resolve("objects"));
    Files.createDirectories(mod.resolve("objects"));
    Files.writeString(base.resolve("objects/box.obj"), "base");
    Files.writeString(base.resolve("objects/only-base.txt"), "only base");
    Files.writeString(mod.resolve("objects/box.obj"), "mod");

    final var assets = new Assets().addRoot(base).addRoot(mod);
    assertEquals("mod", assets.readString("objects/box.obj"));
    assertEquals("only base", assets.readString("objects/only-base.txt"));
    assertEquals(mod.resolve("objects/box.obj").toAbsolutePath(), assets.file("objects/box.obj"));

    assets.removeRoot(mod);
    assertEquals("base", assets.readString("objects/box.obj"));
    assets.removeRoot(base);
    assertTrue(assets.readString("objects/box.obj").contains("v "), "back to the classpath");
  }

  @Test
  void pathsCannotLeaveARoot() throws IOException {
    final var root = Files.createDirectories(this.dir.resolve("root"));
    Files.writeString(this.dir.resolve("secret.txt"), "secret");
    final var assets = new Assets().addRoot(root);
    assertNull(assets.file("../secret.txt"));
    assertFalse(assets.exists("../secret.txt"));
  }

  @Test
  void theResourceManagerLoadsThroughItsAssets() throws IOException {
    final var root = Files.createDirectories(this.dir.resolve("objects"));
    Files.writeString(root.resolve("tri.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
    final var backend = new ResourceTest.FakeBackend();
    final var res = new ResourceManager(backend, new engine.audio.NullAudioBackend(), new Assets().addRoot(this.dir));
    assertEquals(3, res.mesh("objects/tri.obj").data().indices().length);
  }
}
