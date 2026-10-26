package engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.CollisionEvent;
import engine.physics.Rigidbody;

class AudioTest {
  private static final double DT = 1.0 / 60.0;

  @Test
  void rolloffCurves() {
    assertEquals(1f, Rolloff.INVERSE.gain(0.5f, 1f, 50f, 1f));
    assertEquals(0.5f, Rolloff.INVERSE.gain(2f, 1f, 50f, 1f), 1e-6f); // twice the distance: half the amplitude
    assertEquals(0.25f, Rolloff.INVERSE.gain(4f, 1f, 50f, 1f), 1e-6f);
    assertEquals(Rolloff.INVERSE.gain(50f, 1f, 50f, 1f), Rolloff.INVERSE.gain(500f, 1f, 50f, 1f)); // clamped
    assertEquals(1f, Rolloff.INVERSE.gain(10f, 1f, 50f, 0f)); // no rolloff
    assertEquals(0.5f, Rolloff.LINEAR.gain(5.5f, 1f, 10f, 1f), 1e-6f);
    assertEquals(0f, Rolloff.LINEAR.gain(20f, 1f, 10f, 1f));
    assertEquals(0.25f, Rolloff.EXPONENTIAL.gain(4f, 2f, 50f, 2f), 1e-6f);
  }

  @Test
  void mixerMultipliesBusAndMaster() {
    final var m = new AudioMixer();
    m.setVolume(AudioBus.MASTER, 0.5f);
    m.setVolume(AudioBus.MUSIC, 0.4f);
    assertEquals(0.2f, m.gain(AudioBus.MUSIC), 1e-6f);
    assertEquals(0.5f, m.gain(AudioBus.EFFECTS), 1e-6f);
    assertEquals(0.5f, m.gain(AudioBus.MASTER), 1e-6f);
    m.setMuted(true);
    assertEquals(0f, m.gain(AudioBus.UI));
    assertEquals(0.5f, AudioMixer.fromDecibels(-6.0206f), 1e-4f);
    assertEquals(-20f, AudioMixer.toDecibels(0.1f), 1e-4f);
  }

  @Test
  void allocatorPrefersPriorityThenLoudnessAndKeepsHolders() {
    final float[] scores = { VoiceAllocator.score(0, 0.2f), VoiceAllocator.score(0, 0.9f),
        VoiceAllocator.score(5, 0.01f), VoiceAllocator.score(0, 0f) };
    final boolean[] holding = new boolean[4];
    final boolean[] selected = new boolean[4];
    final int[] order = new int[4];
    assertEquals(2, VoiceAllocator.select(scores, holding, 4, 2, order, selected));
    assertTrue(selected[2]); // high priority wins although it is quiet
    assertTrue(selected[1]); // then the loudest
    assertFalse(selected[0]);
    assertFalse(selected[3]); // silent sounds never get a voice
    assertEquals(2, order[0]);

    // nearly as loud as a holder: the holder keeps its voice
    final float[] close = { VoiceAllocator.score(0, 0.5f), VoiceAllocator.score(0, 0.52f) };
    final boolean[] held = { true, false };
    VoiceAllocator.select(close, held, 2, 1, new int[2], selected);
    assertTrue(selected[0]);
    assertFalse(selected[1]);
  }

  private static Scene sceneWithListener() {
    final var scene = new Scene();
    final var ears = new Entity("ears");
    ears.add(new AudioListener());
    scene.add(ears);
    return scene;
  }

  private static Entity source(Scene scene, AudioClip clip, float x, boolean loop) {
    final var e = new Entity();
    e.transform().position().set(x, 0f, 0f);
    final var s = e.add(new AudioSource(clip));
    s.loop = loop;
    scene.add(e);
    scene.flush();
    return e;
  }

  @Test
  void sourcesAreAttenuatedMixedAndEnd() {
    final var backend = new NullAudioBackend(4);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var clip = backend.createClip("beep", AudioData.mono(1000, new float[500])); // 0.5 s
    final var near = source(scene, clip, 0.5f, false).get(AudioSource.class);
    final var far = source(scene, clip, 4f, false).get(AudioSource.class);
    final var music = source(scene, clip, 100f, true).get(AudioSource.class);
    music.spatial = false;
    music.bus = AudioBus.MUSIC;
    audio.mixer().setVolume(AudioBus.MUSIC, 0.5f);
    near.play();
    far.play();
    music.play();
    audio.update(scene, DT);
    assertEquals(3, audio.audibleCount());
    final float[] gains = new float[3];
    for (int v = 0; v < 3; v++) {
      final var voice = backend.voice(v);
      gains[v] = voice.gain();
      if (!voice.spatial()) {
        assertEquals(0.5f, voice.gain(), 1e-6f); // 2D: bus volume only
      }
    }
    java.util.Arrays.sort(gains);
    assertEquals(0.25f, gains[0], 1e-6f); // 4 m away with min distance 1
    assertEquals(1f, gains[2], 1e-6f);

    for (int i = 0; i < 40; i++) {
      audio.update(scene, DT);
    }
    assertFalse(near.isPlaying()); // 0.5 s clip has ended
    assertTrue(music.isPlaying()); // looping
    assertEquals(1, audio.audibleCount());
  }

