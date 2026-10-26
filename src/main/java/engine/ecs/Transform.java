package engine.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import lombok.Getter;

/**
 * Position, rotation and scale with an optional parent. Matrix queries do not allocate: they use per-thread scratch
 * objects, so they are safe to call from several threads and cheap enough to call per entity per frame.
 */
public class Transform {
  @Getter private final Vector3f position = new Vector3f();
  @Getter private final Quaternionf rotation = new Quaternionf();
  @Getter private final Vector3f scale = new Vector3f(1f);
  @Getter private Transform parent;
  private final List<Transform> children = new ArrayList<>();

  private final Vector3f prevPosition = new Vector3f();
  private final Quaternionf prevRotation = new Quaternionf();
  private final Vector3f prevScale = new Vector3f(1f);
  private long changes;
  private boolean tracked;
  private static volatile long trackedChanges;

  private static final class Scratch {
    final Vector3f p = new Vector3f();
    final Vector3f s = new Vector3f();
    final Vector3f v = new Vector3f();
    final Quaternionf q = new Quaternionf();
    final Quaternionf q2 = new Quaternionf();
    final Quaternionf q3 = new Quaternionf();
    final Matrix4f tmp = new Matrix4f();
    final Matrix4f inverse = new Matrix4f();
    final List<Matrix4f> parents = new ArrayList<>(); // one matrix per hierarchy depth

    Matrix4f parentMatrix(int depth) {
      while (this.parents.size() <= depth) {
        this.parents.add(new Matrix4f());
      }
      return this.parents.get(depth);
    }
  }

  private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

  /**
   * Remembers the current state as the "previous" state used for render interpolation. The engine calls this at the
   * start of every fixed step; call it after moving an entity instantly (a teleport) to avoid a one-frame smear.
   */
  public void snapshot() {
    if (!this.position.equals(this.prevPosition) || !this.rotation.equals(this.prevRotation)
        || !this.scale.equals(this.prevScale)) {
      this.changes++;
      if (this.tracked) {
        trackedChanges++;
      }
    }
    this.prevPosition.set(this.position);
    this.prevRotation.set(this.rotation);
    this.prevScale.set(this.scale);
  }

  /**
   * Changes whenever this transform or an ancestor was found modified by a {@link #snapshot()} (or was re-parented).
   * Together with {@link #isChainStatic()} it lets callers cache values derived from the world matrix: the cache is
   * valid while the chain is static and this number is the one seen when it was computed.
   */
  public long chainVersion() {
    long v = 0;
    for (Transform t = this; t != null; t = t.parent) {
      v += t.changes;
    }
    return v;
  }

  /**
   * Asks to be told when this transform or one of its ancestors changes: from now on such a change (found by
   * {@link #snapshot()}, or a re-parenting) increases {@link #trackedChangeCount()}. Used by caches of world-space data
   * that are rarely invalid, such as static render geometry, so they only need to look for changes when the counter
   * moved.
   */
  public void trackChanges() {
    for (Transform t = this; t != null && !t.tracked; t = t.parent) {
      t.tracked = true;
    }
  }

  /** Increases whenever a transform marked with {@link #trackChanges()} (or an ancestor of one) changed. */
  public static long trackedChangeCount() {
    return trackedChanges;
  }

  /**
   * True if this transform and all its ancestors have not changed since the last {@link #snapshot()}, i.e. the
   * interpolated and the plain world matrix are identical and could be cached.
   */
  public boolean isChainStatic() {
    for (Transform t = this; t != null; t = t.parent) {
      if (!t.position.equals(t.prevPosition) || !t.rotation.equals(t.prevRotation)
          || !t.scale.equals(t.prevScale)) {
        return false;
      }
    }
    return true;
  }

  /** World matrix blended between the previous and the current fixed step ({@code alpha} in 0..1). */
  public Matrix4f interpolatedWorldMatrix(float alpha, Matrix4f dest) {
    return interpolated(alpha, dest, SCRATCH.get(), 0);
  }

  private Matrix4f interpolated(float alpha, Matrix4f dest, Scratch s, int depth) {
    this.prevPosition.lerp(this.position, alpha, s.p);
    this.prevRotation.nlerp(this.rotation, alpha, s.q);
    this.prevScale.lerp(this.scale, alpha, s.s);
    dest.translationRotateScale(s.p, s.q, s.s); // dest is complete before the scratch values are reused
    if (this.parent != null) {
      final var pm = s.parentMatrix(depth);
      this.parent.interpolated(alpha, pm, s, depth + 1);
      pm.mul(dest, dest);
    }
    return dest;
  }

  public List<Transform> children() {
    return Collections.unmodifiableList(this.children);
  }

  public void setParent(Transform newParent) {
    for (Transform t = newParent; t != null; t = t.parent) {
      if (t == this) {
        throw new IllegalArgumentException("Transform hierarchy cycle");
      }
    }
    if (this.parent != null) {
      this.parent.children.remove(this);
    }
    this.parent = newParent;
    this.changes++;
    if (newParent != null) {
      newParent.children.add(this);
    }
    if (this.tracked) {
      trackedChanges++;
      if (newParent != null) {
        newParent.trackChanges(); // the new ancestors now move this one too
      }
    }
  }

  public Transform setEulerDegrees(float x, float y, float z) {
    this.rotation.identity().rotateXYZ((float) Math.toRadians(x), (float) Math.toRadians(y),
        (float) Math.toRadians(z));
    return this;
  }

  public Matrix4f localMatrix(Matrix4f dest) {
    return dest.translationRotateScale(this.position, this.rotation, this.scale);
  }

  public Matrix4f worldMatrix(Matrix4f dest) {
    return world(dest, SCRATCH.get(), 0);
  }

  private Matrix4f world(Matrix4f dest, Scratch s, int depth) {
    localMatrix(dest);
    if (this.parent != null) {
      final var pm = s.parentMatrix(depth);
      this.parent.world(pm, s, depth + 1);
      pm.mul(dest, dest);
    }
    return dest;
  }

  public Vector3f worldPosition(Vector3f dest) {
    final var s = SCRATCH.get();
    return world(s.tmp, s, 0).getTranslation(dest);
  }

  /** Moves the entity by a world-space offset, converting it into this transform's parent space. */
  public void translateWorld(Vector3f delta) {
    if (this.parent == null) {
      this.position.add(delta);
      return;
    }
    final var s = SCRATCH.get();
    this.parent.world(s.inverse, s, 0).invert().transformDirection(delta, s.v);
    this.position.add(s.v);
  }

  /**
   * Rotates the entity about its own origin by a world-space rotation, converting it into this transform's parent
   * space.
   */
  public void rotateWorld(Quaternionf delta) {
    if (this.parent == null) {
      this.rotation.premul(delta).normalize();
      return;
    }
    final var s = SCRATCH.get();
    this.parent.world(s.inverse, s, 0).getNormalizedRotation(s.q2); // the parent's world rotation P
    // local' = P^-1 * delta * P * local
    s.q3.set(s.q2).conjugate().mul(delta).mul(s.q2);
    this.rotation.premul(s.q3).normalize();
  }

  /** Local forward direction (-Z) in the parent's space. */
  public Vector3f forward(Vector3f dest) {
    return this.rotation.transform(dest.set(0f, 0f, -1f));
  }

  public Vector3f right(Vector3f dest) {
    return this.rotation.transform(dest.set(1f, 0f, 0f));
  }
}
