package engine.physics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the convex hull of a point cloud the quickhull way: start from the largest tetrahedron of extreme points,
 * then keep adding the point farthest outside the hull (removing the faces it sees and closing the hole with faces
 * to it) until no point is outside. Stopping after {@code maxVertices} gives an inner approximation made of the most
 * significant points. Coplanar triangles are merged into convex polygons afterwards, so a box comes out with six
 * quads. Runs once when a collider is made (it allocates; the physics step does not use it).
 */
final class HullBuilder {
  private HullBuilder() {
  }

  /**
   * The hull: {@code vertices} xyz, face f has the vertex indices {@code faceIndices[faceStart[f]..faceStart[f+1])}
   * counter-clockwise seen from outside, and the unit outward normal {@code normals[3f..3f+2]}.
   */
  record Hull(float[] vertices, int[] faceStart, int[] faceIndices, float[] normals) {
    int vertexCount() {
      return this.vertices.length / 3;
    }

    int faceCount() {
      return this.faceStart.length - 1;
    }
  }

  private static final class Face {
    final int a;
    final int b;
    final int c;
    final double nx;
    final double ny;
    final double nz;
    final double d;
    boolean alive = true;

    Face(double[] p, int a, int b, int c) {
      this.a = a;
      this.b = b;
      this.c = c;
      final double ux = p[b * 3] - p[a * 3];
      final double uy = p[b * 3 + 1] - p[a * 3 + 1];
      final double uz = p[b * 3 + 2] - p[a * 3 + 2];
      final double vx = p[c * 3] - p[a * 3];
      final double vy = p[c * 3 + 1] - p[a * 3 + 1];
      final double vz = p[c * 3 + 2] - p[a * 3 + 2];
      double nx = uy * vz - uz * vy;
      double ny = uz * vx - ux * vz;
      double nz = ux * vy - uy * vx;
      final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (len > 0) {
        nx /= len;
        ny /= len;
        nz /= len;
      }
      this.nx = nx;
      this.ny = ny;
      this.nz = nz;
      this.d = nx * p[a * 3] + ny * p[a * 3 + 1] + nz * p[a * 3 + 2];
    }

    double distance(double[] p, int i) {
      return this.nx * p[i * 3] + this.ny * p[i * 3 + 1] + this.nz * p[i * 3 + 2] - this.d;
    }
  }

