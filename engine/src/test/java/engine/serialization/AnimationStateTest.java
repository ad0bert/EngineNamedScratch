package engine.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import engine.animation.AnimationClip;
import engine.animation.AnimationLayer;
import engine.animation.AnimationSystem;
import engine.animation.Animator;
import engine.animation.Channel;
import engine.animation.Easing;
import engine.animation.Track;
import engine.audio.AudioData;
import engine.audio.AudioSource;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.input.InputState;
import engine.render.Material;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.Skin;
import engine.resources.ResourceManager;

/** Animators, skins and the play state of sounds survive a save and a load. */
class AnimationStateTest {
  private final ResourceManager res = new ResourceManager(SerializationTest.fakeBackend());
  private final InputState input = new InputState();

  private SerializationContext ctx() {
    return new SerializationContext(this.res, this.input);
  }

  private List<Entity> roundTrip(Scene scene) {
    final var json = new SceneSerializer().write(scene, ctx());
    return new SceneSerializer().read(json, ctx());
  }

  private static Entity named(List<Entity> entities, String name) {
    return entities.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void animatorsKeepTheirClipsAndLayers() {
    final var scene = new Scene();
    final var root = new Entity("root");
    final var arm = new Entity("arm");
    arm.transform().setParent(root.transform());
    final var swing = new AnimationClip("swing")
        .add(Channel.transform("arm", Channel.Property.ROTATION, Track.linear(new float[] { 0f, 1f },
            new float[] { 0, 0, 0, 1, 0, 0, 0.7071068f, 0.7071068f }, 4)))
        .add(Channel.transform("", Channel.Property.POSITION, Track.tween(new float[] { 0, 0, 0 },
            new float[] { 0, 2, 0 }, 2f, Easing.BOUNCE_OUT)))
        .add(Channel.custom(v -> {
        }, Track.linear(new float[] { 0f }, new float[] { 1f }, 1))); // code: not saved
    final var idle = new AnimationClip("idle").add(Channel.transform("", Channel.Property.SCALE,
        new Track(new float[] { 0f, 1f }, new float[] { 1, 1, 1, 2, 2, 2 }, 3, Track.Interpolation.STEP, null)));
    final var animator = root.add(new Animator());
    animator.addClip(swing).addClip(idle);
    animator.speed = 0.5f;
    animator.play(idle, AnimationLayer.Loop.PING_PONG).setTime(0.25f);
    final var fadingIn = animator.crossFade(swing, 2f, AnimationLayer.Loop.ONCE);
    fadingIn.setTime(0.4f);
    fadingIn.speed = -1f;
    // halfway through the cross-fade
    animator.layers().forEach(l -> l.setFade(l == fadingIn ? 0.5f : 0.5f, l == fadingIn ? 0.5f : -0.5f));
    scene.add(root);
    scene.add(arm);

    final var loaded = roundTrip(scene);
    final var a = named(loaded, "root").get(Animator.class);
    assertEquals(0.5f, a.speed);
    assertEquals(List.of("swing", "idle"), List.copyOf(a.clips().keySet()));
    final var clip = a.clip("swing");
    assertEquals(2, clip.channels().size(), "the custom channel is left out");
    assertEquals(Easing.BOUNCE_OUT, clip.channels().get(1).track().easing());
    assertEquals(2f, clip.duration());
    assertEquals(Track.Interpolation.STEP, a.clip("idle").channels().get(0).track().interpolation());

    assertEquals(2, a.layers().size());
    final var first = a.layers().get(0);
    assertSame(a.clip("idle"), first.clip(), "layers and the library share clips");
    assertEquals(AnimationLayer.Loop.PING_PONG, first.loop());
    assertEquals(0.25f, first.time());
    assertEquals(-0.5f, first.fadeSpeed());
    final var second = a.layers().get(1);
    assertSame(clip, second.clip());
    assertEquals(AnimationLayer.Loop.ONCE, second.loop());
    assertEquals(-1f, second.speed);
    assertEquals(0.4f, second.time());
    assertEquals(0.5f, second.fade());
    assertEquals(0.5f, second.fadeSpeed());

    // and it plays: the arm turns like the original's
    final var system = new AnimationSystem();
    final var s2 = new Scene();
    loaded.forEach(s2::add);
    s2.flush();
    system.animate(s2, a, 0.1f);
    new AnimationSystem().animate(scene, animator, 0.1f); // a system caches the scene's names
    final var expected = arm.transform().rotation();
    final var actual = named(loaded, "arm").transform().rotation();
    assertTrue(new Quaternionf(expected).equals(actual, 1e-5f), expected + " vs " + actual);
  }

  @Test
  void skinsAreSharedAndPointAtTheJoints() {
    final var scene = new Scene();
    final var mesh = this.res.mesh("gen:skinned", MeshFactory.cube(1f));
    final var hip = new Entity("hip");
    final var knee = new Entity("knee");
    knee.transform().setParent(hip.transform());
    knee.transform().position().set(0f, -1f, 0f);
    final var skin = new Skin(new engine.ecs.Transform[] { hip.transform(), knee.transform() },
        new Matrix4f[] { new Matrix4f(), new Matrix4f().translation(0f, 1f, 0f) });
    final var material = new Material();
    final var a = new Entity("leg-a");
    a.add(new MeshRenderer(mesh, material)).skin = skin;
    final var b = new Entity("leg-b"); // listed before the joints: they are linked once all exist
    b.add(new MeshRenderer(mesh, material)).skin = skin;
    scene.add(a);
    scene.add(b);
    scene.add(hip);
    scene.add(knee);

    final var loaded = roundTrip(scene);
    final var sa = named(loaded, "leg-a").get(MeshRenderer.class).skin;
    assertNotNull(sa);
    assertSame(sa, named(loaded, "leg-b").get(MeshRenderer.class).skin);
    assertEquals(2, sa.jointCount());
    assertSame(named(loaded, "hip").transform(), sa.joint(0));
    assertSame(named(loaded, "knee").transform(), sa.joint(1));
    assertTrue(sa.inverseBind(1, new Matrix4f()).equals(new Matrix4f().translation(0f, 1f, 0f), 0f));
  }

  @Test
  void aSkinWhoseJointsAreNotSavedIsLeftOut() {
    final var scene = new Scene();
    final var outside = new Entity("not-saved");
    final var e = new Entity("leg");
    e.add(new MeshRenderer(this.res.mesh("gen:m", MeshFactory.cube(1f)), new Material())).skin = new Skin(
        new engine.ecs.Transform[] { outside.transform() }, new Matrix4f[] { new Matrix4f() });
    scene.add(e);
    final var loaded = roundTrip(scene);
    assertNull(loaded.get(0).get(MeshRenderer.class).skin);
  }

  @Test
  void soundsCarryOnWhereTheyWere() {
    final var scene = new Scene();
    final var clip = this.res.sound("gen:hum", AudioData.mono(8000, new float[8000 * 4]));
    final var playing = new Entity("radio");
    final var radio = playing.add(new AudioSource(clip));
    radio.loop = true;
    radio.playOnStart = true;
    radio.play(1.5f);
    final var paused = new Entity("tape");
    final var tape = paused.add(new AudioSource(clip));
    tape.play(2f);
    tape.pause();
    final var silent = new Entity("bell");
    silent.add(new AudioSource(clip));
    scene.add(playing);
    scene.add(paused);
    scene.add(silent);

    final var loaded = roundTrip(scene);
    final var r = named(loaded, "radio").get(AudioSource.class);
    assertTrue(r.isPlaying());
    assertEquals(1.5f, r.time());
    final var t = named(loaded, "tape").get(AudioSource.class);
    assertTrue(t.isPaused());
    assertEquals(2f, t.time());
    assertFalse(named(loaded, "bell").get(AudioSource.class).isPlaying());

    // playOnStart does not send the loaded radio back to the start
    final var audio = new engine.audio.AudioSystem(new engine.audio.NullAudioBackend());
    final var s2 = new Scene();
    loaded.forEach(s2::add);
    s2.flush();
    audio.update(s2, 0.1);
    assertEquals(1.6f, r.time(), 1e-4f);
  }

  @Test
  void aSavedGltfInstanceStillAnimates() {
    final var res = new ResourceManager(SerializationTest.fakeBackend());
    final var scene = new Scene();
    final var root = res.gltf("models/tube.glb").instantiate(scene);
    root.get(Animator.class).play("bend", AnimationLayer.Loop.LOOP).setTime(0.3f);
    scene.flush();
    final var json = new SceneSerializer().write(scene, new SerializationContext(res, this.input));
    final var loaded = new SceneSerializer().read(json, new SerializationContext(res, this.input));
    final var tube = named(loaded, "tube-mesh").get(MeshRenderer.class);
    assertNotNull(tube.skin);
    assertEquals(4, tube.skin.jointCount());
    final var animator = named(loaded, "tube-root").get(Animator.class);
    assertEquals(3, animator.clips().size());
    assertEquals(0.3f, animator.layers().get(0).time());
  }
}
