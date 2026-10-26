package engine.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class EcsTest {

  static class Counter extends Component {
    int updates;

    @Override
    public void update(double dt) {
      this.updates++;
    }
  }

  @Test
  void entitiesGetUniqueIds() {
    assertNotEquals(new Entity().id(), new Entity().id());
  }

  @Test
  void componentLookupByTypeAndAttachment() {
    final var e = new Entity("e");
    final var c = e.add(new Counter());
    assertSame(c, e.get(Counter.class));
    assertSame(e, c.entity());
    assertTrue(e.has(Counter.class));
    assertFalse(e.has(Spinner.class));
    assertThrows(IllegalStateException.class, () -> e.get(Spinner.class));
    assertThrows(IllegalStateException.class, () -> new Entity().add(c));
  }

  @Test
  void sceneDefersAddAndRemoveUntilFlush() {
    final var scene = new Scene();
    final var e = scene.add(new Entity());
    assertTrue(scene.entities().isEmpty());
    scene.flush();
    assertEquals(1, scene.entities().size());
    scene.remove(e);
    assertEquals(1, scene.entities().size());
    scene.flush();
    assertTrue(scene.entities().isEmpty());
  }

  @Test
  void updateSystemSkipsDisabledComponents() {
    final var scene = new Scene();
    final var e = new Entity();
    final var on = e.add(new Counter());
    final var off = e.add(new Counter());
    off.setEnabled(false);
    scene.add(e);
    scene.flush();

    new ComponentUpdateSystem().update(scene, 0.016);

    assertEquals(1, on.updates);
    assertEquals(0, off.updates);
    assertEquals(1, scene.components(Counter.class).size());
  }

  @Test
  void worldPositionAppliesParentTransform() {
    final var parent = new Entity();
    parent.transform().position().set(10f, 0f, 0f);
    parent.transform().scale().set(2f);
    final var child = new Entity();
    child.transform().setParent(parent.transform());
    child.transform().position().set(1f, 0f, 0f);

    final var w = child.transform().worldPosition(new Vector3f());

    assertEquals(12f, w.x, 1e-5);
    assertEquals(0f, w.y, 1e-5);
  }

  @Test
  void parentCyclesAreRejected() {
    final var a = new Entity();
    final var b = new Entity();
    b.transform().setParent(a.transform());
    assertThrows(IllegalArgumentException.class, () -> a.transform().setParent(b.transform()));
  }

  @Test
  void forwardIsMinusZUntilRotated() {
    final var t = new Transform();
    final var f = t.forward(new Vector3f());
    assertEquals(-1f, f.z, 1e-6);
    t.setEulerDegrees(0f, 90f, 0f);
    t.forward(f);
    assertEquals(-1f, f.x, 1e-5); // yaw 90deg turns -Z towards -X
  }

  @Test
  void interpolationBlendsPreviousAndCurrentState() {
    final var e = new Entity();
    e.transform().position().set(0f, 0f, 0f);
    e.transform().snapshot();
    e.transform().position().set(10f, 0f, 0f);

    assertEquals(0f, e.transform().interpolatedWorldMatrix(0f, new org.joml.Matrix4f()).m30(), 1e-5f);
    assertEquals(5f, e.transform().interpolatedWorldMatrix(0.5f, new org.joml.Matrix4f()).m30(), 1e-5f);
    assertEquals(10f, e.transform().interpolatedWorldMatrix(1f, new org.joml.Matrix4f()).m30(), 1e-5f);
  }

  @Test
  void interpolationIncludesParentAndRotation() {
    final var parent = new Entity();
    final var child = new Entity();
    child.transform().setParent(parent.transform());
    child.transform().position().set(1f, 0f, 0f);
    parent.transform().snapshot();
    child.transform().snapshot();
    parent.transform().position().set(0f, 4f, 0f);
    parent.transform().setEulerDegrees(0f, 0f, 90f);

    final var m = child.transform().interpolatedWorldMatrix(1f, new org.joml.Matrix4f());
    final var p = m.getTranslation(new Vector3f());
    assertEquals(0f, p.x, 1e-5f); // (1,0,0) turned 90 degrees about Z points along +Y
    assertEquals(5f, p.y, 1e-5f);
  }

  @Test
  void newEntitiesAreSnapshottedWhenAddedToTheScene() {
    final var scene = new Scene();
    final var e = new Entity();
    e.transform().position().set(7f, 0f, 0f);
    scene.add(e);
    scene.flush();
    // no smear from the origin: at alpha 0 the entity is already where it was placed
    assertEquals(7f, e.transform().interpolatedWorldMatrix(0f, new org.joml.Matrix4f()).m30(), 1e-5f);
  }

  @Test
  void chainStaticnessTracksTheTransformAndItsAncestors() {
    final var parent = new Entity();
    final var child = new Entity();
    child.transform().setParent(parent.transform());
    parent.transform().snapshot();
    child.transform().snapshot();
    assertTrue(child.transform().isChainStatic());

    child.transform().position().x += 1f;
    assertFalse(child.transform().isChainStatic());
    child.transform().snapshot();
    assertTrue(child.transform().isChainStatic());

    parent.transform().rotation().rotateY(0.1f); // a moving ancestor makes the child non-static too
    assertFalse(child.transform().isChainStatic());
    parent.transform().snapshot();
    assertTrue(child.transform().isChainStatic());
  }

  @Test
  void translateWorldConvertsIntoParentSpace() {
    final var parent = new Entity();
    parent.transform().position().set(5f, 0f, 0f);
    parent.transform().scale().set(2f);
    parent.transform().setEulerDegrees(0f, 0f, 90f);
    final var child = new Entity();
    child.transform().setParent(parent.transform());
    child.transform().position().set(1f, 0f, 0f);
    final var before = child.transform().worldPosition(new Vector3f());

    child.transform().translateWorld(new Vector3f(0f, 0f, 3f));
    final var after = child.transform().worldPosition(new Vector3f());
    assertEquals(before.x, after.x, 1e-4f);
    assertEquals(before.y, after.y, 1e-4f);
    assertEquals(before.z + 3f, after.z, 1e-4f);

    final var loose = new Entity();
    loose.transform().translateWorld(new Vector3f(1f, 2f, 3f));
    assertEquals(2f, loose.transform().position().y, 0f);
  }

  @Test
  void matrixQueriesWorkForDeepHierarchies() {
    Entity previous = null;
    final var chain = new java.util.ArrayList<Entity>();
    for (int i = 0; i < 6; i++) {
      final var e = new Entity();
      e.transform().position().set(1f, 0f, 0f);
      if (previous != null) {
        e.transform().setParent(previous.transform());
      }
      chain.add(e);
      previous = e;
    }
    assertEquals(6f, previous.transform().worldPosition(new Vector3f()).x, 1e-5f);
    chain.forEach(e -> e.transform().snapshot());
    assertEquals(6f, previous.transform().interpolatedWorldMatrix(0.5f, new org.joml.Matrix4f()).m30(), 1e-5f);
  }

  @Test
  void sceneQueriesAreCachedUntilSomethingChanges() {
    final var scene = new Scene();
    final var e = new Entity();
    final var counter = e.add(new Counter());
    scene.add(e);
    scene.flush();

    final var first = scene.components(Counter.class);
    assertSame(first, scene.components(Counter.class));
    assertEquals(1, first.size());

    counter.setEnabled(false);
    final var second = scene.components(Counter.class);
    assertNotSame(first, second);
    assertTrue(second.isEmpty());
    assertEquals(1, first.size(), "an old snapshot is never mutated");

    counter.setEnabled(true);
    e.add(new Counter());
    assertEquals(2, scene.components(Counter.class).size());

    scene.add(new Entity());
    scene.flush();
    assertNotSame(scene.components(Counter.class), second);
  }

  @Test
  void spinnerRotatesWithFixedStep() {
    final var e = new Entity();
    e.add(new Spinner(0f, 90f, 0f));
    for (int i = 0; i < 100; i++) {
      e.get(Spinner.class).update(0.01); // 1 second in total
    }
    final var f = e.transform().forward(new Vector3f());
    assertEquals(-1f, f.x, 1e-3);
  }
}
