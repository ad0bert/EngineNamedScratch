package engine.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.messaging.IMessageReceiver;
import engine.messaging.MessageObject;
import engine.physics.CollisionEvent;
import engine.physics.Rigidbody;
import lombok.Getter;

/**
 * Drives the sound of a scene: places the listener, follows every {@link AudioSource}, plays one-shots and impact
 * sounds, applies the {@link AudioMixer} and hands the backend's voices to the sounds that matter most (see
 * {@link VoiceAllocator}). Runs once per fixed step after physics. The per-step work does not allocate.
 *
 * <p>
 * Registered by the engine for collision messages, so entities with an {@link ImpactSound} are heard when they hit
 * something.
 */
public class AudioSystem implements ISystem, IMessageReceiver {
  /** One-shots playing at the same time at most; a new one replaces the least important if all are busy. */
  public static final int MAX_ONE_SHOTS = 64;

  @Getter private final AudioBackend backend;
  @Getter private final AudioMixer mixer = new AudioMixer();
  /** Strength of the Doppler effect: 0 off, 1 physical. */
  public float dopplerFactor = 1f;
  /** Metres per second. */
  public float speedOfSound = 343.3f;

  private final Playback[] oneShots = new Playback[MAX_ONE_SHOTS];
  private final List<Playback> active = new ArrayList<>();
  private float[] scores = new float[64];
  private boolean[] holding = new boolean[64];
  private boolean[] selected = new boolean[64];
  private int[] order = new int[64];
  private final Playback[] voiceOwner;
  private final boolean[] voiceKept;

  private final Vector3f listenerPosition = new Vector3f();
  private final Vector3f listenerVelocity = new Vector3f();
  private final Vector3f listenerLast = new Vector3f();
  private boolean listenerHasLast;
  private final Vector3f forward = new Vector3f(0f, 0f, -1f);
  private final Vector3f up = new Vector3f(0f, 1f, 0f);
  private final Quaternionf rotation = new Quaternionf();
  private final Vector3f tmp = new Vector3f();
  private float lastDoppler = Float.NaN;
  private float lastSpeed = Float.NaN;

  /** Sounds that currently have a voice, and those that play without one (after the last update). */
  @Getter private int audibleCount;
  @Getter private int virtualCount;

  public AudioSystem(AudioBackend backend) {
    this.backend = backend;
    for (int i = 0; i < MAX_ONE_SHOTS; i++) {
      this.oneShots[i] = new Playback();
      this.oneShots[i].oneShot = true;
    }
    this.voiceOwner = new Playback[backend.voiceCount()];
    this.voiceKept = new boolean[backend.voiceCount()];
  }

  /** Plays a clip once at a world position on the effects bus. */
  public boolean play(AudioClip clip, Vector3f position) {
    return play(clip, position, 1f, 1f, AudioBus.EFFECTS, 0);
  }

  /**
   * Plays a clip once at a world position.
   *
   * @return false if it was dropped: every one-shot slot is busy with a more important or louder sound
   */
  public boolean play(AudioClip clip, Vector3f position, float volume, float pitch, AudioBus bus, int priority) {
    if (clip == null) {
      return false;
    }
    final var p = oneShotSlot(priority, volume * this.mixer.gain(bus) * attenuation(position, 1f, 50f, 1f,
        Rolloff.INVERSE));
    if (p == null) {
      return false;
    }
    setUp(p, clip, volume, pitch, bus, priority);
    p.spatial = true;
    p.position.set(position);
    p.velocity.zero();
    return true;
  }

  /** Plays a clip once without positioning (interface clicks, stingers). */
  public boolean play2D(AudioClip clip, float volume, AudioBus bus) {
    if (clip == null) {
      return false;
    }
    final var p = oneShotSlot(0, volume * this.mixer.gain(bus));
    if (p == null) {
      return false;
    }
    setUp(p, clip, volume, 1f, bus, 0);
    p.spatial = false;
    return true;
  }

  private void setUp(Playback p, AudioClip clip, float volume, float pitch, AudioBus bus, int priority) {
    p.clip = clip;
    p.volume = volume;
    p.pitch = pitch;
    p.bus = bus;
    p.priority = priority;
    p.loop = false;
    p.minDistance = 1f;
    p.maxDistance = 50f;
    p.rolloff = 1f;
    p.rolloffMode = Rolloff.INVERSE;
    p.start();
  }

  /** A free one-shot slot, or the least important busy one if the new sound outranks it, or null. */
  private Playback oneShotSlot(int priority, float gain) {
    Playback weakest = null;
    for (Playback p : this.oneShots) {
      if (!p.active()) {
        return p;
      }
      if (weakest == null || p.score < weakest.score) {
        weakest = p;
      }
    }
    if (weakest.score >= VoiceAllocator.score(priority, gain)) {
      return null;
    }
    releaseVoice(weakest);
    return weakest;
  }

