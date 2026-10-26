package engine.audio;

import org.joml.Vector3f;

/** One sound that wants to be heard: an {@link AudioSource}'s state or a one-shot. Owned by {@link AudioSystem}. */
final class Playback {
  enum State {
    STOPPED, PLAYING, PAUSED
  }

  AudioClip clip;
  AudioBus bus = AudioBus.EFFECTS;
  float volume = 1f;
  float pitch = 1f;
  boolean loop;
  boolean spatial = true;
  float minDistance = 1f;
  float maxDistance = 50f;
  float rolloff = 1f;
  Rolloff rolloffMode = Rolloff.INVERSE;
  int priority;
  final Vector3f position = new Vector3f();
  final Vector3f velocity = new Vector3f();
  final Vector3f lastPosition = new Vector3f();
  boolean hasLastPosition;

  State state = State.STOPPED;
  /** Seconds into the clip (kept while the sound is virtual, so it resumes in the right place). */
  float time;
  /** The voice playing it, or -1 while it is virtual. */
  int voice = -1;
  /** Play was requested while holding a voice: start the clip again. */
  boolean restart;
  boolean oneShot;
  /** Final gain (bus, volume, distance) and ranking score of the current step. */
  float gain;
  float score;

  boolean active() {
    return this.state != State.STOPPED;
  }

  void start() {
    this.state = State.PLAYING;
    this.time = 0f;
    this.restart = true;
  }

  /** Advances the clock of a playing sound; returns false if a non-looping sound has reached its end. */
  boolean advance(float dt) {
    if (this.state != State.PLAYING) {
      return true;
    }
    this.time += dt * this.pitch;
    final float d = this.clip.duration();
    if (this.time >= d) {
      if (!this.loop) {
        return false;
      }
      if (d > 0f && Float.isFinite(d)) {
        this.time %= d;
      }
    }
    return true;
  }
}
