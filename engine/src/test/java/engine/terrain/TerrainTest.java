package engine.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.physics.BoxCollider;
import engine.physics.MeshCollider;
import engine.physics.PhysicsSystem;
import engine.physics.Rigidbody;
import engine.render.ImageData;
import engine.render.MeshData;
import engine.render.MeshRenderer;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.Material;
import engine.render.RenderBackend;
import engine.resources.ResourceManager;

class TerrainTest {
  /** Hands out mesh handles; nothing else is needed here. */
  private static ResourceManager resources() {
    final RenderBackend backend = (RenderBackend) java.lang.reflect.Proxy.newProxyInstance(
        TerrainTest.class.getClassLoader(), new Class<?>[] { RenderBackend.class }, (proxy, m, args) -> switch (m
            .getName()) {
        case "createMesh" -> new GpuMesh() {
          @Override
          public MeshData data() {
            return (MeshData) args[0];
          }

          @Override
          public int id() {
            return System.identityHashCode(this);
          }

          @Override
          public void dispose() {
          }
        };
        case "whiteTexture" -> (GpuTexture) () -> {
        };
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
        });
    return new ResourceManager(backend);
  }

  /** A ramp: height = 0.5 x. */
  private static Heightfield ramp(int n, float spacing) {
    return Heightfield.generate(n, n, spacing, (x, z) -> 0.5f * x);
  }

  @Test
  void heightsAndNormals() {
    final var f = ramp(5, 2f);
    assertEquals(8f, f.width());
    assertEquals(3f, f.heightAt(6f, 3f), 1e-5f);
    assertEquals(4f, f.heightAt(100f, 0f), 1e-5f, "clamped at the edge");
    final var n = f.normal(2, 2, new Vector3f());
    assertEquals(new Vector3f(-0.5f, 1f, 0f).normalize().x, n.x, 1e-5f);

    // the two triangles of a cell: (0,0)=0, (1,0)=1, (0,1)=2, (1,1)=4
    final var cell = new Heightfield(2, 2, 1f, new float[] { 0f, 1f, 2f, 4f });
    assertEquals(0.5f + 3f * 0.25f, cell.heightAt(0.5f, 0.25f), 1e-5f, "on the (0,0)-(1,0)-(1,1) triangle");
    assertEquals(2f * 0.75f + 2f * 0.25f, cell.heightAt(0.25f, 0.75f), 1e-5f, "on the other one");
    assertThrows(IllegalArgumentException.class, () -> new Heightfield(1, 2, 1f, new float[2]));
  }

  @Test
  void imagesAndNoise() {
    final byte w = (byte) 255;
    final var img = new ImageData(2, 2, new byte[] { 0, 0, 0, w, w, w, w, w, 0, 0, 0, w, 0, 0, 0, w });
    final var f = Heightfield.fromImage(img, 1f, -1f, 3f);
    assertEquals(-1f, f.sample(0, 0));
    assertEquals(3f, f.sample(1, 0));
    final float a = Heightfield.fbm(3.3f, 7.1f, 0.1f, 4, 42L);
    assertEquals(a, Heightfield.fbm(3.3f, 7.1f, 0.1f, 4, 42L), "deterministic");
    float min = 1f;
    float max = -1f;
    for (int i = 0; i < 2000; i++) {
      final float v = Heightfield.fbm(i * 0.37f, i * 0.91f, 0.2f, 5, 7L);
      min = Math.min(min, v);
      max = Math.max(max, v);
    }
    assertTrue(min >= -1f && max <= 1f && max - min > 0.8f, min + " .. " + max);
  }

