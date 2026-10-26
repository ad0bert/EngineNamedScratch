package engine.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;

class AnimationTest {

  @Test
  void easingCurvesStartAndEndInPlace() {
    for (Easing e : Easing.values()) {
      assertEquals(0f, e.apply(0f), 1e-6f, e.name());
      assertEquals(1f, e.apply(1f), 1e-6f, e.name());
    }
    assertEquals(0.25f, Easing.QUAD_IN.apply(0.5f), 1e-6f);
    assertEquals(0.5f, Easing.SINE_IN_OUT.apply(0.5f), 1e-6f);
    assertTrue(Easing.BACK_OUT.apply(0.7f) > 1f); // overshoots
  }

  @Test
  void linearStepAndEasedSampling() {
    final float[] out = new float[4];
    final var t = Track.linear(new float[] { 0f, 1f, 3f }, new float[] { 0f, 10f, 30f }, 1);
    t.sample(-1f, out, false);
    assertEquals(0f, out[0]);
    t.sample(0.5f, out, false);
    assertEquals(5f, out[0], 1e-5f);
    t.sample(2f, out, false);
    assertEquals(20f, out[0], 1e-5f);
    t.sample(9f, out, false);
    assertEquals(30f, out[0]);
    assertEquals(3f, t.duration());

    final var step = new Track(new float[] { 0f, 1f }, new float[] { 1f, 2f }, 1, Track.Interpolation.STEP, null);
    step.sample(0.99f, out, false);
    assertEquals(1f, out[0]);
    step.sample(1f, out, false);
    assertEquals(2f, out[0]);

    final var eased = Track.tween(new float[] { 0f }, new float[] { 1f }, 2f, Easing.QUAD_IN);
    eased.sample(1f, out, false);
    assertEquals(0.25f, out[0], 1e-6f);
    assertThrows(IllegalArgumentException.class, () -> Track.linear(new float[] { 0f, 1f }, new float[] { 1f }, 1));
  }

  @Test
  void cubicSplineFollowsTheTangents() {
    // keys 0 -> 1 with zero tangents: a smoothstep; with matching slopes: a straight line
    final float[] out = new float[1];
    final var flat = new Track(new float[] { 0f, 1f }, new float[] { 0, 0, 0, 0, 1, 0 }, 1,
        Track.Interpolation.CUBIC_SPLINE, null);
    flat.sample(0.5f, out, false);
    assertEquals(0.5f, out[0], 1e-6f);
    flat.sample(0.25f, out, false);
    assertEquals(0.15625f, out[0], 1e-6f);
    final var line = new Track(new float[] { 0f, 2f }, new float[] { 0.5f, 0, 0.5f, 0.5f, 1, 0.5f }, 1,
        Track.Interpolation.CUBIC_SPLINE, null);
    line.sample(0.5f, out, false);
    assertEquals(0.25f, out[0], 1e-6f);
  }

  @Test
  void rotationsAreSlerpedTheShortWay() {
    final var a = new Quaternionf().rotateY((float) Math.toRadians(10));
    final var b = new Quaternionf().rotateY((float) Math.toRadians(90));
    b.mul(-1f); // the same rotation with the opposite sign: must not go the long way round
    final var t = Track.linear(new float[] { 0f, 1f }, new float[] { a.x, a.y, a.z, a.w, b.x, b.y, b.z, b.w }, 4);
    final float[] out = new float[4];
    t.sample(0.5f, out, true);
    final var mid = new Quaternionf(out[0], out[1], out[2], out[3]);
    final var expected = new Quaternionf().rotateY((float) Math.toRadians(50));
    assertEquals(1f, Math.abs(mid.dot(expected)), 1e-5f);
    assertEquals(1f, mid.lengthSquared(), 1e-5f);
  }

