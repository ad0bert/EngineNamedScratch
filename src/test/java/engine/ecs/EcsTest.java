package engine.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

  private static void assertSame(Object a, Object b) {
    org.junit.jupiter.api.Assertions.assertSame(a, b);
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

    final Vector3f w = child.transform().worldPosition(new Vector3f());

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
    final Vector3f f = t.forward(new Vector3f());
    assertEquals(-1f, f.z, 1e-6);
    t.setEulerDegrees(0f, 90f, 0f);
    t.forward(f);
    assertEquals(-1f, f.x, 1e-5); // yaw 90deg turns -Z towards -X
  }

  @Test
  void spinnerRotatesWithFixedStep() {
    final var e = new Entity();
    e.add(new Spinner(0f, 90f, 0f));
    for (int i = 0; i < 100; i++) {
      e.get(Spinner.class).update(0.01); // 1 second in total
    }
    final Vector3f f = e.transform().forward(new Vector3f());
    assertEquals(-1f, f.x, 1e-3);
  }
}
