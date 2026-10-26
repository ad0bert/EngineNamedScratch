package engine.audio;

/**
 * Decoded sound: signed 16-bit samples, interleaved by channel (left, right, left, ... for stereo). Spatial sounds
 * should be mono; a stereo clip is played as it is, without positioning.
 */
public record AudioData(int sampleRate, int channels, short[] samples) {
  public AudioData {
    if (channels < 1 || channels > 2) {
      throw new IllegalArgumentException("only mono and stereo are supported, got " + channels + " channels");
    }
    if (sampleRate <= 0) {
      throw new IllegalArgumentException("sample rate must be positive");
    }
  }

  /** Samples per channel. */
  public int frames() {
    return this.samples.length / this.channels;
  }

  /** Length in seconds. */
  public float duration() {
    return (float) frames() / this.sampleRate;
  }

  /** Mono sound from samples in -1..1 (clipped). */
  public static AudioData mono(int sampleRate, float[] samples) {
    final short[] out = new short[samples.length];
    for (int i = 0; i < samples.length; i++) {
      out[i] = toShort(samples[i]);
    }
    return new AudioData(sampleRate, 1, out);
  }

  /** The same sound with both channels averaged; returns this if it is mono already. */
  public AudioData toMono() {
    if (this.channels == 1) {
      return this;
    }
    final short[] out = new short[frames()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (short) ((this.samples[2 * i] + this.samples[2 * i + 1]) / 2);
    }
    return new AudioData(this.sampleRate, 1, out);
  }

  static short toShort(float v) {
    final float c = Math.max(-1f, Math.min(1f, v));
    return (short) Math.round(c * Short.MAX_VALUE);
  }
}