  @Test
  void chunkMeshesFaceUpAndHaveSkirts() {
    final var f = ramp(9, 1f);
    final var full = Terrain.chunkMesh(f, 0, 0, 8, 8, 1, 4f, 0f);
    assertEquals(81 * MeshData.FLOATS_PER_VERTEX, full.vertices().length);
    assertEquals(8 * 8 * 2 * 3, full.indices().length);
    final var v = full.vertices();
    for (int t = 0; t < full.indices().length; t += 3) {
      final var a = pos(v, full.indices()[t]);
      final var b = pos(v, full.indices()[t + 1]);
      final var c = pos(v, full.indices()[t + 2]);
      assertTrue(new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).y > 0f, "faces up");
    }
    final var coarse = Terrain.chunkMesh(f, 0, 0, 8, 8, 4, 4f, 2f);
    final int skirtVerts = 4 * 3; // 3 samples per side at step 4
    assertEquals((9 + skirtVerts) * MeshData.FLOATS_PER_VERTEX, coarse.vertices().length);
    assertEquals((2 * 2 * 2 + 4 * 2 * 2) * 3, coarse.indices().length);
    // the skirt of the z = 0 edge faces -z
    final var cv = coarse.vertices();
    boolean outwards = false;
    for (int t = 2 * 2 * 2 * 3; t < coarse.indices().length; t += 3) {
      final var a = pos(cv, coarse.indices()[t]);
      final var b = pos(cv, coarse.indices()[t + 1]);
      final var c = pos(cv, coarse.indices()[t + 2]);
      final var n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
      if (a.z == 0f && b.z == 0f && c.z == 0f) {
        assertTrue(n.z < 0f, "outwards");
        outwards = true;
      }
    }
    assertTrue(outwards);
  }

  private static Vector3f pos(float[] v, int i) {
    final int o = i * MeshData.FLOATS_PER_VERTEX;
    return new Vector3f(v[o], v[o + 1], v[o + 2]);
  }

  @Test
  void buildsChunksWithLevelsAndColliders() {
    final var res = resources();
    final var scene = new Scene();
    final var field = Heightfield.generate(41, 25, 1f, (x, z) -> 2f * Heightfield.fbm(x, z, 0.05f, 3, 1L));
    final var settings = new Terrain.Settings();
    settings.chunkCells = 16;
    settings.levels = 3;
    final var root = Terrain.build(scene, res, field, new Material(), settings, "hills");
    root.transform().position().set(-20f, 0f, -12f);
    scene.flush();
    // 40 x 24 cells in 16-cell chunks: 3 x 2 chunks, the last column 8 wide, the last row 8 deep
    final var chunks = scene.components(MeshRenderer.class);
    assertEquals(6, chunks.size());
    assertEquals(6, scene.components(MeshCollider.class).size());
    final var first = scene.entities().stream().filter(e -> e.name().equals("hills-0-0")).findFirst().get();
    assertEquals(2, first.get(MeshRenderer.class).lodCount());
    assertEquals(40f, first.get(MeshRenderer.class).lodDistance(0));
    assertEquals(80f, first.get(MeshRenderer.class).lodDistance(1));
    final var corner = scene.entities().stream().filter(e -> e.name().equals("hills-2-1")).findFirst().get();
    assertEquals(2, corner.get(MeshRenderer.class).lodCount(), "8 cells still take steps of 4");
    assertThrows(IllegalArgumentException.class, () -> {
      settings.chunkCells = 6;
      Terrain.build(scene, res, field, new Material(), settings, "bad");
    });
  }

  @Test
  void aCrateComesToRestOnTheSurface() {
    final var res = resources();
    final var scene = new Scene();
    final var field = Heightfield.generate(33, 33, 0.5f, (x, z) -> 0.25f * x); // 16 m, rising 0.25 per metre
    Terrain.build(scene, res, field, new Material(), new Terrain.Settings(), "slope");
    final var crate = new Entity("crate");
    crate.transform().position().set(8f, 10f, 8f);
    crate.add(new BoxCollider(0.25f, 0.25f, 0.25f));
    final var body = crate.add(new Rigidbody());
    body.friction = 1f;
    scene.add(crate);
    scene.flush();
    final var physics = new PhysicsSystem(new MessageService());
    for (int i = 0; i < 180; i++) {
      physics.update(scene, 1.0 / 60.0);
    }
    final var p = crate.transform().position();
    final float ground = field.heightAt(p.x, p.z);
    assertTrue(p.y > ground && p.y < ground + 0.6f, p + " over " + ground);
    // straight down onto a vertex of the grid: exactly on the faces of the collider's boxes
    final var hit = physics.raycast(scene, new Vector3f(3f, 20f, 3f), new Vector3f(0f, -1f, 0f), 50f);
    assertEquals(0.75f, hit.get().point().y, 1e-3f);
  }
}
