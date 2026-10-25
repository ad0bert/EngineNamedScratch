package engine.input;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe polled input state, written by the window thread and read by the update thread. */
public class InputState {
  private final Set<Short> keysDown = ConcurrentHashMap.newKeySet();
  private final Set<Short> keysPressedThisFrame = ConcurrentHashMap.newKeySet();
  private final AtomicInteger mouseDx = new AtomicInteger();
  private final AtomicInteger mouseDy = new AtomicInteger();
  private final Map<String, short[]> bindings = new ConcurrentHashMap<>();

  void keyDown(short key) {
    if (this.keysDown.add(key)) {
      this.keysPressedThisFrame.add(key);
    }
  }

  void keyUp(short key) {
    this.keysDown.remove(key);
  }

  void mouseMoved(int dx, int dy) {
    this.mouseDx.addAndGet(dx);
    this.mouseDy.addAndGet(dy);
  }

  public void releaseAll() {
    this.keysDown.clear();
  }

  public boolean isKeyDown(short key) {
    return this.keysDown.contains(key);
  }

  /** True once per physical key press; cleared by {@link #endFrame()}. */
  public boolean wasKeyPressed(short key) {
    return this.keysPressedThisFrame.contains(key);
  }

  public void bind(String action, short... keys) {
    this.bindings.put(action, keys);
  }

  public boolean isActionDown(String action) {
    final short[] keys = this.bindings.get(action);
    if (keys != null) {
      for (short k : keys) {
        if (isKeyDown(k)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean wasActionPressed(String action) {
    final short[] keys = this.bindings.get(action);
    if (keys != null) {
      for (short k : keys) {
        if (wasKeyPressed(k)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Mouse movement since the last call, in pixels; resets the accumulator. */
  public int consumeMouseDx() {
    return this.mouseDx.getAndSet(0);
  }

  public int consumeMouseDy() {
    return this.mouseDy.getAndSet(0);
  }

  public void endFrame() {
    this.keysPressedThisFrame.clear();
  }
}
