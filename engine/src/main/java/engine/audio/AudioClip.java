package engine.audio;

/** Opaque handle to a sound living in an {@link AudioBackend}: decoded samples, or a stream that decodes on demand. */
public interface AudioClip {
  /** The resource path or key it was created from; for logs and serialization. */
  String name();

  /** Length in seconds; for a stream whose length is unknown, {@link Float#POSITIVE_INFINITY}. */
  float duration();

  int channels();

  /** True for clips decoded while they play (see {@link PcmStream}); they cannot start in the middle. */
  boolean streaming();

  void dispose();
}
