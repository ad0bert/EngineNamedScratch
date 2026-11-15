package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import engine.audio.NullAudioBackend;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.input.InputState;
import engine.physics.BoxCollider;
import engine.physics.HingeJoint;
import engine.physics.Rigidbody;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshRenderer;
import engine.resources.Assets;
import engine.resources.ResourceManager;

/** Material files shared by scene files, and prefabs nested in other prefabs. */
class AssetFilesTest {
  @TempDir Path dir;

  private ResourceManager res;
  private final SceneSerializer serializer = new SceneSerializer();

  private ResourceManager resources() {
    if (this.res == null) {
      this.res = new ResourceManager(SerializationTest.fakeBackend(), new NullAudioBackend(),
          new Assets().addRoot(this.dir));
    }
    return this.res;
  }

  private SerializationContext ctx() {
    return new SerializationContext(resources(), new InputState());
  }

  private void file(String path, String text) throws IOException {
    final var f = this.dir.resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  private static String scene(String materials, String entities) {
    return "{\"format\": \"engine-scene\", \"version\": 1, \"materials\": [" + materials + "], \"entities\": ["
        + entities + "]}";
  }

  private static String renderer(String material) {
    return "{\"type\": \"MeshRenderer\", \"properties\": {\"mesh\": \"cube:0.5\", \"material\": " + material + "}}";
  }

  private void steel() throws IOException {
    file("materials/steel.json", """
        { "format": "engine-material", "version": 1,
          "color": [0.5, 0.6, 0.7, 1], "pbr": true, "metallic": 1, "roughness": 0.3,
          "fragmentShader": "shaders/brushed.frag", "params": { "streaks": 40, "tint": [1, 0.9, 0.8] } }
        """);
  }

  @Test
  void materialFilesAreSharedAcrossFilesAndCode() throws IOException {
    steel();
    final var a = this.serializer.read(scene("\"materials/steel.json\"",
        "{\"name\": \"a\", \"components\": [" + renderer("0") + "]}, {\"name\": \"b\", \"components\": ["
            + renderer("0") + "]}"),
        ctx());
    final var b = this.serializer.read(scene("", "{\"name\": \"c\", \"components\": ["
        + renderer("\"/materials/steel.json\"") + "]}"), ctx()); // a path in place of an index, too
    final var m = a.get(0).get(MeshRenderer.class).material;
    assertSame(m, a.get(1).get(MeshRenderer.class).material);
    assertSame(m, b.get(0).get(MeshRenderer.class).material);
    assertSame(m, ctx().material("materials/steel.json"));
    assertEquals(new org.joml.Vector4f(0.5f, 0.6f, 0.7f, 1f), m.color);
    assertTrue(m.pbr);
    assertEquals(0.3f, m.roughness);
    assertEquals("shaders/brushed.frag", m.fragmentShader);
    assertEquals(40f, m.params.get("streaks"));
    assertEquals("materials/steel.json", AssetLibrary.of(resources()).pathOf(m));

    // saved as the path, not the fields
    final var s = new Scene();
    a.forEach(s::add);
    final var saved = Json.asObject(Json.parse(this.serializer.write(s, ctx())));
    assertEquals(List.of("materials/steel.json"), saved.get("materials"));
    final var again = this.serializer.read(Json.write(saved), ctx());
    assertSame(m, again.get(0).get(MeshRenderer.class).material);
  }

  @Test
  void materialsCanStartFromAFile() throws IOException {
    steel();
    file("materials/rusty.json", "{ \"file\": \"materials/steel.json\", \"roughness\": 0.9 }");
    final var loaded = this.serializer.read(scene(
        "\"materials/rusty.json\", {\"file\": \"materials/steel.json\", \"metallic\": 0.5, \"params\": {\"streaks\": 8}}",
        "{\"name\": \"a\", \"components\": [" + renderer("0") + "]}, {\"name\": \"b\", \"components\": ["
            + renderer("1") + "]}"),
        ctx());
    final var rusty = loaded.get(0).get(MeshRenderer.class).material;
    assertEquals(0.9f, rusty.roughness);
    assertEquals(1f, rusty.metallic); // from steel
    assertEquals("shaders/brushed.frag", rusty.fragmentShader);
    final var own = loaded.get(1).get(MeshRenderer.class).material;
    assertNotSame(own, ctx().material("materials/steel.json"));
    assertEquals(0.5f, own.metallic);
    assertEquals(0.3f, own.roughness);
    assertEquals(8f, own.params.get("streaks"));
    assertEquals(new Vector3f(1f, 0.9f, 0.8f), own.params.get("tint"));

    // the derived material is saved as its file and what differs
    final var s = new Scene();
    loaded.forEach(s::add);
    final var materials = Json.asList(Json.asObject(Json.parse(this.serializer.write(s, ctx()))).get("materials"));
    assertEquals("materials/rusty.json", materials.get(0));
    final var derived = Json.asObject(materials.get(1));
    assertEquals("materials/steel.json", derived.get("file"));
    assertEquals(0.5, derived.get("metallic"));
    assertFalse(derived.containsKey("roughness"));
    assertFalse(derived.containsKey("color"));
    assertEquals(8.0, Json.asObject(derived.get("params")).get("streaks"));
  }

  @Test
  void badMaterialFilesAreRefused() throws IOException {
    file("materials/a.json", "{ \"file\": \"materials/b.json\" }");
    file("materials/b.json", "{ \"file\": \"materials/a.json\" }");
    file("materials/scene.json", scene("", ""));
    final var cycle = assertThrows(IllegalArgumentException.class, () -> ctx().material("materials/a.json"));
    assertTrue(cycle.getMessage().contains("starts from itself"), cycle.getMessage());
    assertThrows(IllegalArgumentException.class, () -> ctx().material("materials/scene.json"));
    assertThrows(IllegalArgumentException.class, () -> ctx().material("materials/missing.json"));
  }

  @Test
  void changedMaterialFilesApplyInPlace() throws IOException {
    steel();
    file("materials/rusty.json", "{ \"file\": \"materials/steel.json\", \"roughness\": 0.9 }");
    file("materials/glass.json", "{ \"color\": [1, 1, 1, 0.2], \"transparent\": true }");
    final var steel = ctx().material("materials/steel.json");
    final var rusty = ctx().material("materials/rusty.json");
    final var glass = ctx().material("materials/glass.json");
    final var own = this.serializer.read(scene("{\"file\": \"materials/steel.json\", \"metallic\": 0.5}",
        "{\"name\": \"a\", \"components\": [" + renderer("0") + "]}"), ctx()).get(0).get(MeshRenderer.class).material;
    glass.color.x = 0.5f; // changed by the game: kept, since its file does not change

    file("materials/steel.json", "{ \"color\": [1, 0, 0, 1], \"roughness\": 0.1 }");
    final var library = AssetLibrary.of(resources());
    assertEquals(3, library.reload("materials/steel.json", resources()));
    assertEquals(new org.joml.Vector4f(1f, 0f, 0f, 1f), steel.color);
    assertEquals(0.1f, steel.roughness);
    assertFalse(steel.pbr); // back to the default: no longer in the file
    assertNull(steel.fragmentShader);
    assertTrue(steel.params.isEmpty());
    assertEquals(0.9f, rusty.roughness); // its own value stays
    assertEquals(1f, rusty.color.x);
    assertEquals(0.5f, own.metallic);
    assertEquals(0.1f, own.roughness);
    assertEquals(0.5f, glass.color.x);

    file("materials/steel.json", "{ broken");
    assertEquals(0, library.reload("materials/steel.json", resources()));
    assertEquals(0.1f, steel.roughness);
    assertEquals(0, library.reload("textures/unrelated.png", resources()));
  }

  private void lamp() throws IOException {
    file("prefabs/lamp.json", scene("{\"emissive\": [3, 2, 1]}", """
        { "name": "lamp", "parent": null,
          "transform": { "position": [0, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] },
          "components": [ { "type": "BoxCollider", "properties": { "halfExtents": [0.1, 1, 0.1] } } ] },
        { "name": "head", "parent": 0,
          "transform": { "position": [0, 2, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] },
          "components": [ { "type": "MeshRenderer", "properties": { "mesh": "cube:0.5", "material": 0 } },
                          { "type": "Light", "properties": { "type": "POINT", "intensity": 1.2, "range": 6 } } ] }
        """));
  }

  @Test
  void prefabsNestInOtherFiles() throws IOException {
    lamp();
    steel();
    final var loaded = this.serializer.read(scene("", """
        { "name": "street", "parent": null },
        { "parent": 0, "prefab": "prefabs/lamp.json",
          "transform": { "position": [4, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] } },
        { "name": "bright-lamp", "parent": 0, "prefab": "prefabs/lamp.json",
          "transform": { "position": [-4, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] },
          "components": [ { "type": "Rigidbody", "properties": { "mass": 20 } },
                          { "type": "BoxCollider", "removed": true } ],
          "overrides": { "head": { "components": [
              { "type": "Light", "properties": { "intensity": 3 } },
              { "type": "MeshRenderer", "properties": { "material": "materials/steel.json" } } ] } } },
        { "name": "gate", "parent": 0,
          "components": [ { "type": "HingeJoint", "properties": { "connected": 2 } } ] }
        """), ctx());
    // street, lamp, head, bright-lamp, head, gate
    assertEquals(List.of("street", "lamp", "head", "bright-lamp", "head", "gate"),
        loaded.stream().map(Entity::name).toList());
    final var street = loaded.get(0);
    final var lamp = loaded.get(1);
    final var bright = loaded.get(3);
    assertSame(street.transform(), lamp.transform().parent());
    assertSame(street.transform(), bright.transform().parent());
    assertSame(lamp.transform(), loaded.get(2).transform().parent());
    assertSame(bright.transform(), loaded.get(4).transform().parent());
    assertEquals(4f, lamp.transform().position().x);
    assertEquals(-4f, bright.transform().position().x);
    assertEquals(2f, loaded.get(4).transform().position().y);
    assertSame(bright, loaded.get(5).get(HingeJoint.class).connected); // indices of the outer file

    assertTrue(lamp.has(BoxCollider.class));
    assertFalse(bright.has(BoxCollider.class));
    assertEquals(20f, bright.get(Rigidbody.class).mass);
    assertEquals(1.2f, loaded.get(2).get(Light.class).intensity);
    final var brightLight = loaded.get(4).get(Light.class);
    assertEquals(3f, brightLight.intensity);
    assertEquals(6f, brightLight.range); // kept from the prefab
    assertSame(ctx().material("materials/steel.json"), loaded.get(4).get(MeshRenderer.class).material);

    // instances of a prefab share its materials, across reads
    final var again = this.serializer.read(scene("", "{\"prefab\": \"prefabs/lamp.json\"}"), ctx());
    final Material emissive = loaded.get(2).get(MeshRenderer.class).material;
    assertEquals(3f, emissive.emissive.x);
    assertSame(emissive, again.get(1).get(MeshRenderer.class).material);
  }

  @Test
  void prefabsNestInPrefabs() throws IOException {
    lamp();
    file("prefabs/pair.json", scene("", """
        { "name": "pair" },
        { "parent": 0, "prefab": "prefabs/lamp.json",
          "transform": { "position": [1, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] } },
        { "parent": 0, "prefab": "prefabs/lamp.json",
          "transform": { "position": [-1, 0, 0], "rotation": [0, 0, 0, 1], "scale": [1, 1, 1] } }
        """));
    final var prefab = Prefab.load("prefabs/pair.json", resources().assets(), this.serializer);
    final var s = new Scene();
    final var root = prefab.instantiate(s, ctx(), new Vector3f(0f, 0f, 10f), null);
    s.flush();
    assertEquals(5, s.entities().size());
    assertEquals("pair", root.name());
    assertEquals(2, root.transform().children().size());
    final var heads = s.entities().stream().filter(e -> e.name().equals("head")).toList();
    assertEquals(2, heads.size());
    assertEquals(10f, heads.get(0).transform().worldPosition(new Vector3f()).z, 1e-5f);

    // a saved scene keeps the instances as plain entities
    final var reread = this.serializer.read(this.serializer.write(s, ctx()), ctx());
    assertEquals(5, reread.size());
  }

  @Test
  void badNestedPrefabsAreRefused() throws IOException {
    lamp();
    file("prefabs/loop.json", scene("", "{ \"name\": \"loop\" }, { \"parent\": 0, \"prefab\": \"prefabs/loop.json\" }"));
    final var loop = assertThrows(IllegalArgumentException.class,
        () -> this.serializer.read(scene("", "{\"prefab\": \"prefabs/loop.json\"}"), ctx()));
    assertTrue(loop.getMessage().contains("contains itself"), loop.getMessage());
    final var unknown = assertThrows(IllegalArgumentException.class, () -> this.serializer.read(scene("",
        "{\"prefab\": \"prefabs/lamp.json\", \"overrides\": {\"tail\": {}}}"), ctx()));
    assertTrue(unknown.getMessage().contains("tail"), unknown.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> this.serializer.read(scene("", "{\"prefab\": \"prefabs/missing.json\"}"), ctx()));
  }

  @Test
  void changedPrefabFilesAreReadAgain() throws IOException {
    lamp();
    this.serializer.read(scene("", "{\"prefab\": \"prefabs/lamp.json\"}"), ctx());
    file("prefabs/lamp.json", scene("", "{ \"name\": \"new-lamp\" }"));
    assertEquals(1, AssetLibrary.of(resources()).reload("prefabs/lamp.json", resources()));
    final var loaded = this.serializer.read(scene("", "{\"prefab\": \"prefabs/lamp.json\"}"), ctx());
    assertEquals("new-lamp", loaded.get(0).name());
  }
}
