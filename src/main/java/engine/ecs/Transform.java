package engine.ecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Position, rotation and scale with an optional parent. */
public class Transform {
  private final Vector3f position = new Vector3f();
  private final Quaternionf rotation = new Quaternionf();
  private final Vector3f scale = new Vector3f(1f);
  private Transform parent;
  private final List<Transform> children = new ArrayList<>();

  private final Vector3f prevPosition = new Vector3f();
  private final Quaternionf prevRotation = new Quaternionf();
  private final Vector3f prevScale = new Vector3f(1f);

  /**
   * Remembers the current state as the "previous" state used for render interpolation. The engine calls this at the
   * start of every fixed step; call it after moving an entity instantly (a teleport) to avoid a one-frame smear.
   */
  public void snapshot() {
    this.prevPosition.set(this.position);
    this.prevRotation.set(this.rotation);
    this.prevScale.set(this.scale);
  }

  /** World matrix blended between the previous and the current fixed step ({@code alpha} in 0..1). */
  public Matrix4f interpolatedWorldMatrix(float alpha, Matrix4f dest) {
    final Vector3f p = this.prevPosition.lerp(this.position, alpha, new Vector3f());
    final Quaternionf q = this.prevRotation.nlerp(this.rotation, alpha, new Quaternionf());
    final Vector3f s = this.prevScale.lerp(this.scale, alpha, new Vector3f());
    dest.translationRotateScale(p, q, s);
    if (this.parent != null) {
      this.parent.interpolatedWorldMatrix(alpha, new Matrix4f()).mul(dest, dest);
    }
    return dest;
  }

  public Vector3f position() {
    return this.position;
  }

  public Quaternionf rotation() {
    return this.rotation;
  }

  public Vector3f scale() {
    return this.scale;
  }

  public Transform parent() {
    return this.parent;
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
    if (newParent != null) {
      newParent.children.add(this);
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
    localMatrix(dest);
    if (this.parent != null) {
      final Matrix4f p = this.parent.worldMatrix(new Matrix4f());
      p.mul(dest, dest);
    }
    return dest;
  }

  public Vector3f worldPosition(Vector3f dest) {
    return worldMatrix(new Matrix4f()).getTranslation(dest);
  }

  /** Local forward direction (-Z) in the parent's space. */
  public Vector3f forward(Vector3f dest) {
    return this.rotation.transform(dest.set(0f, 0f, -1f));
  }

  public Vector3f right(Vector3f dest) {
    return this.rotation.transform(dest.set(1f, 0f, 0f));
  }
}
