package engine.audio.al;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.joml.Vector3f;

import com.jogamp.openal.AL;
import com.jogamp.openal.ALC;
import com.jogamp.openal.ALCcontext;
import com.jogamp.openal.ALCdevice;
import com.jogamp.openal.ALConstants;
import com.jogamp.openal.ALFactory;

import engine.audio.AudioBackend;
import engine.audio.AudioClip;
import engine.audio.AudioData;
import engine.audio.AudioVoice;
import engine.audio.PcmStream;
import lombok.extern.slf4j.Slf4j;

/**
 * The OpenAL implementation of {@link AudioBackend} (JOAL, which ships OpenAL Soft). Distance attenuation is done by
 * the engine, so OpenAL's distance model is off: it only pans the spatial voices and applies the Doppler shift.
 */
@Slf4j
public final class AlAudioBackend implements AudioBackend {
  final AL al;
  private final ALC alc;
  private final ALCdevice device;
  private final ALCcontext context;
  private final String name;
  private final List<AlVoice> voices = new ArrayList<>();
  private final List<AlClip> clips = new ArrayList<>();
  private final float[] orientation = new float[6];
  private boolean disposed;

  private AlAudioBackend(AL al, ALC alc, ALCdevice device, ALCcontext context, int maxVoices) {
    this.al = al;
    this.alc = alc;
    this.device = device;
    this.context = context;
    final var specifier = alc.alcGetString(device, ALC.ALC_DEVICE_SPECIFIER);
    this.name = specifier != null ? specifier : "OpenAL";
    al.alDistanceModel(ALConstants.AL_NONE);
    final int[] id = new int[1];
    for (int i = 0; i < maxVoices; i++) {
      al.alGetError();
      al.alGenSources(1, id, 0);
      if (al.alGetError() != ALConstants.AL_NO_ERROR) {
        break; // the device has fewer sources than asked for
      }
      this.voices.add(new AlVoice(this, id[0]));
    }
  }

  /**
   * Opens the default sound device, or returns null if there is none or OpenAL cannot be loaded (a server, a CI
   * machine, a container without sound).
   */
  public static AlAudioBackend open(int maxVoices) {
    try {
      final var al = ALFactory.getAL();
      final var alc = ALFactory.getALC();
      final var device = alc.alcOpenDevice(null);
      if (device == null) {
        log.info("No sound device; sound is off");
        return null;
      }
      final var context = alc.alcCreateContext(device, null);
      if (context == null || !alc.alcMakeContextCurrent(context)) {
        if (context != null) {
          alc.alcDestroyContext(context);
        }
        alc.alcCloseDevice(device);
        log.info("Could not create a sound context; sound is off");
        return null;
      }
      final var backend = new AlAudioBackend(al, alc, device, context, maxVoices);
      if (backend.voices.isEmpty()) {
        backend.dispose();
        log.info("The sound device has no voices; sound is off");
        return null;
      }
      log.info("Sound: {} with {} voices", backend.name, backend.voices.size());
      return backend;
    } catch (RuntimeException | LinkageError e) {
      log.info("OpenAL is not available ({}); sound is off", e.getMessage());
      return null;
    }
  }

  @Override
  public String name() {
    return this.name;
  }

  @Override
  public boolean hasDevice() {
    return true;
  }

  @Override
  public int voiceCount() {
    return this.voices.size();
  }

  @Override
  public AudioVoice voice(int index) {
    return this.voices.get(index);
  }

  @Override
  public AudioClip createClip(String name, AudioData data) {
    final var clip = AlClip.upload(this, name, data);
    this.clips.add(clip);
    return clip;
  }

  @Override
  public AudioClip createStreamingClip(String name, int channels, float duration, Supplier<PcmStream> open) {
    final var clip = new AlClip(this, name, 0, channels, duration, open);
    this.clips.add(clip);
    return clip;
  }

  @Override
  public void setListener(Vector3f position, Vector3f velocity, Vector3f forward, Vector3f up) {
    if (this.disposed) {
      return;
    }
    this.al.alListener3f(ALConstants.AL_POSITION, position.x, position.y, position.z);
    this.al.alListener3f(ALConstants.AL_VELOCITY, velocity.x, velocity.y, velocity.z);
    this.orientation[0] = forward.x;
    this.orientation[1] = forward.y;
    this.orientation[2] = forward.z;
    this.orientation[3] = up.x;
    this.orientation[4] = up.y;
    this.orientation[5] = up.z;
    this.al.alListenerfv(ALConstants.AL_ORIENTATION, this.orientation, 0);
  }

  @Override
  public void setDoppler(float factor, float speedOfSound) {
    if (!this.disposed) {
      this.al.alDopplerFactor(factor);
      this.al.alSpeedOfSound(speedOfSound);
    }
  }

  @Override
  public void update(double dt) {
    if (this.disposed) {
      return;
    }
    for (int i = 0; i < this.voices.size(); i++) {
      this.voices.get(i).update();
    }
  }

  boolean disposed() {
    return this.disposed;
  }

  /** Stops the voices playing a clip that is about to be deleted. */
  void release(AlClip clip) {
    for (int i = 0; i < this.voices.size(); i++) {
      if (this.voices.get(i).clip() == clip) {
        this.voices.get(i).stop();
      }
    }
    this.clips.remove(clip);
  }

  @Override
  public void dispose() {
    if (this.disposed) {
      return;
    }
    for (AlVoice v : this.voices) {
      v.stop();
    }
    for (AlClip c : List.copyOf(this.clips)) {
      c.dispose();
    }
    for (AlVoice v : this.voices) {
      v.dispose();
    }
    this.disposed = true;
    this.alc.alcMakeContextCurrent(null);
    this.alc.alcDestroyContext(this.context);
    this.alc.alcCloseDevice(this.device);
    log.info("Sound device closed");
  }
}
