package engine.audio;

import java.util.function.Supplier;

import org.joml.Vector3f;

/**
 * A silent backend: used when no sound device is available (servers, CI, a machine without audio) or sound is turned
 * off with {@code -Dengine.audio=false}. Its voices keep time like real ones, so game code that waits for a sound to
 * end behaves the same, and tests can inspect what would be heard.
 */
public class NullAudioBackend implements AudioBackend {
  private final NullVoice[] voices;

  public NullAudioBackend() {
    this(32);
  }

  public NullAudioBackend(int voices) {
    this.voices = new NullVoice[voices];
    for (int i = 0; i < voices; i++) {
      this.voices[i] = new NullVoice();
    }
  }

  @Override
  public String name() {
    return "silent backend";
  }

  @Override
  public boolean hasDevice() {
    return false;
  }

  @Override
  public int voiceCount() {
    return this.voices.length;
  }

  @Override
  public NullVoice voice(int index) {
    return this.voices[index];
  }

  @Override
  public AudioClip createClip(String name, AudioData data) {
    return new Clip(name, data.duration(), data.channels(), false);
  }

  @Override
  public AudioClip createStreamingClip(String name, int channels, float duration, Supplier<PcmStream> open) {
    return new Clip(name, duration, channels, true);
  }

  @Override
  public void setListener(Vector3f position, Vector3f velocity, Vector3f forward, Vector3f up) {
  }

  @Override
  public void setDoppler(float factor, float speedOfSound) {
  }

  private ReverbPreset reverb;
  private float reverbWet;

  /** Kept (and readable) so tests can see what the system asked for; nothing is heard. */
  @Override
  public void setReverb(ReverbPreset preset, float wet) {
    this.reverb = preset;
    this.reverbWet = wet;
  }

  public ReverbPreset reverb() {
    return this.reverb;
  }

  public float reverbWet() {
    return this.reverbWet;
  }

  @Override
  public void update(double dt) {
    for (NullVoice v : this.voices) {
      v.advance(dt);
    }
  }

  @Override
  public void dispose() {
    for (NullVoice v : this.voices) {
      v.stop();
    }
  }

  record Clip(String name, float duration, int channels, boolean streaming) implements AudioClip {
    @Override
    public void dispose() {
    }
  }

  /** A voice that only keeps time; its last parameters can be read back. */
  public static final class NullVoice implements AudioVoice {
    private AudioClip clip;
    private boolean playing;
    private boolean paused;
    private boolean loop;
    private float time;
    private float gain;
    private float directHF = 1f;
    private float send;
    private float pitch = 1f;
    private boolean spatial;
    private final Vector3f position = new Vector3f();

    @Override
    public void start(AudioClip clip, boolean loop, float offsetSeconds) {
      this.clip = clip;
      this.loop = loop;
      this.time = clip.streaming() ? 0f : offsetSeconds;
      this.playing = true;
      this.paused = false;
    }

    @Override
    public void stop() {
      this.playing = false;
      this.clip = null;
    }

    @Override
    public void setPaused(boolean paused) {
      this.paused = paused;
    }

    @Override
    public void setGainAndPitch(float gain, float pitch) {
      this.gain = gain;
      this.pitch = pitch;
    }

    @Override
    public void setSpatial(boolean spatial) {
      this.spatial = spatial;
    }

    @Override
    public void setPosition(float x, float y, float z) {
      this.position.set(x, y, z);
    }

    @Override
    public void setVelocity(float x, float y, float z) {
    }

    @Override
    public boolean isPlaying() {
      return this.playing;
    }

    @Override
    public AudioClip clip() {
      return this.clip;
    }

    @Override
    public void setFilter(float directHF, float send) {
      this.directHF = directHF;
      this.send = send;
    }

    public float directHF() {
      return this.directHF;
    }

    public float send() {
      return this.send;
    }

    public float gain() {
      return this.gain;
    }

    public float pitch() {
      return this.pitch;
    }

    public boolean spatial() {
      return this.spatial;
    }

    public boolean paused() {
      return this.paused;
    }

    public Vector3f position() {
      return this.position;
    }

    void advance(double dt) {
      if (!this.playing || this.paused) {
        return;
      }
      this.time += (float) dt * this.pitch;
      final float d = this.clip.duration();
      if (this.time >= d) {
        if (this.loop && d > 0f) {
          this.time %= d;
        } else {
          this.playing = false;
        }
      }
    }
  }
}
