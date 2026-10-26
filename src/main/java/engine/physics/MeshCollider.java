package engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.render.MeshData;
import engine.spatial.SpatialGrid;

/**
 * A triangle mesh for level geometry: terrain, ramps, rooms. Use it on static entities (or kinematic ones that move
 * rarely: the world-space copy and its bounding volume hierarchy are rebuilt whenever the entity moved). Dynamic bodies
 * need boxes, spheres or capsules; a mesh never collides with another mesh.
 *
 * <p>
 * Triangles are one-sided by default: they push things out of their front (the side their counter-clockwise winding
 * faces, the side the mesh's normals point to) and ignore anything entirely behind them, which keeps bodies from being
 * caught on the back of a surface. Set {@link #twoSided} for thin, open geometry.
 */
public class MeshCollider extends Collider {
  public boolean twoSided;

  private final float[] local; // xyz per vertex
  private final int[] indices;
  private final float[] world;
  private final float[] normals; // per triangle
  private final TriangleTree tree = new TriangleTree();
  private final Matrix4f matrix = new Matrix4f();
  private final Matrix4f builtFor = new Matrix4f();
  private boolean built;
  private final Vector3f center = new Vector3f();
  private float radius;
  private final Vector3f tmp = new Vector3f();
  private final float[] box = new float[6];

  /** Uses the positions and triangle indices of render geometry. */
  public MeshCollider(MeshData data) {
    this(positions(data), data.indices());
  }

  /** @param positions xyz per vertex; @param indices three per triangle (counter-clockwise seen from the front) */
  public MeshCollider(float[] positions, int[] indices) {
    if (indices == null || indices.length % 3 != 0) {
      throw new IllegalArgumentException("a mesh collider needs triangles");
    }
    this.local = positions.clone();
    this.indices = indices.clone();
    this.world = new float[positions.length];
    this.normals = new float[indices.length];
  }

  private static float[] positions(MeshData data) {
    final int n = data.vertexCount();
    final float[] p = new float[n * 3];
    for (int v = 0; v < n; v++) {
      System.arraycopy(data.vertices(), v * MeshData.FLOATS_PER_VERTEX, p, v * 3, 3);
    }
    return p;
  }

  public int triangleCount() {
    return this.indices.length / 3;
  }

  @Override
  void refresh() {
    final var m = entity().transform().worldMatrix(this.matrix);
    if (this.built && m.equals(this.builtFor)) {
      return;
    }
    this.builtFor.set(m);
    this.built = true;
    final var v = this.tmp;
    for (int i = 0; i < this.local.length; i += 3) {
      m.transformPosition(v.set(this.local[i], this.local[i + 1], this.local[i + 2]));
      this.world[i] = v.x;
      this.world[i + 1] = v.y;
      this.world[i + 2] = v.z;
    }
    for (int t = 0; t < triangleCount(); t++) {
      final int a = this.indices[t * 3] * 3;
      final int b = this.indices[t * 3 + 1] * 3;
      final int c = this.indices[t * 3 + 2] * 3;
      final float e1x = this.world[b] - this.world[a];
      final float e1y = this.world[b + 1] - this.world[a + 1];
      final float e1z = this.world[b + 2] - this.world[a + 2];
      final float e2x = this.world[c] - this.world[a];
      final float e2y = this.world[c + 1] - this.world[a + 1];
      final float e2z = this.world[c + 2] - this.world[a + 2];
      v.set(e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x);
      if (v.lengthSquared() > 0f) {
        v.normalize();
      }
      this.normals[t * 3] = v.x;
      this.normals[t * 3 + 1] = v.y;
      this.normals[t * 3 + 2] = v.z;
    }
    this.tree.build(this.world, this.indices);
    if (this.tree.isEmpty()) {
      m.getTranslation(this.center);
      this.radius = 0f;
      return;
    }
    this.tree.rootBounds(this.box);
    this.center.set(this.box[0] + this.box[3], this.box[1] + this.box[4], this.box[2] + this.box[5]).mul(0.5f);
    this.radius = 0.5f * (float) Math.sqrt((this.box[3] - this.box[0]) * (this.box[3] - this.box[0])
        + (this.box[4] - this.box[1]) * (this.box[4] - this.box[1])
        + (this.box[5] - this.box[2]) * (this.box[5] - this.box[2]));
  }

  void vertex(int triangle, int corner, Vector3f dest) {
    final int i = this.indices[triangle * 3 + corner] * 3;
    dest.set(this.world[i], this.world[i + 1], this.world[i + 2]);
  }

  void normal(int triangle, Vector3f dest) {
    dest.set(this.normals[triangle * 3], this.normals[triangle * 3 + 1], this.normals[triangle * 3 + 2]);
  }

  /** Triangles whose boxes overlap the given world box (valid after {@link #refresh()}). */
  void candidates(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, SpatialGrid.IntList out) {
    this.tree.query(minX, minY, minZ, maxX, maxY, maxZ, out);
  }

  /** Nearest triangle hit by the ray (normalized direction) within {@code maxDistance}: {distance, triangle} or null. */
  float[] ray(Vector3f o, Vector3f d, float maxDistance) {
    final float[] best = { maxDistance, -1f };
    this.tree.ray(o.x, o.y, o.z, d.x, d.y, d.z, maxDistance, (t, max) -> {
      final float dist = ShapeTests.rayTriangle(this, t, o, d, max);
      if (dist >= 0f && dist <= best[0]) {
        best[0] = dist;
        best[1] = t;
      }
      return best[0];
    });
    return best[1] < 0f ? null : best;
  }

  @Override
  public Vector3f worldCenter(Vector3f dest) {
    refresh();
    return dest.set(this.center);
  }

  @Override
  public float boundingRadius() {
    refresh();
    return this.radius;
  }

  @Override
  void cachedCenter(Vector3f dest) {
    dest.set(this.center);
  }

  @Override
  float cachedRadius() {
    return this.radius;
  }

  @Override
  float cachedInnerRadius() {
    return 0f; // a surface has no inside
  }

  @Override
  Matrix4f cachedMatrix() {
    return this.matrix;
  }

  @Override
  float cachedVolume() {
    return 0f; // not part of a body's mass
  }

  @Override
  void addCachedInertia(float mass, Matrix3f dest) {
  }
}
