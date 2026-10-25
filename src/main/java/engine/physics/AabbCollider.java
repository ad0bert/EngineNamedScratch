package engine.physics;

import org.joml.Vector3f;

import engine.ecs.Component;

/**
 * Axis-aligned box collider. Half extents are scaled by the entity's world scale; rotation is ignored (the box stays
 * axis-aligned).
 */
public class AabbCollider extends Component {
  public final Vector3f halfExtents = new Vector3f(0.5f);
  public final Vector3f offset = new Vector3f();

  public AabbCollider() {
  }

  public AabbCollider(Vector3f halfExtents) {
    this.halfExtents.set(halfExtents);
  }

  public void worldBounds(Vector3f outMin, Vector3f outMax) {
    final var m = entity().transform().worldMatrix(new org.joml.Matrix4f());
    final Vector3f center = m.transformPosition(new Vector3f(this.offset));
    final Vector3f scale = m.getScale(new Vector3f());
    final Vector3f ext = new Vector3f(this.halfExtents).mul(scale);
    center.sub(ext, outMin);
    center.add(ext, outMax);
  }

  public boolean overlaps(AabbCollider other) {
    final Vector3f aMin = new Vector3f();
    final Vector3f aMax = new Vector3f();
    final Vector3f bMin = new Vector3f();
    final Vector3f bMax = new Vector3f();
    worldBounds(aMin, aMax);
    other.worldBounds(bMin, bMax);
    return aMin.x <= bMax.x && aMax.x >= bMin.x
        && aMin.y <= bMax.y && aMax.y >= bMin.y
        && aMin.z <= bMax.z && aMax.z >= bMin.z;
  }
}
