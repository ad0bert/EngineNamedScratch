package engine.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures where frame time goes, per named section ("physics", "render"...), over the last {@link #HISTORY}
 * frames. The engine times each system's fixed steps, the game's update, rendering and the whole frame; games add
 * their own sections with {@link #begin}/{@link #end} (nested sections are fine; each counts its own whole time) or
 * {@link #add}. A section measured several times in a frame (a system running two fixed steps) sums up.
 *
 * <p>
 * Single-threaded (the GL thread); recording does not allocate once a section exists.
 */
public final class Profiler {
  /** Frames of history kept. */
  public static final int HISTORY = 240;
  private static final int MAX_DEPTH = 32;

  private final Map<String, Integer> ids = new HashMap<>();
  private final List<String> names = new ArrayList<>();
  /** Per section, the time of each frame in the ring (nanoseconds). */
  private long[][] history = new long[0][];
  private long[] current = new long[0];
  private final long[] frames = new long[HISTORY];
  private int head;
  private int recorded;
  private long frameStart = -1;
  private final int[] stack = new int[MAX_DEPTH];
  private final long[] started = new long[MAX_DEPTH];
  private int depth;
  /** Off: nothing is measured (begin/end/add return at once). */
  public boolean enabled = true;

  /** The id of a section, created on first use; faster than names for sections measured very often. */
  public int section(String name) {
    final var id = this.ids.get(name);
    if (id != null) {
      return id;
    }
    final int n = this.names.size();
    this.ids.put(name, n);
    this.names.add(name);
    this.history = Arrays.copyOf(this.history, n + 1);
    this.history[n] = new long[HISTORY];
    this.current = Arrays.copyOf(this.current, n + 1);
    return n;
  }

  public void begin(String name) {
    begin(section(name));
  }

  public void begin(int section) {
    if (!this.enabled) {
      return;
    }
    if (this.depth == MAX_DEPTH) {
      throw new IllegalStateException("profiler sections nested too deep (missing end()?)");
    }
    this.stack[this.depth] = section;
    this.started[this.depth++] = System.nanoTime();
  }

  /** Ends the innermost section begun. */
  public void end() {
    if (!this.enabled) {
      return;
    }
    if (this.depth == 0) {
      throw new IllegalStateException("end() without begin()");
    }
    this.depth--;
    this.current[this.stack[this.depth]] += System.nanoTime() - this.started[this.depth];
  }

  /** Adds time measured elsewhere to a section of this frame. */
  public void add(int section, long nanos) {
    if (this.enabled) {
      this.current[section] += nanos;
    }
  }

  /** Closes the frame: its section times go into the history, and the time since the last call is the frame time. */
  public void endFrame() {
    final long now = System.nanoTime();
    if (!this.enabled) {
      this.frameStart = -1;
      return;
    }
    this.frames[this.head] = this.frameStart < 0 ? 0L : now - this.frameStart;
    this.frameStart = now;
    for (int s = 0; s < this.current.length; s++) {
      this.history[s][this.head] = this.current[s];
      this.current[s] = 0L;
    }
    this.head = (this.head + 1) % HISTORY;
    this.recorded = Math.min(this.recorded + 1, HISTORY);
    this.depth = 0; // an exception between begin and end must not wedge the stack
  }

  /** Section names in the order they were first used. */
  public List<String> sections() {
    return this.names;
  }

  /** Frames in the history (up to {@link #HISTORY}). */
  public int frames() {
    return this.recorded;
  }

  /** Milliseconds of the frame {@code framesAgo} frames back (0 is the last closed frame). */
  public float frameMs(int framesAgo) {
    return this.frames[slot(framesAgo)] / 1e6f;
  }

  /** Milliseconds a section took in the frame {@code framesAgo} frames back (0 if it does not exist). */
  public float ms(String name, int framesAgo) {
    final var id = this.ids.get(name);
    return id == null ? 0f : this.history[id][slot(framesAgo)] / 1e6f;
  }

  /** Average milliseconds per frame of a section over the last {@code frames} frames. */
  public float average(String name, int frames) {
    final var id = this.ids.get(name);
    final int n = Math.min(frames, this.recorded);
    if (id == null || n == 0) {
      return 0f;
    }
    long sum = 0L;
    for (int i = 0; i < n; i++) {
      sum += this.history[id][slot(i)];
    }
    return sum / 1e6f / n;
  }

  /** Average frame time over the last {@code frames} frames, in milliseconds. */
  public float averageFrameMs(int frames) {
    final int n = Math.min(frames, this.recorded);
    if (n == 0) {
      return 0f;
    }
    long sum = 0L;
    for (int i = 0; i < n; i++) {
      sum += this.frames[slot(i)];
    }
    return sum / 1e6f / n;
  }

  /** The longest frame of the last {@code frames}, in milliseconds. */
  public float maxFrameMs(int frames) {
    long max = 0L;
    for (int i = 0; i < Math.min(frames, this.recorded); i++) {
      max = Math.max(max, this.frames[slot(i)]);
    }
    return max / 1e6f;
  }

  private int slot(int framesAgo) {
    return Math.floorMod(this.head - 1 - framesAgo, HISTORY);
  }
}