  @Test
  void quietSoundsLoseTheirVoiceAndComeBack() {
    final var backend = new NullAudioBackend(1);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var loopClip = backend.createClip("hum", AudioData.mono(1000, new float[2000])); // 2 s
    final var hum = source(scene, loopClip, 10f, true).get(AudioSource.class);
    hum.play();
    audio.update(scene, DT);
    assertTrue(hum.isAudible());

    final var shot = backend.createClip("bang", AudioData.mono(1000, new float[100])); // 0.1 s
    assertTrue(audio.play(shot, new Vector3f(0.2f, 0f, 0f)));
    audio.update(scene, DT);
    assertFalse(hum.isAudible()); // the louder one-shot stole the only voice
    assertTrue(hum.isPlaying()); // but it keeps time silently
    assertEquals(1, audio.virtualCount());
    assertEquals("bang", backend.voice(0).clip().name());

    for (int i = 0; i < 12; i++) {
      audio.update(scene, DT);
    }
    assertTrue(hum.isAudible()); // the one-shot ended: the hum is back, at its clock position
    assertTrue(hum.time() > 0.2f);
  }

  @Test
  void pauseStopAndDisable() {
    final var backend = new NullAudioBackend(2);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var clip = backend.createClip("c", AudioData.mono(1000, new float[1000]));
    final var e = source(scene, clip, 1f, true);
    final var s = e.get(AudioSource.class);
    s.playOnStart = true;
    audio.update(scene, DT);
    assertTrue(s.isPlaying());
    s.pause();
    audio.update(scene, DT);
    assertTrue(backend.voice(0).paused());
    final float t = s.time();
    audio.update(scene, DT);
    assertEquals(t, s.time());
    s.resume();
    audio.update(scene, DT);
    assertFalse(backend.voice(0).paused());
    s.setEnabled(false); // disabled: the voice is freed
    audio.update(scene, DT);
    assertFalse(backend.voice(0).isPlaying());
    s.setEnabled(true);
    audio.update(scene, DT);
    assertTrue(s.isAudible());
    s.stop();
    audio.update(scene, DT);
    assertFalse(s.isPlaying());
    assertEquals(0, audio.audibleCount());
  }

  @Test
  void voiceLimitDropsTheQuietestOneShots() {
    final var backend = new NullAudioBackend(2);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var clip = backend.createClip("c", AudioData.mono(1000, new float[1000]));
    audio.update(scene, DT);
    audio.play(clip, new Vector3f(30f, 0f, 0f));
    audio.play(clip, new Vector3f(1f, 0f, 0f));
    audio.play(clip, new Vector3f(2f, 0f, 0f));
    audio.update(scene, DT);
    assertEquals(2, audio.audibleCount());
    for (int v = 0; v < 2; v++) {
      assertTrue(backend.voice(v).position().x <= 2f); // the far one is not heard
    }
  }

  @Test
  void dopplerVelocityComesFromTheRigidbody() {
    final var backend = new NullAudioBackend(1);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var clip = backend.createClip("c", AudioData.mono(1000, new float[1000]));
    final var e = source(scene, clip, 3f, true);
    e.add(new Rigidbody()).velocity.set(5f, 0f, 0f);
    scene.flush();
    e.get(AudioSource.class).play();
    audio.update(scene, DT);
    assertEquals(5f, e.get(AudioSource.class).playback.velocity.x, 1e-6f);
  }

  @Test
  void impactSoundsScaleWithTheImpulse() {
    assertEquals(0f, ImpactSound.strength(0.5f, 1f, 5f));
    assertEquals(1f, ImpactSound.strength(9f, 1f, 5f));
    assertEquals(0.5f, ImpactSound.strength(2f, 1f, 5f), 1e-6f);

    final var backend = new NullAudioBackend(4);
    final var audio = new AudioSystem(backend);
    final var scene = sceneWithListener();
    final var clip = backend.createClip("thud", AudioData.mono(1000, new float[300]));
    final var crate = new Entity("crate");
    final var body = crate.add(new Rigidbody());
    final var impact = crate.add(new ImpactSound(clip));
    impact.pitchVariation = 0f;
    impact.massPitch = 0f;
    final var ground = new Entity("ground");
    scene.add(crate);
    scene.add(ground);
    scene.flush();

    body.velocity.set(0f, -6f, 0f);
    impact.update(DT); // the velocity before the step
    body.velocity.set(0f, 0.5f, 0f); // the step stopped it
    audio.receiveMessage(MessageObject.builder().queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(crate, ground, true)).build());
    audio.update(scene, DT);
    assertEquals(1, audio.audibleCount());
    final float loud = backend.voice(0).gain();
    assertTrue(loud > 0.5f && loud <= 1f, "gain " + loud);

    // a gentle touch makes no sound
    for (int i = 0; i < 30; i++) {
      impact.update(DT);
      audio.update(scene, DT);
    }
    body.velocity.set(0f, -0.3f, 0f);
    impact.update(DT);
    body.velocity.zero();
    audio.receiveMessage(MessageObject.builder().queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(crate, ground, true)).build());
    audio.update(scene, DT);
    assertEquals(0, audio.audibleCount());
  }

  @Test
  void monoConversion() {
    final var stereo = new AudioData(8000, 2, new short[] { 100, 300, -50, 50 });
    final var mono = stereo.toMono();
    assertEquals(1, mono.channels());
    assertEquals(2, mono.frames());
    assertEquals(200, mono.samples()[0]);
    assertEquals(0, mono.samples()[1]);
    assertEquals(0.00025f, mono.duration(), 1e-7f);
    assertNotNull(AudioData.mono(8000, new float[] { 2f }).samples());
    assertEquals(Short.MAX_VALUE, AudioData.mono(8000, new float[] { 2f }).samples()[0]); // clipped
  }
}
