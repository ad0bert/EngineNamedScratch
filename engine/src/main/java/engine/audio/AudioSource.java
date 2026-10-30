package engine.audio;

import engine.ecs.Component;

/**
 * Plays a clip at the entity's position (or, if not {@link #spatial}, straight into the speakers). {@link AudioSystem}
 * follows the entity, takes its velocity from a {@code Rigidbody} (or from its motion) for the Doppler effect, and
 * gives it one of the backend's voices while it is among the loudest and most important sounds; otherwise it keeps
 * playing silently ("virtual") and is heard again when it outranks another one.
 *
 * <p>
 * The settings may be changed at any time; they are applied at the next update step.
 */
public class AudioSource extends Component {
  public AudioClip clip;
  public AudioBus bus = AudioBus.EFFECTS;
  /** Linear volume, 1 as recorded. */
  public float volume = 1f;
  /** Playback speed: 1 as recorded, 2 an octave up. */
  public float pitch = 1f;
  public boolean loop;
  /** Positioned in the world (panned and attenuated by distance); false for music, ambience beds and UI sounds. */
  public boolean spatial = true;
  /** Full volume up to this distance (metres). */
  public float minDistance = 1f;
  /** The attenuation curve ends here (see {@link Rolloff}). */
  public float maxDistance = 50f;
  /** Steepness of the curve: 0 no attenuation, 1 physical, more for a sound that should fade quickly. */
  public float rolloff = 1f;
  public Rolloff rolloffMode = Rolloff.INVERSE;
  /** Higher priorities keep their voice over louder sounds of lower priority; music should use a high one. */
  public int priority;
  /** Starts playing as soon as the system first sees it in a scene. */
  public boolean playOnStart;

  final Playback playback = new Playback();
  boolean seen;

  public AudioSource() {
  }

  public AudioSource(AudioClip clip) {
    this.clip = clip;
  }

  /** Starts the clip from the beginning (again, if it was playing). */
  public void play() {
    this.playback.clip = this.clip;
    this.playback.start();
  }

  /** Starts the clip {@code seconds} in (where a saved game left it, for example). */
  public void play(float seconds) {
    play();
    this.playback.time = Math.max(0f, seconds);
  }

  public void stop() {
    this.playback.state = Playback.State.STOPPED;
  }

  /** Pauses a playing source; {@link #resume()} continues where it was. */
  public void pause() {
    if (this.playback.state == Playback.State.PLAYING) {
      this.playback.state = Playback.State.PAUSED;
    }
  }

  public void resume() {
    if (this.playback.state == Playback.State.PAUSED) {
      this.playback.state = Playback.State.PLAYING;
    }
  }

  /** True while playing, also while it is virtual (too quiet or unimportant to have a voice). */
  public boolean isPlaying() {
    return this.playback.state == Playback.State.PLAYING;
  }

  public boolean isPaused() {
    return this.playback.state == Playback.State.PAUSED;
  }

  /** True if a backend voice is playing it right now. */
  public boolean isAudible() {
    return this.playback.voice >= 0;
  }

  /** Seconds into the clip. */
  public float time() {
    return this.playback.time;
  }

  /** Copies the settings into the playback state; called by the system every step. */
  void sync() {
    final var p = this.playback;
    if (p.clip != this.clip && p.active()) {
      p.start(); // a new clip restarts
    }
    p.clip = this.clip;
    p.bus = this.bus;
    p.volume = this.volume;
    p.pitch = this.pitch;
    p.loop = this.loop;
    p.spatial = this.spatial;
    p.minDistance = this.minDistance;
    p.maxDistance = this.maxDistance;
    p.rolloff = this.rolloff;
    p.rolloffMode = this.rolloffMode;
    p.priority = this.priority;
    if (p.clip == null) {
      p.state = Playback.State.STOPPED;
    }
  }
}
