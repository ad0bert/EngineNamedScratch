package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;

class LodTest {
  private static final AtomicInteger IDS = new AtomicInteger();

  private static GpuMesh mesh(MeshData data) {
    final int id = IDS.incrementAndGet();
    return new GpuMesh() {
      @Override
      public MeshData data() {
        return data;
      }

      @Override
      public int id() {
        return id;
      }

      @Override
      public void dispose() {
      }
    };
  }

  @Test
  void clusteringReducesASphereAndKeepsItsShape() {
    final var sphere = MeshFactory.sphere(1f, 64, 32);
    final int full = sphere.indices().length / 3;
    final var half = MeshSimplifier.simplify(sphere, 0.25f);
    final int tris = half.indices().length / 3;
    assertTrue(tris < full * 0.35f && tris > full * 0.15f, tris + " of " + full);
    assertEquals(half.vertices().length / MeshData.FLOATS_PER_VERTEX * MeshData.FLOATS_PER_VERTEX,
        half.vertices().length);
    // still a sphere of radius about 1, with unit normals pointing out
    for (int i = 0; i < half.vertices().length; i += MeshData.FLOATS_PER_VERTEX) {
      final var p = new Vector3f(half.vertices()[i], half.vertices()[i + 1], half.vertices()[i + 2]);
      final var n = new Vector3f(half.vertices()[i + 3], half.vertices()[i + 4], half.vertices()[i + 5]);
      assertEquals(1f, p.length(), 0.15f);
      assertEquals(1f, n.length(), 1e-3f);
      assertTrue(n.dot(p) > 0.8f * p.length());
    }
    for (final int index : half.indices()) {
      assertTrue(index >= 0 && index < half.vertices().length / MeshData.FLOATS_PER_VERTEX);
    }
    assertSame(sphere, MeshSimplifier.simplify(sphere, 1f));
  }

  @Test
  void aBoxKeepsItsCornersAndHardEdges() {
    final var cube = MeshFactory.cube(1f); // 24 vertices: each face its own normals
    final var same = MeshSimplifier.cluster(cube, 0.1f);
    assertEquals(12, same.indices().length / 3, "small cells change nothing");
    assertEquals(24, same.vertices().length / MeshData.FLOATS_PER_VERTEX);
    final var gone = MeshSimplifier.cluster(cube, 10f); // every face collapses to a point
    assertEquals(0, gone.indices().length);
    assertThrows(IllegalArgumentException.class, () -> MeshSimplifier.cluster(MeshData.points(new float[8]), 1f));
  }

  @Test
  void skinWeightsComeAlong() {
    final var cube = MeshFactory.cube(1f);
    final float[] skin = new float[24 * MeshData.SKIN_FLOATS_PER_VERTEX];
    for (int v = 0; v < 24; v++) {
      skin[v * MeshData.SKIN_FLOATS_PER_VERTEX + 4] = 1f;
    }
    final var simplified = MeshSimplifier.cluster(cube.withSkin(skin), 0.1f);
    assertNotNull(simplified.skin());
    assertEquals(24 * MeshData.SKIN_FLOATS_PER_VERTEX, simplified.skin().length);
  }

  @Test
  void theQueuePicksTheLevelByDistanceAndCulls() {
    final var full = mesh(MeshFactory.sphere(1f, 32, 16));
    final var mid = mesh(MeshSimplifier.simplify(full.data(), 0.3f));
    final var low = mesh(MeshSimplifier.simplify(full.data(), 0.08f));
    final var scene = new Scene();
    final List<MeshRenderer> renderers = new ArrayList<>();
    for (final float z : new float[] { -5f, -30f, -80f, -200f }) {
      final var e = new Entity();
      e.transform().position().set(0f, 0f, z);
      final var r = e.add(new MeshRenderer(full, new Material()));
      r.addLod(20f, mid).addLod(60f, low);
      r.cullDistance = 150f;
      renderers.add(r);
      scene.add(e);
    }
    assertThrows(IllegalArgumentException.class, () -> renderers.get(0).addLod(10f, low), "nearest first");
    scene.flush();
    final var queue = new RenderQueue();
    queue.collect(scene, 1f, new Vector3f());
    final var proj = new Matrix4f().setPerspective((float) Math.toRadians(60), 1f, 0.1f, 500f);
    final List<MeshRenderer> out = new ArrayList<>();
    queue.cull(new FrustumIntersection(proj), RenderQueue.Pass.MAIN, out);
    assertEquals(3, out.size(), "the one at 200 m is culled");
    assertSame(full, renderers.get(0).drawnMesh());
    assertSame(mid, renderers.get(1).drawnMesh());
    assertSame(low, renderers.get(2).drawnMesh());
    assertTrue(RenderQueue.sameBatch(renderers.get(1), renderers.get(1), true));

    queue.setLodBias(2f); // keeps detail twice as far
    out.clear();
    queue.cull(new FrustumIntersection(proj), RenderQueue.Pass.MAIN, out);
    assertEquals(4, out.size());
    assertSame(full, renderers.get(1).drawnMesh());
    assertSame(mid, renderers.get(2).drawnMesh());
    assertSame(low, renderers.get(3).drawnMesh());
  }
}
