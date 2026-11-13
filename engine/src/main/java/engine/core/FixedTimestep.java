package engine.core;

import java.util.function.DoubleConsumer;
import lombok.Getter;

/** Fixed-timestep accumulator. All times are in seconds; callers feed it {@link System#nanoTime()} values. */
public class FixedTimestep {
  @Getter private final double step;
  private final double maxFrameTime;
  private double accumulator;
  @Getter private double elapsed;
  private long lastNanos;
  private boolean started;
  private double timeScale = 1.0;

  public FixedTimestep(double step, double maxFrameTime) {
    if (step <= 0) {
      throw new IllegalArgumentException("step must be positive");
    }
    this.step = step;
    this.maxFrameTime = maxFrameTime;
  }

  /** Runs as many fixed steps as the elapsed wall time allows; returns how many ran. */
  public int advance(long nowNanos, DoubleConsumer stepFn) {
    if (!this.started) {
      this.started = true;
      this.lastNanos = nowNanos;
      return 0;
    }
    double frameTime = (nowNanos - this.lastNanos) / 1_000_000_000.0;
    this.lastNanos = nowNanos;
    if (frameTime > this.maxFrameTime) {
      frameTime = this.maxFrameTime; // avoid the spiral of death after a stall
    }
    this.accumulator += frameTime * this.timeScale;
    int steps = 0;
    // epsilon: repeated subtraction of e.g. 0.01 would otherwise lose a step to rounding
    while (this.accumulator >= this.step - 1e-9) {
      stepFn.accept(this.step);
      this.elapsed += this.step;
      this.accumulator -= this.step;
      steps++;
    }
    return steps;
  }

  /**
   * How fast wall time turns into steps: 1 is real time, 1.02 runs steps 2 % more often. Each step still advances the
   * simulation by exactly {@link #step()}; only how often steps happen changes. Meant for small corrections, such as
   * keeping a networked client's clock in line with the server's.
   */
  public double timeScale() {
    return this.timeScale;
  }

  public void setTimeScale(double timeScale) {
    if (!(timeScale > 0) || Double.isInfinite(timeScale)) {
      throw new IllegalArgumentException("time scale must be positive and finite: " + timeScale);
    }
    this.timeScale = timeScale;
  }

  /** Wall time, in nanoseconds, until the accumulator holds a full step again (0 if it already does). */
  public long nanosUntilNextStep() {
    final double missing = this.step - this.accumulator;
    return missing <= 0 ? 0 : (long) Math.ceil(missing / this.timeScale * 1_000_000_000.0);
  }

  /** Fraction of a step left in the accumulator (0..1): how far rendering is between the last two states. */
  public float alpha() {
    return (float) Math.max(0.0, Math.min(1.0, this.accumulator / this.step));
  }
}
