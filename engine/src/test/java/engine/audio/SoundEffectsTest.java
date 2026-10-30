package engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;

class SoundEffectsTest {
  private static final double DT = 1.0 / 60.0;

  private final NullAudioBackend backend = new NullAudioBackend(4);
  private final AudioSystem audio = new AudioSystem(this.backend);
  private final Scene scene = new Scene();
  private final Entity ears = new Entity("ears");
  private final AudioClip clip = this.backend.createClip("tone", AudioData.mono(8000, new float[8000 * 10]));

  SoundEffectsTest() {
    this.ears.add(new AudioListener());
    this.scene.add(this.ears);
  }

  private AudioSource source(float x, boolean spatial) {
    final var e = new Entity("source");
    e.transform().position().set(x, 0f, 0f);
    final var s = e.add(new AudioSource(this.clip));
    s.loop = true;
    s.spatial = spatial;
    s.maxDistance = 100f;
    s.play();
    this.scene.add(e);
    this.scene.flush();
    return s;
  }

  private ReverbZone zone(ReverbPreset preset, float x, float size) {
    final var e = new Entity("zone");
    e.transform().position().set(x, 0f, 0f);
    final var z = e.add(new ReverbZone(preset, size, size, size));
    this.scene.add(e);
    this.scene.flush();
    return z;
  }

  private void step(int n) {
    for (int i = 0; i < n; i++) {
      this.audio.update(this.scene, DT);
    }
  }

  private NullAudioBackend.NullVoice voiceOf(AudioSource s) {
    for (int v = 0; v < this.backend.voiceCount(); v++) {
      if (this.backend.voice(v).isPlaying() && this.backend.voice(v).position().x == s.entity().transform()
          .position().x) {
        return this.backend.voice(v);
      }
    }
    throw new AssertionError("no voice");
  }

  @Test
  void theZoneTheListenerIsDeepestInSetsTheReverb() {
    final var hall = zone(ReverbPreset.HALL, 0f, 20f);
    hall.fade = 2f;
    hall.wet = 0.8f;
    zone(ReverbPreset.BATHROOM, 9f, 4f); // a small room at the hall's edge
    step(1);
    assertSame(ReverbPreset.HALL, this.audio.reverb());
    assertEquals(0.8f, this.backend.reverbWet(), 1e-5f);

    this.ears.transform().position().set(9.5f, 0f, 0f); // 0.5 m inside the hall's face, 1.5 m into the bathroom
    step(1);
    assertSame(ReverbPreset.BATHROOM, this.audio.reverb());
    assertEquals(1f, this.backend.reverbWet(), 1e-5f);

    this.ears.transform().position().set(50f, 0f, 0f);
    step(1);
    assertNull(this.backend.reverb(), "outside every zone: dry");
  }

  @Test
  void positionedSoundsSendToTheReverbAndMuffledZonesFilterEverything() {
    final var steps = source(3f, true);
    final var music = source(0f, false);
    final var water = zone(ReverbPreset.UNDERWATER, 0f, 10f);
    water.lowPass = 0.1f;
    water.fade = 0f;
    step(2);
    assertEquals(1f, voiceOf(steps).send());
    assertEquals(0f, voiceOf(music).send(), "music stays dry");
    assertEquals(0.1f, voiceOf(steps).directHF(), 1e-5f);
    assertEquals(0.1f, voiceOf(music).directHF(), 1e-5f, "under water even the music is muffled");
    assertEquals(0.1f, this.audio.listenerTreble(), 1e-5f);
  }

  @Test
  void soundsBehindSomethingAreMuffledAndQuieter() {
    final var behind = source(10f, true);
    final var inFront = source(-10f, true);
    final boolean[] wall = { true };
    // a wall between the listener and anything at x > 5
    this.audio.setOcclusionTest((from, to, listener, entity) -> {
      assertSame(this.ears, listener);
      return wall[0] && to.x > 5f;
    });
    step(1);
    assertEquals(this.audio.occludedTreble, voiceOf(behind).directHF(), 1e-5f, "a sound starting behind it");
    assertEquals(1f, voiceOf(inFront).directHF(), 1e-5f);
    assertEquals(voiceOf(inFront).gain() * this.audio.occludedGain, voiceOf(behind).gain(), 1e-5f);

    wall[0] = false; // the door opens: the sound clears over the occlusion time
    step(this.audio.occlusionInterval);
    final float halfway = voiceOf(behind).directHF();
    assertTrue(halfway > this.audio.occludedTreble && halfway < 1f, "fading: " + halfway);
    step((int) (this.audio.occlusionTime / DT) + this.audio.occlusionInterval);
    assertEquals(1f, voiceOf(behind).directHF(), 1e-5f);
  }

  @Test
  void occlusionCanBeSwitchedOffPerSource() {
    final var s = source(10f, true);
    s.occlusion = false;
    this.audio.setOcclusionTest((from, to, listener, entity) -> true);
    step(2);
    assertEquals(1f, voiceOf(s).directHF(), 1e-5f);
  }
}
