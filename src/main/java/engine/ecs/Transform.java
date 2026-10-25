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