  /** Stops every sound: sources stay stopped until played again. */
  public void stopAll() {
    for (Playback p : this.oneShots) {
      p.state = Playback.State.STOPPED;
    }
    for (int v = 0; v < this.voiceOwner.length; v++) {
      if (this.voiceOwner[v] != null) {
        this.voiceOwner[v].state = Playback.State.STOPPED;
        releaseVoice(this.voiceOwner[v]);
      }
    }
  }

  /**
   * Keeps running while the simulation is paused: the scene stands still but sounds go on (a pause menu's clicks and
   * music play). To silence the game while paused, {@link AudioMixer#setMuted mute} the mixer or turn buses down.
   */
  @Override
  public boolean runsWhilePaused() {
    return true;
  }

  @Override
  public void update(Scene scene, double dtD) {
    final float dt = (float) dtD;
    updateListener(scene, dt);
    if (this.dopplerFactor != this.lastDoppler || this.speedOfSound != this.lastSpeed) {
      this.backend.setDoppler(this.dopplerFactor, this.speedOfSound);
      this.lastDoppler = this.dopplerFactor;
      this.lastSpeed = this.speedOfSound;
    }

    // what wants to be heard
    this.active.clear();
    final var sources = scene.components(AudioSource.class);
    for (int i = 0; i < sources.size(); i++) {
      final var s = sources.get(i);
      if (!s.seen) {
        s.seen = true;
        if (s.playOnStart && s.clip != null) {
          s.play();
        }
      }
      s.sync();
      final var p = s.playback;
      if (p.active()) {
        follow(p, s.entity(), dt);
        this.active.add(p);
      }
    }
    for (Playback p : this.oneShots) {
      if (p.active()) {
        this.active.add(p);
      }
    }

    // sounds whose voice finished playing, or whose clock ran out while virtual
    final int n = this.active.size();
    ensureCapacity(n);
    for (int i = 0; i < n; i++) {
      final var p = this.active.get(i);
      if (p.voice >= 0 && !p.restart && p.state == Playback.State.PLAYING
          && !this.backend.voice(p.voice).isPlaying()) {
        p.state = Playback.State.STOPPED;
      } else if (p.voice < 0 && !p.advance(dt)) {
        p.state = Playback.State.STOPPED;
      } else if (p.voice >= 0) {
        p.advance(dt); // keeps the clock for the case it loses the voice
      }
      p.gain = p.active() ? gain(p) : 0f;
      final boolean wants = p.active() && (p.state == Playback.State.PLAYING || p.voice >= 0);
      p.score = wants ? VoiceAllocator.score(p.priority, p.gain) : VoiceAllocator.SILENT;
      this.scores[i] = p.score;
      this.holding[i] = p.voice >= 0;
    }
    VoiceAllocator.select(this.scores, this.holding, n, this.voiceOwner.length, this.order, this.selected);

    // keep the voices of selected holders, free all others (stolen, stopped, disabled or removed sources)
    Arrays.fill(this.voiceKept, false);
    for (int i = 0; i < n; i++) {
      final var p = this.active.get(i);
      if (this.selected[i] && p.voice >= 0) {
        this.voiceKept[p.voice] = true;
      }
    }
    for (int v = 0; v < this.voiceOwner.length; v++) {
      final var owner = this.voiceOwner[v];
      if (owner != null && !this.voiceKept[v]) {
        releaseVoice(owner);
        if (owner.oneShot && owner.state == Playback.State.PLAYING && owner.score != VoiceAllocator.SILENT) {
          owner.state = Playback.State.STOPPED; // a stolen one-shot is gone; a too quiet one waits
        }
      }
    }

    int audible = 0;
    int virtual = 0;
    for (int i = 0; i < n; i++) {
      final var p = this.active.get(i);
      if (!p.active()) {
        continue;
      }
      if (this.selected[i]) {
        if (p.voice < 0) {
          p.voice = freeVoice();
          this.voiceOwner[p.voice] = p;
          p.restart = true;
        }
        final var voice = this.backend.voice(p.voice);
        apply(voice, p);
        if (p.restart) {
          voice.start(p.clip, p.loop, p.time);
          p.restart = false;
        }
        voice.setPaused(p.state == Playback.State.PAUSED);
        audible++;
      } else {
        p.restart = false; // starts from its clock when it gets a voice
        virtual++;
      }
    }
    this.audibleCount = audible;
    this.virtualCount = virtual;
    this.backend.update(dtD);
  }

  private void ensureCapacity(int n) {
    if (this.scores.length < n) {
      final int size = Math.max(n, this.scores.length * 2);
      this.scores = new float[size];
      this.holding = new boolean[size];
      this.selected = new boolean[size];
      this.order = new int[size];
    }
  }

