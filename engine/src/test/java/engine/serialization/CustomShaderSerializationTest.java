package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.input.InputState;
import engine.render.ImageData;
import engine.render.Material;
import engine.render.MeshRenderer;
import engine.render.ShaderParams;
import engine.resources.ResourceManager;

/** Materials with shaders of their own keep them, and their parameters, in saved scenes. */
class CustomShaderSerializationTest {
  @Test
  void shadersAndParametersAreSavedAndLoaded() {
    final var res = new ResourceManager(SerializationTest.fakeBackend());
    final var noise = res.texture("gen:noise", new ImageData(1, 1, new byte[] { 9, 9, 9, 9 }));
    final var m = new Material().shader("shaders/water.vert", "shaders/water.frag");
    m.params.set("uHeight", 0.25f).set("uSteps", 3).set("uFlow", new Vector2f(1f, 2f))
        .set("uDeep", new Vector3f(0f, 0.1f, 0.2f)).set("uTint", new Vector4f(1f, 2f, 3f, 4f))
        .set("uWarp", new Matrix4f().translation(1f, 2f, 3f)).set("uNoise", noise);
    final var scene = new Scene();
    final var water = new Entity("water");
    water.add(new MeshRenderer(res.shape("cube:1"), m));
    scene.add(water);
    scene.flush();

    final var serializer = new SceneSerializer();
    final var text = serializer.write(scene, new SerializationContext(res, new InputState()));
    final var loaded = serializer.read(text, new SerializationContext(res, new InputState())).getFirst()
        .get(MeshRenderer.class).material;
    assertEquals("shaders/water.vert", loaded.vertexShader);
    assertEquals("shaders/water.frag", loaded.fragmentShader);
    final ShaderParams p = loaded.params;
    assertEquals(0.25f, p.get("uHeight"));
    assertEquals(3, p.get("uSteps"));
    assertEquals(new Vector2f(1f, 2f), p.get("uFlow"));
    assertEquals(new Vector3f(0f, 0.1f, 0.2f), p.get("uDeep"));
    assertEquals(new Vector4f(1f, 2f, 3f, 4f), p.get("uTint"));
    assertEquals(new Matrix4f().translation(1f, 2f, 3f), p.get("uWarp"));
    assertSame(noise, p.get("uNoise"));
    assertEquals(m.params.values().keySet(), p.values().keySet(), "in the same order");

    final var plain = new Material();
    assertTrue(!plain.hasCustomShader() && plain.params.isEmpty());
    final var reloaded = serializer.read(serializer.write(sceneWith(res, plain), new SerializationContext(res, null)),
        new SerializationContext(res, null)).getFirst().get(MeshRenderer.class).material;
    assertNull(reloaded.fragmentShader);
    assertTrue(reloaded.params.isEmpty());
  }

  @Test
  void paramsCopyWhatTheyAreGiven() {
    final var v = new Vector3f(1f, 2f, 3f);
    final var p = new ShaderParams().set("a", v);
    v.x = 9f;
    assertEquals(new Vector3f(1f, 2f, 3f), p.get("a"));
    p.set("t", (engine.render.GpuTexture) null);
    assertEquals(1, p.size(), "a null texture is no value");
    final var q = new ShaderParams().set("b", 1f).setAll(p);
    assertEquals(2, q.size());
    q.remove("a").clear();
    assertTrue(q.isEmpty());
  }

  private static Scene sceneWith(ResourceManager res, Material m) {
    final var scene = new Scene();
    scene.add(new Entity("e").add(new MeshRenderer(res.shape("cube:1"), m)).entity());
    scene.flush();
    return scene;
  }
}
