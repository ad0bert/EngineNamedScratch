package engine.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import lombok.Getter;

/**
 * A named set of {@link Channel}s played together, e.g. "walk" of a character or "pulse" of a lamp. Immutable once
 * built (add channels, then hand it to an {@link Animator}); one clip can drive any number of entities.
 */
public final class AnimationClip {
  @Getter private final String name;
  private final List<Channel> channels = new ArrayList<>();
  private final List<Channel> view = Collections.unmodifiableList(this.channels); // no wrapper per call
  @Getter private float duration;

  public AnimationClip(String name) {
    this.name = name;
  }

  public AnimationClip add(Channel channel) {
    this.channels.add(channel);
    this.duration = Math.max(this.duration, channel.track().duration());
    return this;
  }

  public List<Channel> channels() {
    return this.view;
  }

  /** A clip with one transform channel on the animator's own entity going from {@code from} to {@code to}. */
  public static AnimationClip tween(Channel.Property property, float[] from, float[] to, float seconds,
      Easing easing) {
    return new AnimationClip(property.name().toLowerCase(Locale.ROOT))
        .add(Channel.transform("", property, Track.tween(from, to, seconds, easing)));
  }

  /** A clip with one custom channel going from {@code from} to {@code to}. */
  public static AnimationClip tween(Channel.Target target, float[] from, float[] to, float seconds, Easing easing) {
    return new AnimationClip("tween").add(Channel.custom(target, Track.tween(from, to, seconds, easing)));
  }

  @Override
  public String toString() {
    return "AnimationClip[" + this.name + ", " + this.channels.size() + " channels, " + this.duration + " s]";
  }
}
