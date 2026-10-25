package engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageQueue;
import engine.messaging.MessageService;

class CollisionTest {

  private static Entity boxAt(float x, float half) {
    final var e = new Entity();
    e.transform().position().set(x, 0f, 0f);
    e.add(new AabbCollider(new Vector3f(half)));
    return e;
  }

  @Test
  void overlapAndScale() {
    final var a = boxAt(0f, 1f);
    final var b = boxAt(1.5f, 1f);
    final var c = boxAt(5f, 1f);
    assertTrue(a.get(AabbCollider.class).overlaps(b.get(AabbCollider.class)));
    assertFalse(a.get(AabbCollider.class).overlaps(c.get(AabbCollider.class)));
    c.transform().scale().set(3f); // half extent becomes 3 -> reaches x=2
    assertFalse(a.get(AabbCollider.class).overlaps(c.get(AabbCollider.class)));
    c.transform().position().set(3f, 0f, 0f);
    assertTrue(a.get(AabbCollider.class).overlaps(c.get(AabbCollider.class)));
  }

  @Test
  void systemEmitsStartAndEndOnce() {
    final var messages = new MessageService();
    final List<CollisionEvent> events = new ArrayList<>();
    messages.register(m -> events.add((CollisionEvent) m.getPayload()), MessageQueue.COLLISION);
    final var scene = new Scene();
    final var a = scene.add(boxAt(0f, 1f));
    final var b = scene.add(boxAt(10f, 1f));
    scene.flush();
    final var system = new CollisionSystem(messages);

    system.update(scene, 0.01);
    assertTrue(events.isEmpty());

    b.transform().position().set(1f, 0f, 0f);
    system.update(scene, 0.01);
    system.update(scene, 0.01);
    assertEquals(1, events.size());
    assertTrue(events.get(0).started());

    b.transform().position().set(10f, 0f, 0f);
    system.update(scene, 0.01);
    assertEquals(2, events.size());
    assertFalse(events.get(1).started());
    assertEquals(a, events.get(1).a());
  }
}
