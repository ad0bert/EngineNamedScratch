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
import com.jogamp.openal.ALExt;
import com.jogamp.openal.ALExtConstants;
import com.jogamp.openal.ALFactory;

import engine.audio.AudioBackend;
import engine.audio.AudioClip;
import engine.audio.AudioData;
import engine.audio.AudioVoice;
import engine.audio.PcmStream;
import engine.audio.ReverbPreset;
import lombok.extern.slf4j.Slf4j;

/**
 * The OpenAL implementation of {@link AudioBackend} (JOAL, which ships OpenAL Soft). Distance attenuation is done by
 * the engine, so OpenAL's distance model is off: it only pans the spatial voices and applies the Doppler shift.
 *
 * <p>
 * With the EFX extension (OpenAL Soft has it) there is one reverb in an auxiliary effect slot that every voice can
 * send to, and each voice has a low-pass filter on its direct path (occlusion, muffling zones).
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
  /** EFX, or null without it. */
  ALExt efx;
  int effectSlot;
  private int reverbEffect;

  private AlAudioBackend(AL al, ALC alc, ALCdevice device, ALCcontext context, int maxVoices) {
    this.al = al;
    this.alc = alc;
    this.device = device;
    this.context = context;
    final var specifier = alc.alcGetString(device, ALC.ALC_DEVICE_SPECIFIER);
    this.name = specifier != null ? specifier : "OpenAL";
    al.alDistanceModel(ALConstants.AL_NONE);
    final int[] id = new int[1];
    if (alc.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
      try {
        final var ext = ALFactory.getALExt();
        al.alGetError();
        ext.alGenAuxiliaryEffectSlots(1, id, 0);
        this.effectSlot = id[0];
        ext.alGenEffects(1, id, 0);
        this.reverbEffect = id[0];
        ext.alEffecti(this.reverbEffect, ALExtConstants.AL_EFFECT_TYPE, ALExtConstants.AL_EFFECT_REVERB);
        if (al.alGetError() == ALConstants.AL_NO_ERROR) {
          this.efx = ext;
        } else {
          log.info("EFX is present but its reverb could not be set up; no sound effects");
        }
      } catch (RuntimeException | LinkageError e) {
        log.info("EFX is not usable ({}); no sound effects", e.getMessage());
      }
    }
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
      log.info("Sound: {} with {} voices{}", backend.name, backend.voices.size(),
          backend.efx != null ? ", reverb and filters (EFX)" : "");
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
  public boolean supportsEffects() {
    return this.efx != null;
  }

  @Override
  public void setReverb(ReverbPreset preset, float wet) {
    if (this.efx == null || this.disposed) {
      return;
    }
    final var ext = this.efx;
    if (preset == null || wet <= 0f) {
      ext.alAuxiliaryEffectSloti(this.effectSlot, ALExtConstants.AL_EFFECTSLOT_EFFECT, ALExtConstants.AL_EFFECT_NULL);
      return;
    }
    final int e = this.reverbEffect;
    ext.alEffectf(e, ALExtConstants.AL_REVERB_DENSITY, preset.density);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_DIFFUSION, preset.diffusion);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_GAIN, preset.gain);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_GAINHF, preset.gainHF);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_DECAY_TIME, preset.decayTime);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_DECAY_HFRATIO, preset.decayHFRatio);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_REFLECTIONS_GAIN, preset.reflectionsGain);
    ext.alEffectf(e, ALExtConstants.AL_REVERB_LATE_REVERB_GAIN, preset.lateReverbGain);
    // a slot copies the effect when it is attached: attach it again after changing it
    ext.alAuxiliaryEffectSloti(this.effectSlot, ALExtConstants.AL_EFFECTSLOT_EFFECT, e);
    ext.alAuxiliaryEffectSlotf(this.effectSlot, ALExtConstants.AL_EFFECTSLOT_GAIN, Math.min(1f, wet));
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

  /** The last OpenAL error (and clears it): for tests. */
  int error() {
    return this.al.alGetError();
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
    if (this.efx != null) {
      this.efx.alDeleteAuxiliaryEffectSlots(1, new int[] { this.effectSlot }, 0);
      this.efx.alDeleteEffects(1, new int[] { this.reverbEffect }, 0);
    }
    this.disposed = true;
    this.alc.alcMakeContextCurrent(null);
    this.alc.alcDestroyContext(this.context);
    this.alc.alcCloseDevice(this.device);
    log.info("Sound device closed");
  }
}
