package engine.core;

import java.util.function.DoubleConsumer;

/** Fixed-timestep accumulator. All times are in seconds; callers feed it {@link System#nanoTime()} values. */
public class FixedTimestep {
  private final double step;
  private final double maxFrameTime;
  private double accumulator;
  private double elapsed;
  private long lastNanos;
  private boolean started;

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
    this.accumulator += frameTime;
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

  /** Fraction of a step left in the accumulator (0..1): how far rendering is between the last two states. */
  public float alpha() {
    return (float) Math.max(0.0, Math.min(1.0, this.accumulator / this.step));
  }

  public double step() {
    return this.step;
  }

  public double elapsed() {
    return this.elapsed;
  }
}
