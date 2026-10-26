package engine.audio;

/**
 * Decides which sounds get one of the backend's limited voices. Every playing sound has a score: its priority plus
 * how loud it currently is (0..1, after bus volumes and distance). The highest scores get voices; a sound that
 * already has one gets a small bonus, so two sounds of nearly equal loudness do not keep swapping. A sound that loses
 * its voice is "virtual": it keeps its place in time and is heard again when it outranks another one (looping sounds),
 * or ends (one-shots). Inaudible sounds never get a voice.
 */
public final class VoiceAllocator {
  /** Added to the score of a sound that holds a voice. */
  public static final float HOLD_BONUS = 0.05f;
  /** Scores below this mean "inaudible". */
  public static final float SILENT = -Float.MAX_VALUE;

  private VoiceAllocator() {
  }

  /** The ranking score of a sound: priority first, loudness second. */
  public static float score(int priority, float gain) {
    return gain < 1e-4f ? SILENT : priority + Math.min(gain, 0.999f);
  }

  /**
   * Marks in {@code selected} the (at most {@code voices}) sounds that should be heard. Does not allocate.
   *
   * @param scores  score of each sound ({@link #score}), {@link #SILENT} for inaudible ones
   * @param holding whether each sound has a voice now
   * @param order   scratch space of at least {@code count} ints; on return it holds the sound indices, best first
   * @return the number of selected sounds
   */
  public static int select(float[] scores, boolean[] holding, int count, int voices, int[] order,
      boolean[] selected) {
    for (int i = 0; i < count; i++) { // insertion sort by effective score, descending (stable)
      final float s = effective(scores, holding, i);
      int j = i;
      while (j > 0 && effective(scores, holding, order[j - 1]) < s) {
        order[j] = order[j - 1];
        j--;
      }
      order[j] = i;
    }
    int n = 0;
    for (int k = 0; k < count; k++) {
      final int i = order[k];
      final boolean take = n < voices && scores[i] != SILENT;
      selected[i] = take;
      if (take) {
        n++;
      }
    }
    return n;
  }

  private static float effective(float[] scores, boolean[] holding, int i) {
    return scores[i] == SILENT ? SILENT : scores[i] + (holding[i] ? HOLD_BONUS : 0f);
  }
}
