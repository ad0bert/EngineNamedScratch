package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.input.InputState;
import engine.render.ReflectionProbe;
import engine.resources.ResourceManager;

class ReflectionProbeTest {
  @Test
  void theBoxIsCentredOnTheEntityAndTheProbeIsSaved() {
    final var scene = new Scene();
    final var e = new Entity("probe");
    e.transform().position().set(10f, 1.5f, -4f);
    final var probe = e.add(new ReflectionProbe(6f, 3f, 8f));
    probe.intensity = 0.8f;
    probe.refreshFrames = 30;
    scene.add(e);
    final var min = new Vector3f();
    final var max = new Vector3f();
    probe.box(min, max);
    assertEquals(new Vector3f(7f, 0f, -8f), min);
    assertEquals(new Vector3f(13f, 3f, 0f), max);
    assertTrue(probe.isDirty());
    probe.captured();
    assertFalse(probe.isDirty());
    probe.refresh();
    assertTrue(probe.isDirty());

    final var res = new ResourceManager(SerializationTest.fakeBackend());
    final var json = new SceneSerializer().write(scene, new SerializationContext(res, new InputState()));
    final var loaded = new SceneSerializer().read(json, new SerializationContext(res, new InputState())).get(0)
        .get(ReflectionProbe.class);
    assertEquals(new Vector3f(6f, 3f, 8f), loaded.size);
    assertEquals(0.8f, loaded.intensity);
    assertEquals(30, loaded.refreshFrames);
    assertTrue(loaded.isDirty(), "a loaded probe is captured at its first frame");
  }
}
