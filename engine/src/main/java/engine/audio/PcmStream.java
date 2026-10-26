package engine.audio;

/**
 * A sound decoded piece by piece, for music and other long clips that would take too much memory decoded. A
 * streaming {@link AudioClip} opens a new stream for every voice that plays it.
 */
public interface PcmStream extends AutoCloseable {
  int sampleRate();

  int channels();

  /**
   * Decodes up to {@code buffer.length} samples (interleaved by channel, a whole number of frames) into the buffer.
   *
   * @return the number of samples written, or -1 at the end of the stream
   */
  int read(short[] buffer);

  @Override
  void close();
}
