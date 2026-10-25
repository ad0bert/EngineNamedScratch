package engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.jogamp.common.nio.Buffers;

import engine.ecs.Entity;
import engine.ecs.Scene;

class RenderQueueTest {
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

  private final GpuMesh cube = mesh(MeshFactory.cube(0.5f));
  private final Material material = new Material();
  private final Scene scene = new Scene();
  private final RenderQueue queue = new RenderQueue(16f);

  private static final float CUBE_RADIUS = (float) Math.sqrt(3) * 0.5f;

  private MeshRenderer add(float x, float y, float z, boolean isStatic) {
    final var e = new Entity();
    e.transform().position().set(x, y, z);
    final var r = e.add(new MeshRenderer(this.cube, this.material));
    r.setStaticGeometry(isStatic);
    this.scene.add(e);
    return r;
  }

  private static FrustumIntersection frustum() {
    final var proj = new Matrix4f().setPerspective((float) Math.toRadians(60), 1.5f, 0.1f, 300f);
    final var view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
    return new FrustumIntersection(proj.mul(view));
  }

  private List<MeshRenderer> cull(FrustumIntersection f, RenderQueue.Pass pass) {
    final List<MeshRenderer> out = new ArrayList<>();
    this.queue.cull(f, pass, out);
    return out;
  }

  private Set<MeshRenderer> identitySet(List<MeshRenderer> list) {
    final Set<MeshRenderer> s = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    s.addAll(list);
    assertEquals(list.size(), s.size(), "an object must be reported at most once");
    return s;
  }

  @Test
  void cullingMatchesABruteForceTestForStaticAndMovingObjects() {
    final var rnd = new Random(42);
    final List<MeshRenderer> all = new ArrayList<>();
    final List<Vector3f> expectedCenters = new ArrayList<>();
    for (int i = 0; i < 1500; i++) {
      final var pos = new Vector3f(rnd.nextFloat() * 400f - 200f, rnd.nextFloat() * 40f - 20f,
          rnd.nextFloat() * 400f - 200f);
      final boolean isStatic = rnd.nextBoolean();
      final var r = add(pos.x, pos.y, pos.z, isStatic);
      all.add(r);
      expectedCenters.add(pos);
    }
    final var big = add(0f, -5f, -60f, true); // far larger than a grid cell
    big.entity().transform().scale().set(80f);
    big.invalidate();
    all.add(big);
    expectedCenters.add(new Vector3f(0f, -5f, -60f));
    this.scene.flush();

    // move half of the non-static objects after the snapshot so they have to be interpolated
    for (int i = 0; i < 1500; i += 2) {
      if (!all.get(i).isStaticGeometry()) {
        all.get(i).entity().transform().position().add(10f, 0f, 0f);
        expectedCenters.get(i).add(5f, 0f, 0f); // alpha 0.5 of a 10 unit move
      }
    }

    this.queue.collect(this.scene, 0.5f, new Vector3f());
    final var f = frustum();
    final var got = identitySet(cull(f, RenderQueue.Pass.MAIN));

    int expectedVisible = 0;
    for (int i = 0; i < all.size(); i++) {
      final var c = expectedCenters.get(i);
      final float radius = all.get(i) == big ? CUBE_RADIUS * 80f : CUBE_RADIUS;
      final boolean visible = f.testSphere(c.x, c.y, c.z, radius);
      expectedVisible += visible ? 1 : 0;
      assertEquals(visible, got.contains(all.get(i)), "object " + i);
    }
    assertTrue(expectedVisible > 20 && expectedVisible < 1400, "test scene should cull some but not all");
    assertEquals(all.size(), this.queue.candidates());
  }

  @Test
  void matrixCullingGivesTheSameResultAsFrustumCullingForStaticWorlds() {
    final var rnd = new Random(5);
    for (int i = 0; i < 3000; i++) {
      add(rnd.nextFloat() * 800f - 400f, rnd.nextFloat() * 20f, rnd.nextFloat() * 800f - 400f, i % 4 != 0);
    }
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());

    final var viewProj = new Matrix4f().setPerspective((float) Math.toRadians(70), 1.6f, 0.1f, 250f)
        .mul(new Matrix4f().lookAt(10f, 5f, 10f, 100f, 5f, -50f, 0f, 1f, 0f));
    final var expected = identitySet(cull(new FrustumIntersection(viewProj), RenderQueue.Pass.MAIN));
    final List<MeshRenderer> viaMatrix = new ArrayList<>();
    this.queue.cull(viewProj, RenderQueue.Pass.MAIN, viaMatrix);
    assertEquals(expected, identitySet(viaMatrix));
    assertTrue(expected.size() > 10 && expected.size() < 1500);

