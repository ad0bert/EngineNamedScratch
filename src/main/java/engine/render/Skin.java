package engine.render;

import org.joml.Matrix4f;

import engine.ecs.Transform;

/**
 * The skeleton of a skinned {@link MeshRenderer}: joint transforms (usually entities animated by an
 * {@code Animator}) and their inverse bind matrices. Every frame the render queue computes the joint matrices
 * {@code inverse(meshWorld) * jointWorld * inverseBind} at the interpolated state; the backend uploads them and
 * deforms the mesh's vertices on the GPU by their {@link MeshData#skin} weights. Several renderers (one per
 * primitive or material) may share one skin.
 */
public final class Skin {
  private final Transform[] joints;
  private final Matrix4f[] inverseBind;
  private final float[] palette;
  private final Matrix4f meshInverse = new Matrix4f();
  private final Matrix4f joint = new Matrix4f();
  private long frame = -1;
  private float frameAlpha = Float.NaN;

  /** @param inverseBind one per joint: the inverse of the joint's world matrix in the bind pose (mesh space) */
  public Skin(Transform[] joints, Matrix4f[] inverseBind) {
    if (joints.length != inverseBind.length || joints.length == 0) {
      throw new IllegalArgumentException("a skin needs as many inverse bind matrices as joints, and one joint");
    }
    this.joints = joints.clone();
    this.inverseBind = new Matrix4f[inverseBind.length];
    for (int i = 0; i < inverseBind.length; i++) {
      this.inverseBind[i] = new Matrix4f(inverseBind[i]);
    }
    this.palette = new float[16 * joints.length];
    for (int i = 0; i < joints.length; i++) {
      new Matrix4f().get(this.palette, 16 * i);
    }
  }

  public int jointCount() {
    return this.joints.length;
  }

  public Transform joint(int index) {
    return this.joints[index];
  }

  /** The joint matrices of the last update, 16 floats each (column-major); read-only. */
  public float[] palette() {
    return this.palette;
  }

  /**
   * Recomputes the joint matrices relative to the mesh ({@code meshWorld}: the renderer's model matrix) at render
   * interpolation {@code alpha}. A skin shared by several renderers is computed once per frame. Does not allocate.
   */
  public void update(long frameNumber, float alpha, Matrix4f meshWorld) {
    if (frameNumber == this.frame && alpha == this.frameAlpha) {
      return;
    }
    this.frame = frameNumber;
    this.frameAlpha = alpha;
    meshWorld.invert(this.meshInverse);
    for (int i = 0; i < this.joints.length; i++) {
      this.joints[i].interpolatedWorldMatrix(alpha, this.joint);
      this.meshInverse.mul(this.joint, this.joint).mul(this.inverseBind[i]).get(this.palette, 16 * i);
    }
  }
}
