package engine.audio;

import java.util.Arrays;

/**
 * Volumes of the {@link AudioBus}es (linear, 0 silent to 1 as recorded; more than 1 amplifies) and a global mute. A
 * sound on a bus is scaled by that bus and by the master bus.
 */
public class AudioMixer {
  private final float[] volumes = new float[AudioBus.values().length];
  private boolean muted;

  public AudioMixer() {
    Arrays.fill(this.volumes, 1f);
  }

  public float volume(AudioBus bus) {
    return this.volumes[bus.ordinal()];
  }

  public void setVolume(AudioBus bus, float volume) {
    this.volumes[bus.ordinal()] = Math.max(0f, volume);
  }

  public boolean isMuted() {
    return this.muted;
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  /** The factor applied to everything played on {@code bus}. */
  public float gain(AudioBus bus) {
    if (this.muted) {
      return 0f;
    }
    final float master = this.volumes[AudioBus.MASTER.ordinal()];
    return bus == AudioBus.MASTER ? master : master * this.volumes[bus.ordinal()];
  }

  /** Converts decibels (0 = unchanged, -6 = about half) to a linear volume. */
  public static float fromDecibels(float db) {
    return (float) Math.pow(10.0, db / 20.0);
  }

  public static float toDecibels(float volume) {
    return volume <= 0f ? Float.NEGATIVE_INFINITY : (float) (20.0 * Math.log10(volume));
  }
}
