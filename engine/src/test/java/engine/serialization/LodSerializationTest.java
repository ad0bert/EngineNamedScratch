package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.input.InputState;
import engine.render.Material;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.MeshSimplifier;
import engine.resources.ResourceManager;

class LodSerializationTest {
  @Test
  void levelsOfDetailAndTheCullDistanceAreSaved() {
    final var res = new ResourceManager(SerializationTest.fakeBackend());
    final var full = res.mesh("gen:rock", MeshFactory.sphere(1f, 32, 16));
    final var low = res.mesh("gen:rock-low", MeshSimplifier.simplify(full.data(), 0.2f));
    final var unkeyed = res.mesh(MeshSimplifier.simplify(full.data(), 0.05f)); // no key: left out
    final var scene = new Scene();
    final var rock = new Entity("rock");
    final var r = rock.add(new MeshRenderer(full, new Material()));
    r.addLod(25f, low).addLod(80f, unkeyed);
    r.cullDistance = 200f;
    scene.add(rock);

    final var json = new SceneSerializer().write(scene, new SerializationContext(res, new InputState()));
    final var loaded = new SceneSerializer().read(json, new SerializationContext(res, new InputState()))
        .get(0).get(MeshRenderer.class);
    assertEquals(200f, loaded.cullDistance);
    assertEquals(1, loaded.lodCount());
    assertEquals(25f, loaded.lodDistance(0));
    assertSame(low, loaded.lodMesh(0));
  }
}
