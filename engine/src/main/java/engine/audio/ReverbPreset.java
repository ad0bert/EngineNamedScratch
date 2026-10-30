package engine.audio;

/**
 * Reverberation of a kind of space, with the parameters of OpenAL's EFX reverb (from the standard EFX presets):
 * density, diffusion, gain, high-frequency gain, decay time (seconds), high-frequency decay ratio, early reflections
 * gain and late reverberation gain.
 */
public enum ReverbPreset {
  ROOM(0.4287f, 1f, 0.3162f, 0.5929f, 0.4f, 0.83f, 0.1503f, 1.0629f),
  BATHROOM(0.1715f, 1f, 0.3162f, 0.2512f, 1.49f, 0.54f, 0.6531f, 3.2734f),
  LIVING_ROOM(0.9766f, 1f, 0.3162f, 0.001f, 0.5f, 0.1f, 0.2051f, 0.2805f),
  HALL(1f, 1f, 0.3162f, 0.5623f, 3.92f, 0.7f, 0.2427f, 0.9977f),
  CAVE(1f, 1f, 0.3162f, 1f, 2.91f, 1.3f, 0.5f, 0.7063f),
  HANGAR(1f, 1f, 0.3162f, 0.3162f, 10.05f, 0.23f, 0.5f, 1.256f),
  SEWER_PIPE(0.3071f, 0.8f, 0.3162f, 0.3162f, 2.81f, 0.14f, 1.6387f, 3.2471f),
  FOREST(0.3645f, 0.79f, 0.3162f, 0.0224f, 1.49f, 0.54f, 0.0525f, 0.7682f),
  UNDERWATER(0.3645f, 1f, 0.3162f, 0.01f, 1.49f, 0.1f, 0.5963f, 7.0795f);

  public final float density;
  public final float diffusion;
  public final float gain;
  public final float gainHF;
  public final float decayTime;
  public final float decayHFRatio;
  public final float reflectionsGain;
  public final float lateReverbGain;

  ReverbPreset(float density, float diffusion, float gain, float gainHF, float decayTime, float decayHFRatio,
      float reflectionsGain, float lateReverbGain) {
    this.density = density;
    this.diffusion = diffusion;
    this.gain = gain;
    this.gainHF = gainHF;
    this.decayTime = decayTime;
    this.decayHFRatio = decayHFRatio;
    this.reflectionsGain = reflectionsGain;
    this.lateReverbGain = lateReverbGain;
  }
}