  private int freeVoice() {
    for (int v = 0; v < this.voiceOwner.length; v++) {
      if (this.voiceOwner[v] == null) {
        return v;
      }
    }
    throw new IllegalStateException("no free voice"); // the allocator never selects more sounds than voices
  }

  private void releaseVoice(Playback p) {
    if (p.voice >= 0) {
      this.backend.voice(p.voice).stop();
      this.voiceOwner[p.voice] = null;
      p.voice = -1;
    }
  }

  private void apply(AudioVoice voice, Playback p) {
    voice.setGainAndPitch(p.gain, p.pitch);
    voice.setSpatial(p.spatial);
    if (p.spatial) {
      voice.setPosition(p.position.x, p.position.y, p.position.z);
      voice.setVelocity(p.velocity.x, p.velocity.y, p.velocity.z);
    }
  }

  private float gain(Playback p) {
    float g = p.volume * this.mixer.gain(p.bus);
    if (p.spatial && g > 0f) {
      g *= attenuation(p.position, p.minDistance, p.maxDistance, p.rolloff, p.rolloffMode);
    }
    return g;
  }

  private float attenuation(Vector3f position, float min, float max, float rolloff, Rolloff mode) {
    return mode.gain(position.distance(this.listenerPosition), min, max, rolloff);
  }

  /** Copies the entity's world position and velocity into the playback. */
  private void follow(Playback p, Entity e, float dt) {
    e.transform().worldPosition(p.position);
    final var body = e.findOrNull(Rigidbody.class);
    if (body != null && !body.kinematic) {
      p.velocity.set(body.velocity);
    } else if (p.hasLastPosition && dt > 0f) {
      p.position.sub(p.lastPosition, p.velocity).div(dt);
    } else {
      p.velocity.zero();
    }
    p.lastPosition.set(p.position);
    p.hasLastPosition = true;
  }

  private void updateListener(Scene scene, float dt) {
    final var listeners = scene.components(AudioListener.class);
    Entity e = listeners.isEmpty() ? null : listeners.get(0).entity();
    if (e == null) {
      final var camera = scene.activeCamera();
      e = camera.isPresent() ? camera.get().entity() : null;
    }
    if (e == null) {
      return; // keep the last listener
    }
    e.transform().worldPosition(this.listenerPosition);
    e.transform().worldRotation(this.rotation);
    this.rotation.transform(this.forward.set(0f, 0f, -1f));
    this.rotation.transform(this.up.set(0f, 1f, 0f));
    final var body = e.findOrNull(Rigidbody.class);
    if (body != null && !body.kinematic) {
      this.listenerVelocity.set(body.velocity);
    } else if (this.listenerHasLast && dt > 0f) {
      this.listenerPosition.sub(this.listenerLast, this.listenerVelocity).div(dt);
    } else {
      this.listenerVelocity.zero();
    }
    this.listenerLast.set(this.listenerPosition);
    this.listenerHasLast = true;
    this.backend.setListener(this.listenerPosition, this.listenerVelocity, this.forward, this.up);
  }

  /** The listener's world position as of the last update. */
  public Vector3f listenerPosition(Vector3f dest) {
    return dest.set(this.listenerPosition);
  }

  /** Impact sounds: called for collision messages (the engine registers the system for them). */
  @Override
  public void receiveMessage(MessageObject message) {
    if (message.getPayload() instanceof CollisionEvent c && c.started()) {
      final var ia = c.a().findOrNull(ImpactSound.class);
      final var ib = c.b().findOrNull(ImpactSound.class);
      if (ia == null && ib == null) {
        return;
      }
      final float impulse = Math.max(ia != null ? ia.impulse() : 0f, ib != null ? ib.impulse() : 0f);
      // the faster-changing body is the one that was hit hardest; it makes the sound
      final var sound = ia == null ? ib : ib == null ? ia : ia.impulse() >= ib.impulse() ? ia : ib;
      impact(sound, impulse);
    }
  }

  private void impact(ImpactSound s, float impulse) {
    if (!s.isEnabled() || s.clip == null || s.cooldown > 0f) {
      return;
    }
    final float strength = ImpactSound.strength(impulse, s.minImpulse, s.maxImpulse);
    if (strength <= 0f) {
      return;
    }
    final var body = s.entity().findOrNull(Rigidbody.class);
    final float mass = body != null ? Math.max(0.01f, body.mass) : 1f;
    float pitch = s.massPitch > 0f ? (float) Math.pow(mass, -s.massPitch) : 1f;
    pitch *= 1f + (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * s.pitchVariation;
    if (play(s.clip, s.entity().transform().worldPosition(this.tmp), s.volume * strength, pitch, AudioBus.EFFECTS,
        s.priority)) {
      s.cooldown = s.minInterval;
    }
  }

  /** Stops everything and closes the backend. */
  public void dispose() {
    stopAll();
    this.backend.dispose();
  }
}
