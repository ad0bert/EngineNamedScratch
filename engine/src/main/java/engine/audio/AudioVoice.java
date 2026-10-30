package engine.audio;

/**
 * Opaque handle to one hardware or mixer voice of an {@link AudioBackend} (an OpenAL source). The backend owns a fixed
 * number of them; {@link AudioSystem} decides which sounds get one. Gains arrive fully computed (bus, volume and
 * distance attenuation): the backend only pans spatial voices and applies the Doppler shift.
 */
public interface AudioVoice {
  /** Starts the clip from {@code offsetSeconds} (ignored by streaming clips, which start at the beginning). */
  void start(AudioClip clip, boolean loop, float offsetSeconds);

  void stop();

  void setPaused(boolean paused);

  /** {@code gain} is linear (1 = as recorded); {@code pitch} 1 is the recorded speed, 2 an octave up. */
  void setGainAndPitch(float gain, float pitch);

  /**
   * Spatial voices are positioned in the world and panned relative to the listener; non-spatial ones (music, UI)
   * play straight into the speakers.
   */
  void setSpatial(boolean spatial);

  void setPosition(float x, float y, float z);

  void setVelocity(float x, float y, float z);

  /**
   * Treble kept of what goes straight to the speakers ({@code directHF}, 0..1: 1 unfiltered, less muffled) and how
   * much of the voice goes into the reverberation ({@code send}, 0..1). Ignored by backends without effects.
   */
  default void setFilter(float directHF, float send) {
  }

  /** False once a started clip has played to its end without looping, or after {@link #stop()}. */
  boolean isPlaying();

  /** The clip this voice is playing (or paused on), null after {@link #stop()}. */
  AudioClip clip();
}
