package engine.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import engine.ecs.Component;
import engine.ecs.Transform;

/**
 * Plays {@link AnimationClip}s on its entity and the entity's descendants (channels name them), driven by
 * {@link AnimationSystem} once per fixed step, before physics, so animated kinematic bodies push what they touch.
 * Several clips can play at once as {@link AnimationLayer}s: {@link #crossFade} blends from the current clips to a
 * new one, {@link #blend} mixes a clip in at a fixed weight (say 30 % run over walk), {@link #tween} adds a one-off
 * movement on top.
 *
 * <p>
 * An animator can keep a library of named clips (a glTF model's animations are put there).
 */
public class Animator extends Component {
  /** Multiplies the speed of every layer. */
  public float speed = 1f;
  private final List<AnimationLayer> layers = new ArrayList<>();
  private final Map<String, AnimationClip> clips = new LinkedHashMap<>();

  // what the layers animate, shared by all layers that animate the same thing (see AnimationSystem)
  final List<Binding> bindings = new ArrayList<>();
  int sceneVersion = -1;

  /** A property of one transform, or one custom target, with the value being built this step. */
  static final class Binding {
    final String node;
    final Channel.Property property;
    final Channel.Target target;
    Transform transform; // null if the node was not found
    final float[] value;
    final float[] sample;
    boolean touched;

    Binding(String node, Channel.Property property, Channel.Target target, int width) {
      this.node = node;
      this.property = property;
      this.target = target;
      this.value = new float[Math.max(4, width)];
      this.sample = new float[Math.max(4, width)];
    }
  }

  public Animator() {
  }

  /** Starts with a clip playing (looped). */
  public Animator(AnimationClip clip) {
    play(clip);
  }

  /** Adds a clip to the library under its name. */
  public Animator addClip(AnimationClip clip) {
    this.clips.put(clip.name(), clip);
    return this;
  }

  /** The library clip with this name, or null. */
  public AnimationClip clip(String name) {
    return this.clips.get(name);
  }

  public Map<String, AnimationClip> clips() {
    return Collections.unmodifiableMap(this.clips);
  }

  public List<AnimationLayer> layers() {
    return Collections.unmodifiableList(this.layers);
  }

  /** Stops everything and plays the clip, looped. */
  public AnimationLayer play(AnimationClip clip) {
    return play(clip, AnimationLayer.Loop.LOOP);
  }

  /** Stops everything and plays the clip. */
  public AnimationLayer play(AnimationClip clip, AnimationLayer.Loop loop) {
    this.layers.clear();
    return add(clip, loop, 1f);
  }

  /** Plays a library clip by name. */
  public AnimationLayer play(String clipName, AnimationLayer.Loop loop) {
    final var clip = this.clips.get(clipName);
    if (clip == null) {
      throw new IllegalArgumentException("no clip named " + clipName);
    }
    return play(clip, loop);
  }

  /** Fades the current layers out and the clip in over {@code seconds}. */
  public AnimationLayer crossFade(AnimationClip clip, float seconds, AnimationLayer.Loop loop) {
    for (AnimationLayer l : this.layers) {
      l.fadeTo(0f, seconds);
    }
    final var layer = add(clip, loop, 1f);
    layer.fadeTo(0f, 0f);
    layer.fadeTo(1f, seconds);
    return layer;
  }

  /** Adds a layer at a fixed weight on top of the current ones. */
  public AnimationLayer blend(AnimationClip clip, float weight, AnimationLayer.Loop loop) {
    return add(clip, loop, weight);
  }

  /** Adds a one-off transform tween of this entity on top of the current layers. */
  public AnimationLayer tween(Channel.Property property, float[] from, float[] to, float seconds, Easing easing,
      AnimationLayer.Loop loop) {
    return add(AnimationClip.tween(property, from, to, seconds, easing), loop, 1f);
  }

  /** Adds a one-off tween of anything (a material colour, a volume...) on top of the current layers. */
  public AnimationLayer tween(Channel.Target target, float[] from, float[] to, float seconds, Easing easing,
      AnimationLayer.Loop loop) {
    return add(AnimationClip.tween(target, from, to, seconds, easing), loop, 1f);
  }

  public void remove(AnimationLayer layer) {
    this.layers.remove(layer);
  }

  public void stop() {
    this.layers.clear();
  }

  private AnimationLayer add(AnimationClip clip, AnimationLayer.Loop loop, float weight) {
    final var layer = new AnimationLayer(clip, loop);
    layer.weight = weight;
    this.layers.add(layer);
    return layer;
  }

  List<AnimationLayer> mutableLayers() {
    return this.layers;
  }
}
