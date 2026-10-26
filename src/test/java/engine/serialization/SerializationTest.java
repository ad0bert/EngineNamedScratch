package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.audio.AudioData;
import engine.audio.AudioSource;
import engine.audio.ImpactSound;
import engine.audio.Rolloff;
import engine.ecs.Component;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.ecs.Spinner;
import engine.input.FirstPersonController;
import engine.input.InputState;
import engine.physics.BoxCollider;
import engine.physics.CapsuleCollider;
import engine.physics.DistanceJoint;
import engine.physics.HingeJoint;
import engine.physics.MeshCollider;
import engine.physics.Rigidbody;
import engine.physics.SphereCollider;
import engine.render.Camera;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.ImageData;
import engine.render.Light;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.RenderBackend;
import engine.resources.ResourceManager;

class SerializationTest {

  /** A backend that only hands out handles; a proxy, so it keeps compiling when the interface grows. */
  static RenderBackend fakeBackend() {
    final var ids = new AtomicInteger();
    final GpuTexture white = () -> {
    };
    return (RenderBackend) Proxy.newProxyInstance(SerializationTest.class.getClassLoader(),
        new Class<?>[] { RenderBackend.class }, (proxy, method, args) -> switch (method.getName()) {
        case "createMesh" -> {
          final var data = (MeshData) args[0];
          final int id = ids.incrementAndGet();
          yield new GpuMesh() {
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
        case "createTexture" -> (GpuTexture) () -> {
        };
        case "whiteTexture" -> white;
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
        });
  }

  @Test
  void jsonRoundTrip() {
    final var text = "{\"a\": [1, 2.5, -3e2, true, null], \"s\": \"q\\\"\\n\\u00e9\", \"o\": {}}";
    final var v = Json.asObject(Json.parse(text));
    assertEquals(List.of(1.0, 2.5, -300.0, true), Json.asList(v.get("a")).subList(0, 4));
    assertNull(Json.asList(v.get("a")).get(4));
    assertEquals("q\"\né", v.get("s"));
    assertEquals(v, Json.parse(Json.write(v)));
    assertEquals(Float.POSITIVE_INFINITY, Json.asFloat(Json.parse(Json.write(Float.POSITIVE_INFINITY))));
    assertTrue(Float.isNaN(Json.asFloat(Json.parse(Json.write(Float.NaN)))));
    assertEquals("[1, 0.5]\n", Json.write(new float[] { 1f, 0.5f }));
    final var e = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\n  \"a\": [1,]\n}"));
    assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Json.parse("[1] x"));
  }

  private static final class Fixture {
    final ResourceManager res = new ResourceManager(fakeBackend());
    final InputState input = new InputState();
    final Scene scene = new Scene();

    SerializationContext ctx() {
      return new SerializationContext(this.res, this.input);
    }
  }

  /** A scene with one of each built-in component, a hierarchy, shared materials and cross references. */
  private static void build(Fixture f) {
    final var crateMesh = f.res.shape("cube:0.5");
    final var shared = new Material(f.res.texture("gen:checker", new ImageData(1, 1, new byte[] { 1, 2, 3, 4 })));
    shared.pbr(0.2f, 0.7f);
    shared.uvScale.set(2f, 3f);
    shared.emissive.set(0.1f, 0.2f, 0.3f);

    final var base = new Entity("base");
    base.transform().position().set(1f, 2f, 3f);
    base.transform().setEulerDegrees(10f, 20f, 30f);
    base.add(new MeshRenderer(crateMesh, shared)).setStaticGeometry(true);
    base.add(new BoxCollider(1f, 2f, 3f)).offset.set(0f, 0.5f, 0f);
    base.add(new MeshCollider(MeshFactory.quad(2f, 2f))).twoSided = true;
    f.scene.add(base);

    final var arm = new Entity("arm");
    arm.transform().setParent(base.transform());
    arm.transform().position().set(0f, 1f, 0f);
    arm.transform().scale().set(0.5f, 2f, 0.5f);
    final var armRenderer = arm.add(new MeshRenderer(crateMesh, shared));
    armRenderer.castShadows = false;
    final var body = arm.add(new Rigidbody());
    body.mass = 3.5f;
    body.velocity.set(1f, 0f, -1f);
    body.lockRotation = true;
    arm.add(new CapsuleCollider(0.3f, 0.7f)).axis = 2;
    final var hinge = arm.add(new HingeJoint());
    hinge.connected = base;
    hinge.anchor.set(0f, -0.5f, 0f);
    hinge.axis.set(1f, 0f, 0f);
    hinge.motor = true;
    hinge.motorSpeed = 2f;
    arm.add(new Spinner(1f, 2f, 3f)).setEnabled(false);
    f.scene.add(arm);

    final var lamp = new Entity("lamp");
    final var light = lamp.add(Light.spot(1f, 0.5f, 0.25f, 3f, 12f, 25f));
    light.castShadows = false;
    lamp.add(new SphereCollider(0.25f)).trigger = true;
    lamp.add(new DistanceJoint(0.5f, 2f)).connectedAnchor.set(0f, 5f, 0f); // to the world
    final var hum = lamp.add(new AudioSource(f.res.sound("gen:hum", AudioData.mono(8000, new float[800]))));
    hum.loop = true;
    hum.rolloffMode = Rolloff.LINEAR;
    hum.maxDistance = 12f;
    lamp.add(new ImpactSound(f.res.sound("gen:hum", null))).minImpulse = 2f;
    f.scene.add(lamp);

    final var player = new Entity("player");
    player.add(new Camera()).fovDegrees = 75f;
    player.add(new FirstPersonController(f.input)).setLook(45f, -10f);
    f.scene.add(player);
    f.scene.flush();
  }

  @Test
  void sceneRoundTrip() {
    final var f = new Fixture();
    build(f);
    final var serializer = new SceneSerializer();
    final var text = serializer.write(f.scene, f.ctx());

    final var g = new Fixture();
    // generated resources must exist before loading; the shape is recreated from its spec
    g.res.texture("gen:checker", new ImageData(1, 1, new byte[] { 1, 2, 3, 4 }));
    g.res.sound("gen:hum", AudioData.mono(8000, new float[800]));
    final var loaded = serializer.load(text, g.scene, g.ctx());
    g.scene.flush();
    assertEquals(4, loaded.size());
    assertEquals(text, serializer.write(g.scene, g.ctx())); // nothing lost, nothing changed

    final var base = loaded.get(0);
    final var arm = loaded.get(1);
    assertEquals("arm", arm.name());
    assertSame(base.transform(), arm.transform().parent());
    assertEquals(new Vector3f(0.5f, 2f, 0.5f), arm.transform().scale());
    assertTrue(base.transform().rotation().equals(f.scene.entities().get(0).transform().rotation(), 1e-6f));
    final var r0 = base.get(MeshRenderer.class);
    final var r1 = arm.get(MeshRenderer.class);
    assertSame(r0.material, r1.material); // still one material: still one instanced draw
    assertSame(r0.mesh, r1.mesh);
    assertSame(g.res.shape("cube:0.5"), r0.mesh);
    assertTrue(r0.isStaticGeometry());
    assertFalse(r1.castShadows);
    assertTrue(r0.material.pbr);
    assertEquals(0.7f, r0.material.roughness);
    assertEquals(3f, r0.material.uvScale.y);
    assertSame(g.res.textureByKey("gen:checker"), r0.material.texture);
    assertEquals(3.5f, arm.get(Rigidbody.class).mass);
    assertTrue(arm.get(Rigidbody.class).lockRotation);
    assertSame(base, arm.get(HingeJoint.class).connected);
    assertEquals(new Vector3f(1f, 0f, 0f), arm.get(HingeJoint.class).axis);
    assertFalse(arm.get(Spinner.class).isEnabled());
    assertEquals(new Vector3f(1f, 2f, 3f), arm.get(Spinner.class).degreesPerSecond);
    assertEquals(4, base.get(MeshCollider.class).positions().length / 3);
    assertTrue(base.get(MeshCollider.class).twoSided);
    final var lamp = loaded.get(2);
    assertEquals(Light.Type.SPOT, lamp.get(Light.class).type);
    assertTrue(lamp.get(SphereCollider.class).trigger);
    assertNull(lamp.get(DistanceJoint.class).connected);
    assertEquals(Float.POSITIVE_INFINITY, lamp.get(DistanceJoint.class).breakImpulse);
    assertEquals(Rolloff.LINEAR, lamp.get(AudioSource.class).rolloffMode);
    assertSame(g.res.soundByKey("gen:hum"), lamp.get(AudioSource.class).clip);
    assertEquals(45f, loaded.get(3).get(FirstPersonController.class).yaw());
    assertEquals(75f, loaded.get(3).get(Camera.class).fovDegrees);
  }

  @Test
  void prefabsInstantiateIndependentCopiesThatShareMaterials() {
    final var f = new Fixture();
    build(f);
    final var serializer = new SceneSerializer();
    final var base = f.scene.entities().get(0);
    final var prefab = Prefab.of(base, f.scene, f.ctx(), serializer);

    final var g = new Fixture();
    g.res.texture("gen:checker", new ImageData(1, 1, new byte[] { 1, 2, 3, 4 }));
    final var a = prefab.instantiate(g.scene, g.ctx(), new Vector3f(10f, 0f, 0f), null);
    final var b = prefab.instantiate(g.scene, g.ctx(), new Vector3f(-10f, 0f, 0f), null);
    g.scene.flush();
    assertEquals(4, g.scene.entities().size()); // base and arm, twice; the lamp and player are not in the prefab
    assertNotSame(a, b);
    assertEquals(10f, a.transform().position().x);
    assertEquals(-10f, b.transform().position().x);
    final var armA = g.scene.entities().get(1);
    final var armB = g.scene.entities().get(3);
    assertSame(a.transform(), armA.transform().parent());
    assertSame(b.transform(), armB.transform().parent());
    assertSame(a, armA.get(HingeJoint.class).connected); // references stay within the instance
    assertSame(b, armB.get(HingeJoint.class).connected);
    assertSame(a.get(MeshRenderer.class).material, b.get(MeshRenderer.class).material);
    assertEquals(1, prefab.materials().size());
  }

  /** A game component with its own serializer. */
  static final class Health extends Component {
    public int hitPoints = 100;
    public String team = "red";
  }

  static final class Unsaved extends Component {
  }

  @Test
  void customAndUnknownComponents() {
    final var f = new Fixture();
    final var registry = ComponentRegistry.defaults()
        .register(new FieldSerializer<>("Health", Health.class, ctx -> new Health()));
    final var serializer = new SceneSerializer(registry);
    final var e = new Entity("unit");
    e.add(new Health()).hitPoints = 42;
    e.add(new Unsaved());
    f.scene.add(e);
    final var text = serializer.write(f.scene, f.ctx());
    assertFalse(text.contains("Unsaved"));
    final var loaded = serializer.read(text, f.ctx()).get(0);
    assertEquals(42, loaded.get(Health.class).hitPoints);
    assertEquals("red", loaded.get(Health.class).team);
    assertEquals(1, loaded.componentCount());

    // a file with a type this registry does not know still loads, without it
    final var withoutHealth = new SceneSerializer().read(text, f.ctx()).get(0);
    assertEquals(0, withoutHealth.componentCount());
    assertThrows(IllegalArgumentException.class, () -> serializer.read("{\"format\": \"other\"}", f.ctx()));
    final Map<String, Object> tree = Json.asObject(Json.parse(text));
    assertEquals(SceneSerializer.VERSION, Json.asInt(tree.get("version")));
  }
}