  @Test
  void loopModes() {
    final var clip = AnimationClip.tween(Channel.Property.POSITION, new float[] { 0, 0, 0 }, new float[] { 2, 0, 0 },
        2f, Easing.LINEAR);
    final var scene = new Scene();
    final var e = new Entity("box");
    final var animator = e.add(new Animator());
    scene.add(e);
    scene.flush();
    final var system = new AnimationSystem();

    final var layer = animator.play(clip, AnimationLayer.Loop.PING_PONG);
    system.animate(scene, animator, 2.5f);
    assertEquals(1.5f, e.transform().position().x, 1e-5f); // on the way back
    animator.play(clip, AnimationLayer.Loop.LOOP);
    system.animate(scene, animator, 2.5f);
    assertEquals(0.5f, e.transform().position().x, 1e-5f);
    final var once = animator.play(clip, AnimationLayer.Loop.ONCE);
    system.animate(scene, animator, 2.5f);
    assertEquals(2f, e.transform().position().x, 1e-5f);
    assertTrue(once.isFinished());
    assertFalse(layer == once);
  }

  @Test
  void childrenAreFoundByNameAndClipsBlend() {
    final var scene = new Scene();
    final var root = new Entity("rig");
    final var arm = new Entity("arm");
    arm.transform().setParent(root.transform());
    scene.add(root);
    scene.add(arm);
    scene.flush();
    final var animator = root.add(new Animator());
    final var up = new AnimationClip("up").add(Channel.transform("arm", Channel.Property.POSITION,
        Track.linear(new float[] { 0f }, new float[] { 0f, 10f, 0f }, 3)));
    final var side = new AnimationClip("side").add(Channel.transform("arm", Channel.Property.POSITION,
        Track.linear(new float[] { 0f }, new float[] { 10f, 0f, 0f }, 3)));
    final var system = new AnimationSystem();
    animator.play(up);
    animator.blend(side, 0.25f, AnimationLayer.Loop.LOOP);
    system.animate(scene, animator, 0.1f);
    assertEquals(new Vector3f(2.5f, 7.5f, 0f), arm.transform().position()); // 25 % of the second clip
    assertEquals(new Vector3f(), root.transform().position()); // only the named child moves

    // a cross-fade moves from one clip to the other over its duration, then drops the old layer
    animator.play(up);
    system.animate(scene, animator, 0.1f);
    animator.crossFade(side, 1f, AnimationLayer.Loop.LOOP);
    system.animate(scene, animator, 0.5f);
    assertEquals(5f, arm.transform().position().x, 1e-4f);
    assertEquals(5f, arm.transform().position().y, 1e-4f);
    system.animate(scene, animator, 0.6f);
    assertEquals(new Vector3f(10f, 0f, 0f), arm.transform().position());
    assertEquals(1, animator.layers().size());
  }

  @Test
  void customTargetsAndTweens() {
    final var scene = new Scene();
    final var e = new Entity("lamp");
    final var animator = e.add(new Animator());
    scene.add(e);
    scene.flush();
    final float[] color = new float[3];
    animator.tween(v -> System.arraycopy(v, 0, color, 0, 3), new float[] { 0, 0, 0 }, new float[] { 1, 0.5f, 0 },
        1f, Easing.LINEAR, AnimationLayer.Loop.ONCE);
    animator.tween(Channel.Property.EULER_DEGREES, new float[] { 0, 0, 0 }, new float[] { 0, 90, 0 }, 1f,
        Easing.LINEAR, AnimationLayer.Loop.ONCE);
    new AnimationSystem().animate(scene, animator, 0.5f);
    assertEquals(0.5f, color[0], 1e-6f);
    assertEquals(0.25f, color[1], 1e-6f);
    final var expected = new Quaternionf().rotateY((float) Math.toRadians(45));
    assertTrue(e.transform().rotation().equals(expected, 1e-5f));
    assertThrows(IllegalArgumentException.class, () -> Channel.transform("", Channel.Property.ROTATION,
        Track.linear(new float[] { 0f }, new float[] { 0, 0, 0 }, 3)));
  }
}
