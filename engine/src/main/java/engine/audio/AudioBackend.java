package engine.audio;

import java.util.function.Supplier;

import org.joml.Vector3f;

/**
 * Everything the engine needs from a sound API. Game and engine code only see this interface and the opaque
 * {@link AudioClip}/{@link AudioVoice} handles; {@code engine.audio.al} is the OpenAL (JOAL) implementation and
 * {@link NullAudioBackend} the silent one used when there is no sound device. Call it from one thread (the engine uses
 * the GL thread).
 */
public interface AudioBackend {
  /** For logs, e.g. the device name. */
  String name();

  /** False for the silent backend. */
  boolean hasDevice();

  /** Number of voices that can play at the same time. */
  int voiceCount();

  AudioVoice voice(int index);

  AudioClip createClip(String name, AudioData data);

  /**
   * A clip that is decoded while it plays; {@code open} is called for every voice that starts it.
   *
   * @param duration length in seconds, or {@link Float#POSITIVE_INFINITY} if unknown
   */
  AudioClip createStreamingClip(String name, int channels, float duration, Supplier<PcmStream> open);

  /** Where the sounds are heard from; forward and up must be unit vectors. */
  void setListener(Vector3f position, Vector3f velocity, Vector3f forward, Vector3f up);

  /** Strength of the Doppler effect (0 off, 1 physical) and the speed of sound in metres per second. */
  void setDoppler(float factor, float speedOfSound);

  /** Keeps streams fed and lets voices notice that they finished; called once per update step. */
  void update(double dt);

  /** Stops every voice and releases the device; clips are unusable afterwards. Safe to call twice. */
  void dispose();
}