    // orthographic volumes (shadow maps) work too
    final var ortho = new Matrix4f().setOrtho(-50f, 50f, -50f, 50f, 0.1f, 200f)
        .mul(new Matrix4f().lookAt(0f, 100f, 0f, 0f, 0f, 0f, 0f, 0f, -1f));
    final var expectedOrtho = identitySet(cull(new FrustumIntersection(ortho), RenderQueue.Pass.SHADOW));
    final List<MeshRenderer> orthoViaMatrix = new ArrayList<>();
    this.queue.cull(ortho, RenderQueue.Pass.SHADOW, orthoViaMatrix);
    assertEquals(expectedOrtho, identitySet(orthoViaMatrix));
  }

  @Test
  void frustumBoundsContainAllCornersOfThePerspectiveAndOrthoVolumes() {
    final var min = new Vector3f();
    final var max = new Vector3f();
    final var view = new Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
    final var persp = new Matrix4f().setPerspective((float) Math.toRadians(90), 1f, 1f, 10f).mul(view);
    RenderQueue.frustumBounds(persp, min, max);
    assertEquals(-10f, min.z, 1e-3f); // far plane
    assertEquals(-1f, max.z, 1e-3f); // near plane
    assertEquals(-10f, min.x, 1e-3f); // 90 degrees: half width equals distance at the far plane
    assertEquals(10f, max.y, 1e-3f);

    final var ortho = new Matrix4f().setOrtho(-3f, 3f, -2f, 2f, 1f, 5f).mul(view);
    RenderQueue.frustumBounds(ortho, min, max);
    assertEquals(-3f, min.x, 1e-3f);
    assertEquals(2f, max.y, 1e-3f);
    assertEquals(-5f, min.z, 1e-3f);
    assertEquals(-1f, max.z, 1e-3f);
  }

  @Test
  void staticAndDynamicAreTrackedSeparately() {
    add(0f, 0f, -10f, true);
    add(1f, 0f, -10f, true);
    add(2f, 0f, -10f, false);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(2, this.queue.staticCount());
    assertEquals(1, this.queue.dynamicCount());
  }

  @Test
  void staticStateIsCachedUntilInvalidated() {
    final var r = add(0f, 0f, -10f, true);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    final var f = frustum();
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());

    // moved behind the camera without telling the engine: the cached (stale) position is still used
    r.entity().transform().position().set(0f, 0f, 50f);
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());

    r.invalidate();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(0, cull(f, RenderQueue.Pass.MAIN).size());

    r.entity().transform().position().set(0f, 0f, -20f);
    r.invalidate();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());
  }

  @Test
  void movingObjectsFollowTheirTransformEveryFrame() {
    final var r = add(0f, 0f, -10f, false);
    this.scene.flush();
    final var f = frustum();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());

    r.entity().transform().position().set(0f, 0f, 50f); // behind the camera, no invalidate needed
    this.queue.collect(this.scene, 1f, new Vector3f());
    assertEquals(0, cull(f, RenderQueue.Pass.MAIN).size());

    // once the step snapshot catches up the object is static again and its cached matrix is reused
    r.entity().transform().snapshot();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(0, cull(f, RenderQueue.Pass.MAIN).size());
    r.entity().transform().position().set(0f, 0f, -10f);
    r.entity().transform().snapshot();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());
  }

  @Test
  void aMoveThatWasSnapshottedBetweenTwoFramesIsNotMissed() {
    final var r = add(0f, 0f, -10f, false);
    this.scene.flush();
    final var f = frustum();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());

    // two fixed steps pass before the next frame: step 1 moves the object, step 2 leaves it alone, so by the time
    // it is rendered previous == current again and only the version counter reveals the move
    r.entity().transform().position().set(0f, 0f, 50f);
    r.entity().transform().snapshot();
    r.entity().transform().snapshot();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(0, cull(f, RenderQueue.Pass.MAIN).size());
  }

  @Test
  void removedAndDisabledRenderersDisappearAndComeBack() {
    final var a = add(0f, 0f, -10f, true);
    final var b = add(1f, 0f, -10f, false);
    this.scene.flush();
    final var f = frustum();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(2, cull(f, RenderQueue.Pass.MAIN).size());

    a.setEnabled(false);
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());
    assertEquals(0, this.queue.staticCount());

    a.setEnabled(true);
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(2, cull(f, RenderQueue.Pass.MAIN).size());
    assertEquals(1, this.queue.staticCount());

    this.scene.remove(b.entity());
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, cull(f, RenderQueue.Pass.MAIN).size());
  }

  @Test
  void switchingAnObjectBetweenStaticAndDynamicRegroupsIt() {
    final var r = add(0f, 0f, -10f, true);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(1, this.queue.staticCount());

    r.setStaticGeometry(false);
    this.queue.collect(this.scene, 0f, new Vector3f());
    assertEquals(0, this.queue.staticCount());
    assertEquals(1, this.queue.dynamicCount());
    assertEquals(1, cull(frustum(), RenderQueue.Pass.MAIN).size());
  }

  @Test
  void gridSlotsAreReusedAfterRemoval() {
    for (int round = 0; round < 5; round++) {
      final var r = add(0f, 0f, -10f, true);
      this.scene.flush();
      this.queue.collect(this.scene, 0f, new Vector3f());
      assertEquals(1, cull(frustum(), RenderQueue.Pass.MAIN).size());
      this.scene.remove(r.entity());
      this.scene.flush();
      this.queue.collect(this.scene, 0f, new Vector3f());
      assertEquals(0, cull(frustum(), RenderQueue.Pass.MAIN).size());
    }
  }

  @Test
  void shadowPassSkipsNonCasters() {
    final var normal = add(0f, 0f, -10f, false);
    final var noShadow = add(1f, 0f, -10f, false);
    noShadow.castShadows = false;
    final var glass = new Material().color(1f, 1f, 1f, 0.5f);
    final var transparent = new Entity();
    transparent.transform().position().set(2f, 0f, -10f);
    final var tr = transparent.add(new MeshRenderer(this.cube, glass));
    this.scene.add(transparent);
    final var points = new Entity();
    points.transform().position().set(3f, 0f, -10f);
    final var pr = points.add(new MeshRenderer(mesh(MeshFactory.points(List.of(new Vector3f()))), this.material));
    this.scene.add(points);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());

    final var f = frustum();
    final var shadow = identitySet(cull(f, RenderQueue.Pass.SHADOW));
    assertEquals(Set.of(normal).size(), shadow.size());
    assertTrue(shadow.contains(normal));
    assertFalse(shadow.contains(noShadow));
    assertFalse(shadow.contains(tr));
    assertFalse(shadow.contains(pr));
    assertEquals(4, cull(f, RenderQueue.Pass.MAIN).size());
  }

  @Test
  void batchingGroupsSameMeshAndMaterial() {
    final var other = new Material();
    final var sphere = mesh(MeshFactory.quad(1f, 1f));
    final List<MeshRenderer> list = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      final var e = new Entity();
      final var r = e.add(new MeshRenderer(i % 3 == 0 ? sphere : this.cube, i % 2 == 0 ? this.material : other));
      list.add(r);
    }
    RenderQueue.sortForBatching(list, true);
    int batches = 1;
    for (int i = 1; i < list.size(); i++) {
      if (!RenderQueue.sameBatch(list.get(i - 1), list.get(i), true)) {
        batches++;
      }
    }
    final Set<String> distinct = new java.util.HashSet<>();
    for (var r : list) {
      distinct.add(System.identityHashCode(r.mesh) + "/" + System.identityHashCode(r.material));
    }
    assertEquals(distinct.size(), batches, "every (mesh, material) combination forms exactly one run");

    RenderQueue.sortForBatching(list, false);
    int meshRuns = 1;
    for (int i = 1; i < list.size(); i++) {
      if (!RenderQueue.sameBatch(list.get(i - 1), list.get(i), false)) {
        meshRuns++;
      }
    }
    assertEquals(2, meshRuns);
  }

  @Test
  void transparentObjectsSortFarToNear() {
    final var near = add(0f, 0f, -5f, false);
    final var far = add(0f, 0f, -50f, false);
    final var mid = add(0f, 0f, -20f, false);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    final var visible = cull(frustum(), RenderQueue.Pass.MAIN);
    RenderQueue.sortBackToFront(visible);
    assertEquals(List.of(far, mid, near), visible);
  }

  @Test
  void instanceDataHoldsModelAndNormalMatrix() {
    final var r = add(3f, 4f, -10f, false);
    r.entity().transform().scale().set(2f, 1f, 1f);
    this.scene.flush();
    this.queue.collect(this.scene, 0f, new Vector3f());
    final var buf = Buffers.newDirectFloatBuffer(RenderQueue.FLOATS_PER_INSTANCE * 2);
    RenderQueue.writeInstance(r, buf, 1);
    final int o = RenderQueue.FLOATS_PER_INSTANCE;
    assertEquals(2f, buf.get(o), 1e-6f); // model m00 (x scale)
    assertEquals(3f, buf.get(o + 12), 1e-6f); // translation
    assertEquals(4f, buf.get(o + 13), 1e-6f);
    assertEquals(0.5f, buf.get(o + 16), 1e-6f); // normal matrix = inverse transpose: 1/2 on x
    assertEquals(1f, buf.get(o + 20), 1e-6f);
    assertEquals(0f, buf.get(0), 0f); // slot 0 untouched
  }
}