  static Hull build(float[] points, int maxVertices) {
    final int n = points.length / 3;
    if (n < 4) {
      throw new IllegalArgumentException("a convex hull needs at least 4 points");
    }
    if (maxVertices < 4) {
      throw new IllegalArgumentException("a convex hull needs at least 4 vertices");
    }
    final double[] p = new double[n * 3];
    for (int i = 0; i < p.length; i++) {
      p[i] = points[i];
    }
    // the extreme points along the axes; the farthest pair of them starts the hull
    final int[] extremes = new int[6];
    for (int i = 0; i < n; i++) {
      for (int k = 0; k < 3; k++) {
        if (p[i * 3 + k] < p[extremes[k * 2] * 3 + k]) {
          extremes[k * 2] = i;
        }
        if (p[i * 3 + k] > p[extremes[k * 2 + 1] * 3 + k]) {
          extremes[k * 2 + 1] = i;
        }
      }
    }
    int i0 = 0;
    int i1 = 0;
    double far = -1;
    for (int x = 0; x < 6; x++) {
      for (int y = x + 1; y < 6; y++) {
        final double dist = dist2(p, extremes[x], extremes[y]);
        if (dist > far) {
          far = dist;
          i0 = extremes[x];
          i1 = extremes[y];
        }
      }
    }
    final double size = Math.sqrt(far);
    final double eps = Math.max(1e-9, 1e-5 * size);
    if (size < 1e-9) {
      throw new IllegalArgumentException("the points of a convex hull are all in one place");
    }
    int i2 = -1;
    far = -1;
    for (int i = 0; i < n; i++) {
      final double dist = lineDistance2(p, i0, i1, i);
      if (dist > far) {
        far = dist;
        i2 = i;
      }
    }
    if (Math.sqrt(far) < eps) {
      throw new IllegalArgumentException("the points of a convex hull lie on a line");
    }
    final var base = new Face(p, i0, i1, i2);
    int i3 = -1;
    far = -1;
    for (int i = 0; i < n; i++) {
      final double dist = Math.abs(base.distance(p, i));
      if (dist > far) {
        far = dist;
        i3 = i;
      }
    }
    if (far < eps) {
      throw new IllegalArgumentException("the points of a convex hull are flat");
    }
    final List<Face> faces = new ArrayList<>();
    faces.add(oriented(p, i0, i1, i2, i3));
    faces.add(oriented(p, i0, i1, i3, i2));
    faces.add(oriented(p, i1, i2, i3, i0));
    faces.add(oriented(p, i2, i0, i3, i1));
    final boolean[] inHull = new boolean[n];
    inHull[i0] = inHull[i1] = inHull[i2] = inHull[i3] = true;
    int vertices = 4;
    int[] outside = new int[n];
    int outsideCount = 0;
    for (int i = 0; i < n; i++) {
      if (!inHull[i]) {
        outside[outsideCount++] = i;
      }
    }
    final List<int[]> horizon = new ArrayList<>();
    while (vertices < maxVertices) {
      int best = -1;
      double bestDist = eps;
      for (int k = 0; k < outsideCount; k++) {
        final int i = outside[k];
        double dist = -Double.MAX_VALUE;
        for (final var f : faces) {
          if (f.alive) {
            dist = Math.max(dist, f.distance(p, i));
          }
        }
        if (dist <= eps) { // inside for good: the hull only grows
          outside[k--] = outside[--outsideCount];
          continue;
        }
        if (dist > bestDist) {
          bestDist = dist;
          best = i;
        }
      }
      if (best < 0) {
        break;
      }
      // the faces the point sees go; the edges between them and the rest are the horizon
      horizon.clear();
      for (final var f : faces) {
        if (f.alive && f.distance(p, best) > eps) {
          f.alive = false;
          toggle(horizon, f.a, f.b);
          toggle(horizon, f.b, f.c);
          toggle(horizon, f.c, f.a);
        }
      }
      for (final int[] e : horizon) {
        faces.add(new Face(p, e[0], e[1], best));
      }
      faces.removeIf(f -> !f.alive);
      inHull[best] = true;
      vertices++;
    }
    return merge(p, faces, eps);
  }

  /** Face abc turned so that {@code opposite} is behind it. */
  private static Face oriented(double[] p, int a, int b, int c, int opposite) {
    final var f = new Face(p, a, b, c);
    return f.distance(p, opposite) > 0 ? new Face(p, a, c, b) : f;
  }

  private static void toggle(List<int[]> edges, int a, int b) {
    for (int i = 0; i < edges.size(); i++) {
      final int[] e = edges.get(i);
      if (e[0] == b && e[1] == a) {
        edges.remove(i);
        return;
      }
    }
    edges.add(new int[] { a, b });
  }

  /** Groups coplanar triangles into polygons and keeps only the vertices the faces use. */
  private static Hull merge(double[] p, List<Face> faces, double eps) {
    final int n = p.length / 3;
    final int[] remap = new int[n];
    Arrays.fill(remap, -1);
    final List<Integer> used = new ArrayList<>();
    final List<int[]> polygons = new ArrayList<>();
    final List<double[]> normals = new ArrayList<>();
    final boolean[] grouped = new boolean[faces.size()];
    for (int f = 0; f < faces.size(); f++) {
      if (grouped[f]) {
        continue;
      }
      final var face = faces.get(f);
      final List<Integer> ids = new ArrayList<>();
      for (int g = f; g < faces.size(); g++) {
        final var other = faces.get(g);
        if (grouped[g]) {
          continue;
        }
        final double dot = face.nx * other.nx + face.ny * other.ny + face.nz * other.nz;
        if (dot > 1 - 1e-6 && Math.abs(face.d - other.d) < eps) {
          grouped[g] = true;
          for (final int v : new int[] { other.a, other.b, other.c }) {
            if (!ids.contains(v)) {
              ids.add(v);
            }
          }
        }
      }
      final int[] polygon = convexPolygon(p, ids, face);
      for (int k = 0; k < polygon.length; k++) {
        if (remap[polygon[k]] < 0) {
          remap[polygon[k]] = used.size();
          used.add(polygon[k]);
        }
        polygon[k] = remap[polygon[k]];
      }
      polygons.add(polygon);
      normals.add(new double[] { face.nx, face.ny, face.nz });
    }
    final float[] vertices = new float[used.size() * 3];
    for (int i = 0; i < used.size(); i++) {
      for (int k = 0; k < 3; k++) {
        vertices[i * 3 + k] = (float) p[used.get(i) * 3 + k];
      }
    }
    final int[] faceStart = new int[polygons.size() + 1];
    int total = 0;
    for (int f = 0; f < polygons.size(); f++) {
      faceStart[f] = total;
      total += polygons.get(f).length;
    }
    faceStart[polygons.size()] = total;
    final int[] faceIndices = new int[total];
    final float[] faceNormals = new float[polygons.size() * 3];
    for (int f = 0; f < polygons.size(); f++) {
      System.arraycopy(polygons.get(f), 0, faceIndices, faceStart[f], polygons.get(f).length);
      for (int k = 0; k < 3; k++) {
        faceNormals[f * 3 + k] = (float) normals.get(f)[k];
      }
    }
    return new Hull(vertices, faceStart, faceIndices, faceNormals);
  }

