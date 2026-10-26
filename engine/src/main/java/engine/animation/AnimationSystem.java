package engine.animation;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.ecs.Transform;
import lombok.extern.slf4j.Slf4j;

/**
 * Advances every {@link Animator}'s layers, samples their channels, blends the layers and writes the result into the
 * transforms and custom targets. Channel targets are looked up by name once per layer (again when the scene's
 * entities change); the per-step work does not allocate.
 */
@Slf4j
public class AnimationSystem implements ISystem {
  private static final ThreadLocal<Vector3f> EULER = ThreadLocal.withInitial(Vector3f::new);
  private final Map<Transform, Entity> owners = new IdentityHashMap<>();
  private final ArrayDeque<Transform> queue = new ArrayDeque<>();
  private int ownersVersion = -1;

  @Override
  public void update(Scene scene, double dtD) {
    final float dt = (float) dtD;
    final var animators = scene.components(Animator.class);
    for (int i = 0; i < animators.size(); i++) {
      animate(scene, animators.get(i), dt);
    }
  }

  /** Samples and applies one animator; public so tools and tests can drive an animator without a scene loop. */
  public void animate(Scene scene, Animator animator, float dt) {
    final var layers = animator.mutableLayers();
    if (animator.sceneVersion != scene.version()) { // entities came or went: look the targets up again
      animator.sceneVersion = scene.version();
      animator.bindings.clear();
      for (int i = 0; i < layers.size(); i++) {
        layers.get(i).bindings = null;
      }
    }
    for (int i = layers.size() - 1; i >= 0; i--) {
      final var layer = layers.get(i);
      layer.advance(dt * animator.speed);
      if (layer.isFadedOut()) {
        layers.remove(i);
      }
    }
    final var bindings = animator.bindings;
    for (int b = 0; b < bindings.size(); b++) {
      bindings.get(b).touched = false;
    }
    for (int i = 0; i < layers.size(); i++) {
      final var layer = layers.get(i);
      if (layer.bindings == null) {
        bind(scene, animator, layer);
      }
      final float w = layer.effectiveWeight();
      if (w <= 0f) {
        continue;
      }
      final float t = layer.sampleTime();
      final var channels = layer.clip().channels();
      for (int c = 0; c < channels.size(); c++) {
        final var channel = channels.get(c);
        final var binding = bindings.get(layer.bindings[c]);
        if (binding.property != null && binding.transform == null) {
          continue;
        }
        final boolean rotation = binding.property == Channel.Property.ROTATION;
        channel.track().sample(t, binding.sample, rotation);
        if (!binding.touched) {
          binding.touched = true;
          if (w >= 1f) {
            System.arraycopy(binding.sample, 0, binding.value, 0, channel.track().width());
            continue;
          }
          current(binding); // a partial first layer blends over the pose as it is
        }
        mix(binding, channel.track().width(), w, rotation);
      }
    }
    for (int b = 0; b < bindings.size(); b++) {
      final var binding = bindings.get(b);
      if (binding.touched) {
        apply(binding);
      }
    }
  }

  private static void mix(Animator.Binding b, int width, float w, boolean rotation) {
    if (rotation) {
      final float[] v = b.value;
      final float[] s = b.sample;
      Track.slerp(v[0], v[1], v[2], v[3], s[0], s[1], s[2], s[3], w, v);
      Track.normalize(v);
      return;
    }
    for (int k = 0; k < width; k++) {
      b.value[k] += (b.sample[k] - b.value[k]) * w;
    }
  }

  /** Reads the target's present value into the binding (custom targets keep what was applied last). */
  private static void current(Animator.Binding b) {
    if (b.property == null) {
      return;
    }
    final var t = b.transform;
    switch (b.property) {
    case POSITION -> {
      b.value[0] = t.position().x;
      b.value[1] = t.position().y;
      b.value[2] = t.position().z;
    }
    case SCALE -> {
      b.value[0] = t.scale().x;
      b.value[1] = t.scale().y;
      b.value[2] = t.scale().z;
    }
    case ROTATION -> {
      b.value[0] = t.rotation().x;
      b.value[1] = t.rotation().y;
      b.value[2] = t.rotation().z;
      b.value[3] = t.rotation().w;
    }
    case EULER_DEGREES -> {
      t.rotation().getEulerAnglesXYZ(EULER.get());
      b.value[0] = (float) Math.toDegrees(EULER.get().x);
      b.value[1] = (float) Math.toDegrees(EULER.get().y);
      b.value[2] = (float) Math.toDegrees(EULER.get().z);
    }
    }
  }

  private static void apply(Animator.Binding b) {
    final float[] v = b.value;
    if (b.property == null) {
      b.target.apply(v);
      return;
    }
    final var t = b.transform;
    switch (b.property) {
    case POSITION -> t.position().set(v[0], v[1], v[2]);
    case SCALE -> t.scale().set(v[0], v[1], v[2]);
    case ROTATION -> t.rotation().set(v[0], v[1], v[2], v[3]);
    case EULER_DEGREES -> t.setEulerDegrees(v[0], v[1], v[2]);
    }
  }

  /** Finds (or creates) the binding of every channel of the layer. */
  private void bind(Scene scene, Animator animator, AnimationLayer layer) {
    final var channels = layer.clip().channels();
    layer.bindings = new int[channels.size()];
    for (int c = 0; c < channels.size(); c++) {
      final var ch = channels.get(c);
      int found = -1;
      final var bindings = animator.bindings;
      for (int b = 0; b < bindings.size() && found < 0; b++) {
        final var x = bindings.get(b);
        if (ch.property() != null ? ch.property() == x.property && ch.node().equals(x.node) : ch.target() == x.target) {
          found = b;
        }
      }
      if (found < 0) {
        final var binding = new Animator.Binding(ch.node(), ch.property(), ch.target(), ch.track().width());
        if (ch.property() != null) {
          binding.transform = find(scene, animator.entity(), ch.node());
          if (binding.transform == null) {
            log.warn("{}: no entity named '{}' under {}", layer.clip().name(), ch.node(), animator.entity().name());
          }
        }
        bindings.add(binding);
        found = bindings.size() - 1;
      }
      layer.bindings[c] = found;
    }
  }

  /** The transform of the entity named {@code name} at or below {@code root} (breadth first), or null. */
  private Transform find(Scene scene, Entity root, String name) {
    if (name.isEmpty() || name.equals(root.name())) {
      return root.transform();
    }
    if (this.ownersVersion != scene.version()) {
      this.ownersVersion = scene.version();
      this.owners.clear();
      for (Entity e : scene.entities()) {
        this.owners.put(e.transform(), e);
      }
    }
    this.queue.clear();
    this.queue.add(root.transform());
    while (!this.queue.isEmpty()) {
      final var t = this.queue.poll();
      final var e = this.owners.get(t);
      if (e != null && e.name().equals(name)) {
        return t;
      }
      this.queue.addAll(t.children());
    }
    return null;
  }
}
