package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import engine.render.ImageData;
import engine.render.MeshData;

class HotReloadTest {
  @TempDir Path dir;

  private static void png(Path file, int size) throws IOException {
    Files.createDirectories(file.getParent());
    ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
  }

  /** Polls until {@code count} paths came in or five seconds passed (file notifications take a moment). */
  private static Set<String> await(AssetWatcher w, int count) throws InterruptedException {
    final Set<String> seen = new TreeSet<>();
    final long end = System.nanoTime() + 5_000_000_000L;
    while (seen.size() < count && System.nanoTime() < end) {
      seen.addAll(w.poll());
      Thread.sleep(20);
    }
    return seen;
  }

  @Test
  void reportsChangedFilesOnceSettledIncludingNewSubdirectories() throws Exception {
    Files.writeString(this.dir.resolve("a.txt"), "1");
    try (var w = new AssetWatcher(List.of(this.dir))) {
      w.settleMillis = 50;
      assertTrue(w.poll().isEmpty());
      Files.writeString(this.dir.resolve("a.txt"), "2");
      Files.writeString(this.dir.resolve("a.txt"), "3"); // written twice: reported once
      Files.createDirectories(this.dir.resolve("levels/one"));
      Files.writeString(this.dir.resolve("levels/one/map.json"), "{}");
      final var seen = await(w, 2);
      assertEquals(Set.of("a.txt", "levels/one/map.json"), seen);
      Files.writeString(this.dir.resolve("levels/one/map.json"), "{\"x\":1}"); // the new directory is watched
      assertEquals(Set.of("levels/one/map.json"), await(w, 1));
      assertTrue(w.poll().isEmpty());
    }
  }

  @Test
  void reloadsTexturesAndMeshesInPlace() throws Exception {
    png(this.dir.resolve("textures/t.png"), 2);
    Files.writeString(this.dir.resolve("m.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
    final var backend = new ResourceTest.FakeBackend();
    final var assets = new Assets().addRoot(this.dir);
    final var res = new ResourceManager(backend, null, assets);
    final var colour = res.texture("textures/t.png");
    final var data = res.dataTexture("textures/t.png");
    final var mesh = res.mesh("m.obj");
    assertEquals(0, res.reload("other.png"));

    png(this.dir.resolve("textures/t.png"), 4);
    assertEquals(2, res.reload("textures/t.png"), "the colour and the data texture");
    assertEquals(4, ((ImageData) backend.updated.get(0)).width());
    assertTrue(colour != data);

    Files.writeString(this.dir.resolve("m.obj"), "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 1 1 0\nf 1 2 3\nf 2 4 3\n");
    assertEquals(1, res.reload("m.obj"));
    assertEquals(2, ((MeshData) backend.updated.get(2)).indices().length / 3);
    assertEquals(mesh, res.mesh("m.obj"), "same handle");

    Files.writeString(this.dir.resolve("m.obj"), "this is not an obj");
    final List<Object> before = new ArrayList<>(backend.updated);
    res.reload("m.obj"); // logged, the mesh stays
    assertEquals(before.size(), backend.updated.size());
  }
}