  /** The 2D convex hull (monotone chain) of points in a face's plane, counter-clockwise about its normal. */
  private static int[] convexPolygon(double[] p, List<Integer> ids, Face face) {
    // a basis of the plane with u x v = normal
    double ux;
    double uy;
    double uz;
    if (Math.abs(face.nx) > 0.57735) {
      ux = face.ny;
      uy = -face.nx;
      uz = 0;
    } else {
      ux = 0;
      uy = face.nz;
      uz = -face.ny;
    }
    final double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
    ux /= len;
    uy /= len;
    uz /= len;
    final double vx = face.ny * uz - face.nz * uy;
    final double vy = face.nz * ux - face.nx * uz;
    final double vz = face.nx * uy - face.ny * ux;
    final int m = ids.size();
    final double[] su = new double[m];
    final double[] sv = new double[m];
    final Integer[] order = new Integer[m];
    for (int k = 0; k < m; k++) {
      final int i = ids.get(k);
      su[k] = p[i * 3] * ux + p[i * 3 + 1] * uy + p[i * 3 + 2] * uz;
      sv[k] = p[i * 3] * vx + p[i * 3 + 1] * vy + p[i * 3 + 2] * vz;
      order[k] = k;
    }
    Arrays.sort(order, (a, b) -> su[a] != su[b] ? Double.compare(su[a], su[b]) : Double.compare(sv[a], sv[b]));
    final int[] chain = new int[2 * m];
    int k = 0;
    for (int i = 0; i < m; i++) { // lower hull
      while (k >= 2 && cross(su, sv, chain[k - 2], chain[k - 1], order[i]) <= 0) {
        k--;
      }
      chain[k++] = order[i];
    }
    for (int i = m - 2, lower = k + 1; i >= 0; i--) { // upper hull
      while (k >= lower && cross(su, sv, chain[k - 2], chain[k - 1], order[i]) <= 0) {
        k--;
      }
      chain[k++] = order[i];
    }
    final int[] polygon = new int[k - 1]; // the last point repeats the first
    for (int i = 0; i < k - 1; i++) {
      polygon[i] = ids.get(chain[i]);
    }
    return polygon;
  }

  private static double cross(double[] su, double[] sv, int o, int a, int b) {
    return (su[a] - su[o]) * (sv[b] - sv[o]) - (sv[a] - sv[o]) * (su[b] - su[o]);
  }

  private static double dist2(double[] p, int a, int b) {
    final double dx = p[a * 3] - p[b * 3];
    final double dy = p[a * 3 + 1] - p[b * 3 + 1];
    final double dz = p[a * 3 + 2] - p[b * 3 + 2];
    return dx * dx + dy * dy + dz * dz;
  }

  private static double lineDistance2(double[] p, int a, int b, int i) {
    final double dx = p[b * 3] - p[a * 3];
    final double dy = p[b * 3 + 1] - p[a * 3 + 1];
    final double dz = p[b * 3 + 2] - p[a * 3 + 2];
    final double px = p[i * 3] - p[a * 3];
    final double py = p[i * 3 + 1] - p[a * 3 + 1];
    final double pz = p[i * 3 + 2] - p[a * 3 + 2];
    final double cx = dy * pz - dz * py;
    final double cy = dz * px - dx * pz;
    final double cz = dx * py - dy * px;
    return (cx * cx + cy * cy + cz * cz) / (dx * dx + dy * dy + dz * dz);
  }
}
