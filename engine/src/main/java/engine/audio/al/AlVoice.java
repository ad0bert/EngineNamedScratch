package engine.audio.al;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.jogamp.openal.ALConstants;

import engine.audio.AudioClip;
import engine.audio.AudioVoice;
import engine.audio.PcmStream;

/**
 * An OpenAL source. A whole clip is attached as one buffer; a streaming clip is decoded into a ring of small buffers
 * that are refilled as they finish (about three quarters of a second ahead), so a long piece of music needs little
 * memory. Parameters are only sent to OpenAL when they change.
 */
final class AlVoice implements AudioVoice {
  private static final int STREAM_BUFFERS = 4;
  private static final int STREAM_FRAMES = 8192;

  private final AlAudioBackend backend;
  private final int source;
  private AlClip clip;
  private boolean loop;
  private boolean paused;
  private int spatial = -1;
  private float gain = Float.NaN;
  private float pitch = Float.NaN;
  private final int[] one = new int[1];

  // streaming
  private PcmStream stream;
  private boolean streamEnded;
  private int[] buffers;
  private short[] pcm;
  private ByteBuffer bytes;

  AlVoice(AlAudioBackend backend, int source) {
    this.backend = backend;
    this.source = source;
  }

  @Override
  public void start(AudioClip clip, boolean loop, float offsetSeconds) {
    final var al = this.backend.al;
    stop();
    final var c = (AlClip) clip;
    this.clip = c;
    this.loop = loop;
    this.paused = false;
    if (!c.streaming()) {
      al.alSourcei(this.source, ALConstants.AL_BUFFER, c.buffer);
      al.alSourcei(this.source, ALConstants.AL_LOOPING, loop ? ALConstants.AL_TRUE : ALConstants.AL_FALSE);
      if (offsetSeconds > 0f && c.duration() > 0f) {
        al.alSourcef(this.source, ALConstants.AL_SEC_OFFSET, offsetSeconds % c.duration());
      }
      al.alSourcePlay(this.source);
      return;
    }
    al.alSourcei(this.source, ALConstants.AL_LOOPING, ALConstants.AL_FALSE);
    if (this.buffers == null) {
      this.buffers = new int[STREAM_BUFFERS];
      al.alGenBuffers(STREAM_BUFFERS, this.buffers, 0);
    }
    this.stream = c.open.get();
    this.streamEnded = false;
    final int samples = STREAM_FRAMES * this.stream.channels();
    if (this.pcm == null || this.pcm.length < samples) {
      this.pcm = new short[samples];
      this.bytes = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder());
    }
    for (int b : this.buffers) {
      if (!refill(b)) {
        break;
      }
    }
    al.alSourcePlay(this.source);
  }

  /** Decodes the next piece into {@code buffer} and queues it; false at the end of a non-looping stream. */
  private boolean refill(int buffer) {
    if (this.streamEnded) {
      return false;
    }
    int n = this.stream.read(this.pcm);
    if (n < 0 && this.loop) {
      this.stream.close();
      this.stream = this.clip.open.get(); // start over
      n = this.stream.read(this.pcm);
    }
    if (n <= 0) {
      this.streamEnded = true;
      return false;
    }
    final var al = this.backend.al;
    this.bytes.clear();
    this.bytes.asShortBuffer().put(this.pcm, 0, n);
    al.alBufferData(buffer, this.stream.channels() == 1 ? ALConstants.AL_FORMAT_MONO16
        : ALConstants.AL_FORMAT_STEREO16, this.bytes, n * 2, this.stream.sampleRate());
    this.one[0] = buffer;
    al.alSourceQueueBuffers(this.source, 1, this.one, 0);
    return true;
  }

  /** Feeds a streaming voice; restarts it if it ran dry during a long frame. */
  void update() {
    if (this.stream == null || this.paused) {
      return;
    }
    final var al = this.backend.al;
    al.alGetSourcei(this.source, ALConstants.AL_BUFFERS_PROCESSED, this.one, 0);
    for (int processed = this.one[0]; processed > 0; processed--) {
      al.alSourceUnqueueBuffers(this.source, 1, this.one, 0);
      refill(this.one[0]);
    }
    if (!this.streamEnded && state() == ALConstants.AL_STOPPED) {
      al.alGetSourcei(this.source, ALConstants.AL_BUFFERS_QUEUED, this.one, 0);
      if (this.one[0] > 0) {
        al.alSourcePlay(this.source); // starved: carry on
      }
    }
  }

  private int state() {
    this.backend.al.alGetSourcei(this.source, ALConstants.AL_SOURCE_STATE, this.one, 0);
    return this.one[0];
  }

  @Override
  public void stop() {
    if (this.clip == null) {
      return;
    }
    final var al = this.backend.al;
    al.alSourceStop(this.source);
    al.alSourcei(this.source, ALConstants.AL_BUFFER, 0); // also empties a stream's queue
    if (this.stream != null) {
      this.stream.close();
      this.stream = null;
    }
    this.clip = null;
    this.paused = false;
  }

  @Override
  public void setPaused(boolean paused) {
    if (paused == this.paused || this.clip == null) {
      return;
    }
    this.paused = paused;
    if (paused) {
      this.backend.al.alSourcePause(this.source);
    } else {
      this.backend.al.alSourcePlay(this.source);
    }
  }

  @Override
  public void setGainAndPitch(float gain, float pitch) {
    if (gain != this.gain) {
      this.backend.al.alSourcef(this.source, ALConstants.AL_GAIN, gain);
      this.gain = gain;
    }
    final float p = Math.max(0.01f, pitch);
    if (p != this.pitch) {
      this.backend.al.alSourcef(this.source, ALConstants.AL_PITCH, p);
      this.pitch = p;
    }
  }

  @Override
  public void setSpatial(boolean spatial) {
    final int s = spatial ? 1 : 0;
    if (s != this.spatial) {
      this.spatial = s;
      final var al = this.backend.al;
      al.alSourcei(this.source, ALConstants.AL_SOURCE_RELATIVE, spatial ? ALConstants.AL_FALSE : ALConstants.AL_TRUE);
      if (!spatial) { // at the listener, not moving: no panning, no Doppler
        al.alSource3f(this.source, ALConstants.AL_POSITION, 0f, 0f, 0f);
        al.alSource3f(this.source, ALConstants.AL_VELOCITY, 0f, 0f, 0f);
      }
    }
  }

  @Override
  public void setPosition(float x, float y, float z) {
    this.backend.al.alSource3f(this.source, ALConstants.AL_POSITION, x, y, z);
  }

  @Override
  public void setVelocity(float x, float y, float z) {
    this.backend.al.alSource3f(this.source, ALConstants.AL_VELOCITY, x, y, z);
  }

  @Override
  public boolean isPlaying() {
    if (this.clip == null) {
      return false;
    }
    if (this.paused) {
      return true;
    }
    final int s = state();
    if (this.stream != null) {
      return !this.streamEnded || s == ALConstants.AL_PLAYING;
    }
    return s == ALConstants.AL_PLAYING || s == ALConstants.AL_PAUSED;
  }

  @Override
  public AudioClip clip() {
    return this.clip;
  }

  void dispose() {
    stop();
    this.backend.al.alDeleteSources(1, new int[] { this.source }, 0);
    if (this.buffers != null) {
      this.backend.al.alDeleteBuffers(STREAM_BUFFERS, this.buffers, 0);
    }
  }
}
