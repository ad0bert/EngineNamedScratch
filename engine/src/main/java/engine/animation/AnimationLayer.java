package engine.animation;

import lombok.Getter;

/**
 * A clip being played by an {@link Animator}: its clock, speed, looping and blend weight. Layers are applied in order;
 * each one blends its values over what the layers before it produced (or over the current pose, for the first) by
 * its weight, so a layer at weight 1 overrides, one at 0.3 mixes 30 % of itself in.
 */
public final class AnimationLayer {
  public enum Loop {
    /** Plays once and holds the last pose. */
    ONCE,
    /** Starts over at the end. */
    LOOP,
    /** Plays forwards, then backwards, and so on. */
    PING_PONG
  }

  @Getter private final AnimationClip clip;
  @Getter private final Loop loop;
  /** Playback speed; negative plays backwards. */
  public float speed = 1f;
  /** Blend weight 0..1 (multiplied with the fade). */
  public float weight = 1f;
  @Getter private float time;
  private float fade = 1f;
  private float fadeSpeed; // per second; negative fades out
  int[] bindings; // channel -> binding index of the animator, resolved by the system
  int bindingVersion = -1;

  AnimationLayer(AnimationClip clip, Loop loop) {
    this.clip = clip;
    this.loop = loop;
  }

  public void setTime(float seconds) {
    this.time = seconds;
  }

  /** The fade (0..1) the weight is multiplied with. */
  public float fade() {
    return this.fade;
  }

  /** How fast the fade changes, per second (negative: fading out). */
  public float fadeSpeed() {
    return this.fadeSpeed;
  }

  /** Sets the fade and its rate of change directly (restoring a saved state). */
  public void setFade(float fade, float fadeSpeed) {
    this.fade = fade;
    this.fadeSpeed = fadeSpeed;
  }

  /** Changes the fade (0..1) linearly to {@code target} over {@code seconds}. */
  public void fadeTo(float target, float seconds) {
    if (seconds <= 0f) {
      this.fade = target;
      this.fadeSpeed = 0f;
    } else {
      this.fadeSpeed = (target - this.fade) / seconds;
    }
  }

  /** The weight it is applied with now: {@link #weight} times the fade. */
  public float effectiveWeight() {
    return Math.max(0f, Math.min(1f, this.weight * this.fade));
  }

  /** True for a {@link Loop#ONCE} layer that reached its end (or start, playing backwards). */
  public boolean isFinished() {
    final float d = this.clip.duration();
    return this.loop == Loop.ONCE && (this.speed >= 0f ? this.time >= d : this.time <= 0f);
  }

  boolean isFadedOut() {
    return this.fadeSpeed < 0f && this.fade <= 0f;
  }

  void advance(float dt) {
    this.time += dt * this.speed;
    if (this.fadeSpeed != 0f) {
      this.fade += this.fadeSpeed * dt;
      if (this.fadeSpeed > 0f && this.fade >= 1f) {
        this.fade = 1f;
        this.fadeSpeed = 0f;
      } else if (this.fade <= 0f) {
        this.fade = 0f;
      }
    }
  }

  /** The time to sample the clip at, after looping. */
  float sampleTime() {
    final float d = this.clip.duration();
    if (d <= 0f) {
      return 0f;
    }
    return switch (this.loop) {
    case ONCE -> Math.max(0f, Math.min(d, this.time));
    case LOOP -> {
      final float t = this.time % d;
      yield t < 0f ? t + d : t;
    }
    case PING_PONG -> {
      float t = this.time % (2f * d);
      if (t < 0f) {
        t += 2f * d;
      }
      yield t <= d ? t : 2f * d - t;
    }
    };
  }
}
