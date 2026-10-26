package game;

import java.util.Random;

import engine.audio.AudioData;

/** Synthesizes the demo's sounds in code (like {@link ProceduralTextures}), so the repository needs no audio files. */
final class ProceduralSounds {
  static final int RATE = 22050;

  private ProceduralSounds() {
  }

  /** A dull knock of a wooden crate: a falling low tone plus a short burst of muffled noise. */
  static AudioData thud() {
    final int n = (int) (RATE * 0.28f);
    final float[] out = new float[n];
    final var rnd = new Random(3);
    double phase = 0;
    float noise = 0f;
    for (int i = 0; i < n; i++) {
      final float t = (float) i / RATE;
      final double freq = 70 + 110 * Math.exp(-t * 30); // pitch drops quickly
      phase += 2 * Math.PI * freq / RATE;
      noise += 0.25f * ((rnd.nextFloat() * 2f - 1f) - noise); // one-pole low-pass
      final float body = (float) Math.sin(phase) * (float) Math.exp(-t * 18);
      final float knock = noise * (float) Math.exp(-t * 60) * 2.5f;
      out[i] = 0.8f * (body + knock) * attack(i, 40);
    }
    return AudioData.mono(RATE, out);
  }

  /** A short, bright tick for buttons. */
  static AudioData click() {
    final int n = (int) (RATE * 0.04f);
    final float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      final float t = (float) i / RATE;
      out[i] = 0.5f * (float) (Math.sin(2 * Math.PI * 1900 * t) * Math.exp(-t * 140)) * attack(i, 10);
    }
    return AudioData.mono(RATE, out);
  }

  /** Air rushing past: noise through a low-pass whose cutoff rises and falls, with a swell. */
  static AudioData whoosh() {
    final int n = (int) (RATE * 0.4f);
    final float[] out = new float[n];
    final var rnd = new Random(5);
    float lp = 0f;
    float lp2 = 0f;
    for (int i = 0; i < n; i++) {
      final float u = (float) i / n;
      final float shape = (float) Math.sin(Math.PI * u); // 0 -> 1 -> 0
      final float cutoff = 0.03f + 0.25f * shape;
      lp += cutoff * ((rnd.nextFloat() * 2f - 1f) - lp);
      lp2 += cutoff * (lp - lp2);
      out[i] = 1.6f * lp2 * shape * shape;
    }
    return AudioData.mono(RATE, out);
  }

  /**
   * A machine hum that loops seamlessly: every partial completes a whole number of cycles in the loop, and the slow
   * tremolo one cycle.
   */
  static AudioData hum() {
    final float seconds = 2f;
    final int n = (int) (RATE * seconds);
    final float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      final double t = (double) i / RATE;
      final double tremolo = 0.8 + 0.2 * Math.sin(2 * Math.PI * t / seconds);
      out[i] = (float) (tremolo * (0.35 * Math.sin(2 * Math.PI * 110 * t) + 0.18 * Math.sin(2 * Math.PI * 165 * t)
          + 0.08 * Math.sin(2 * Math.PI * 330.5 * t)));
    }
    return AudioData.mono(RATE, out);
  }

  /**
   * Soft wind, as a looping ambience bed: filtered noise whose last half second is cross-faded into its start, so the
   * loop point cannot be heard.
   */
  static AudioData wind() {
    final int n = RATE * 6;
    final int fade = RATE / 2;
    final float[] noise = new float[n + fade];
    final var rnd = new Random(9);
    float lp = 0f;
    float lp2 = 0f;
    for (int i = 0; i < noise.length; i++) {
      final double t = (double) i / RATE;
      final float gust = (float) (0.55 + 0.45 * Math.sin(2 * Math.PI * t / 6.0) * Math.sin(2 * Math.PI * t / 2.0));
      final float cutoff = 0.01f + 0.03f * gust;
      lp += cutoff * ((rnd.nextFloat() * 2f - 1f) - lp);
      lp2 += cutoff * (lp - lp2);
      noise[i] = lp2 * 4f * gust;
    }
    final float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      if (i < fade) {
        final float w = (float) i / fade;
        out[i] = noise[i] * w + noise[n + i] * (1f - w); // the end flows into the start
      } else {
        out[i] = noise[i];
      }
    }
    return AudioData.mono(RATE, out);
  }

  /** A linear fade-in over the first samples, so a sound does not start with a click. */
  private static float attack(int i, int samples) {
    return i < samples ? (float) i / samples : 1f;
  }
}
