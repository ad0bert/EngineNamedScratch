package engine.audio.al;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

import com.jogamp.openal.ALConstants;

import engine.audio.AudioClip;
import engine.audio.AudioData;
import engine.audio.PcmStream;
import lombok.Getter;

/** An OpenAL buffer holding a whole clip, or (with {@code buffer == 0}) a stream opener. */
final class AlClip implements AudioClip {
  private final AlAudioBackend backend;
  @Getter private final String name;
  final int buffer;
  @Getter private final int channels;
  @Getter private final float duration;
  final Supplier<PcmStream> open;
  private boolean disposed;

  AlClip(AlAudioBackend backend, String name, int buffer, int channels, float duration, Supplier<PcmStream> open) {
    this.backend = backend;
    this.name = name;
    this.buffer = buffer;
    this.channels = channels;
    this.duration = duration;
    this.open = open;
  }

  static AlClip upload(AlAudioBackend backend, String name, AudioData data) {
    final int[] id = new int[1];
    backend.al.alGenBuffers(1, id, 0);
    fill(backend, id[0], data.samples(), data.samples().length, data.channels(), data.sampleRate());
    return new AlClip(backend, name, id[0], data.channels(), data.duration(), null);
  }

  /** Uploads {@code count} interleaved samples into an OpenAL buffer. */
  static void fill(AlAudioBackend backend, int buffer, short[] samples, int count, int channels, int rate) {
    final var bytes = ByteBuffer.allocateDirect(Math.max(2, count * 2)).order(ByteOrder.nativeOrder());
    bytes.asShortBuffer().put(samples, 0, count);
    backend.al.alBufferData(buffer, channels == 1 ? ALConstants.AL_FORMAT_MONO16 : ALConstants.AL_FORMAT_STEREO16,
        bytes, count * 2, rate);
  }

  @Override
  public boolean streaming() {
    return this.open != null;
  }

  @Override
  public void dispose() {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    if (!this.backend.disposed()) {
      this.backend.release(this);
      if (this.buffer != 0) {
        this.backend.al.alDeleteBuffers(1, new int[] { this.buffer }, 0);
      }
    }
  }
}
