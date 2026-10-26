package engine.animation;

/**
 * Shapes the progress between two keyframes: maps 0..1 to 0..1 (the elastic and back curves overshoot in between).
 * {@code IN} starts slowly, {@code OUT} ends slowly, {@code IN_OUT} does both.
 */
public enum Easing {
  LINEAR, QUAD_IN, QUAD_OUT, QUAD_IN_OUT, CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT, SINE_IN, SINE_OUT, SINE_IN_OUT,
  EXPO_IN, EXPO_OUT, BACK_IN, BACK_OUT, ELASTIC_OUT, BOUNCE_OUT;

  private static final float BACK = 1.70158f;

  public float apply(float t) {
    if (t <= 0f) {
      return 0f;
    }
    if (t >= 1f) {
      return 1f;
    }
    return switch (this) {
    case LINEAR -> t;
    case QUAD_IN -> t * t;
    case QUAD_OUT -> t * (2f - t);
    case QUAD_IN_OUT -> t < 0.5f ? 2f * t * t : 1f - 2f * (1f - t) * (1f - t);
    case CUBIC_IN -> t * t * t;
    case CUBIC_OUT -> 1f - (1f - t) * (1f - t) * (1f - t);
    case CUBIC_IN_OUT -> t < 0.5f ? 4f * t * t * t : 1f - 4f * (1f - t) * (1f - t) * (1f - t);
    case SINE_IN -> 1f - (float) Math.cos(t * Math.PI / 2);
    case SINE_OUT -> (float) Math.sin(t * Math.PI / 2);
    case SINE_IN_OUT -> 0.5f - 0.5f * (float) Math.cos(t * Math.PI);
    case EXPO_IN -> (float) Math.pow(2, 10 * (t - 1));
    case EXPO_OUT -> 1f - (float) Math.pow(2, -10 * t);
    case BACK_IN -> t * t * ((BACK + 1f) * t - BACK);
    case BACK_OUT -> {
      final float u = t - 1f;
      yield 1f + u * u * ((BACK + 1f) * u + BACK);
    }
    case ELASTIC_OUT -> (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
    case BOUNCE_OUT -> bounce(t);
    };
  }

  private static float bounce(float t) {
    final float n = 7.5625f;
    final float d = 2.75f;
    if (t < 1f / d) {
      return n * t * t;
    } else if (t < 2f / d) {
      final float u = t - 1.5f / d;
      return n * u * u + 0.75f;
    } else if (t < 2.5f / d) {
      final float u = t - 2.25f / d;
      return n * u * u + 0.9375f;
    }
    final float u = t - 2.625f / d;
    return n * u * u + 0.984375f;
  }
}
