package engine.audio.al;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.jogamp.openal.ALConstants;

import engine.audio.AudioData;
import engine.audio.ReverbPreset;

/**
 * Drives OpenAL's EFX calls on a real device, checking that none raises an error. Skipped where OpenAL finds no
 * device (CI); run it with {@code ALSOFT_DRIVERS=null} to use OpenAL Soft's silent device.
 */
class AlEffectsTest {
  @Test
  void reverbAndFiltersRaiseNoErrors() {
    final var backend = AlAudioBackend.open(4);
    assumeTrue(backend != null, "no sound device");
    try {
      assumeTrue(backend.supportsEffects(), "no EFX");
      backend.error();
      final var clip = backend.createClip("tone", AudioData.mono(8000, new float[8000]));
      final var voice = backend.voice(0);
      voice.start(clip, true, 0f);
      voice.setSpatial(true);
      voice.setFilter(0.3f, 1f);
      voice.setFilter(0.3f, 1f); // unchanged: nothing sent
      voice.setFilter(1f, 0f);
      for (final var preset : ReverbPreset.values()) {
        backend.setReverb(preset, 0.7f);
      }
      backend.setReverb(null, 0f);
      backend.update(0.016);
      assertEquals(ALConstants.AL_NO_ERROR, backend.error());
      assertTrue(voice.isPlaying());
    } finally {
      backend.dispose();
    }
  }
}
