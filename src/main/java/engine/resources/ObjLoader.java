package engine.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import engine.render.MeshData;

/**
 * Minimal Wavefront OBJ loader: v/vt/vn/f (including negative indices), polygons are fan-triangulated, missing normals
 * become flat face normals. Materials (.mtl) are ignored.
 */
public final class ObjLoader {
  private ObjLoader() {
  }

  public static MeshData load(InputStream in, String name) throws IOException {
    final List<float[]> pos = new ArrayList<>();
    final List<float[]> tex = new ArrayList<>();
    final List<float[]> nor = new ArrayList<>();
    final List<Float> out = new ArrayList<>();
    final List<Integer> indices = new ArrayList<>();
    final Map<String, Integer> cache = new HashMap<>();

    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      int lineNo = 0;
      while ((line = br.readLine()) != null) {
        lineNo++;
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        final String[] t = line.split("\\s+");
        try {
          switch (t[0]) {
          case "v" -> pos.add(floats(t, 3));
          case "vt" -> tex.add(floats(t, 2));
          case "vn" -> nor.add(floats(t, 3));
          case "f" -> face(t, pos, tex, nor, out, indices, cache);
          default -> {
            // o, g, s, usemtl, mtllib ... ignored
          }
          }
        } catch (RuntimeException e) {
          throw new IOException("Bad OBJ line " + lineNo + " in " + name + ": " + line, e);
        }
      }
    }
    if (indices.isEmpty()) {
      throw new IOException("OBJ " + name + " contains no faces");
    }
    final float[] vertices = new float[out.size()];
    for (int i = 0; i < vertices.length; i++) {
      vertices[i] = out.get(i);
    }
    return MeshData.triangles(vertices, indices.stream().mapToInt(Integer::intValue).toArray());
  }

  private static float[] floats(String[] t, int n) {
    final float[] r = new float[n];
    for (int i = 0; i < n; i++) {
      r[i] = i + 1 < t.length ? Float.parseFloat(t[i + 1]) : 0f;
    }
    return r;
  }

  private static int resolve(String s, int size) {
    if (s.isEmpty()) {
      return -1;
    }
    final int i = Integer.parseInt(s);
    return i > 0 ? i - 1 : size + i;
  }

  private static void face(String[] t, List<float[]> pos, List<float[]> tex, List<float[]> nor, List<Float> out,
      List<Integer> indices, Map<String, Integer> cache) {
    final int n = t.length - 1;
    if (n < 3) {
      throw new IllegalArgumentException("face needs at least 3 vertices");
    }
    final int[][] refs = new int[n][3];
    for (int i = 0; i < n; i++) {
      final String[] p = t[i + 1].split("/", -1);
      refs[i][0] = resolve(p[0], pos.size());
      refs[i][1] = p.length > 1 ? resolve(p[1], tex.size()) : -1;
      refs[i][2] = p.length > 2 ? resolve(p[2], nor.size()) : -1;
    }
    // flat normal for faces that do not provide any
    final float[] flat = flatNormal(pos.get(refs[0][0]), pos.get(refs[1][0]), pos.get(refs[2][0]));
    final int[] ids = new int[n];
    for (int i = 0; i < n; i++) {
      final int[] r = refs[i];
      final String key = r[0] + "/" + r[1] + "/" + r[2] + (r[2] < 0 ? "/" + flat[0] + "," + flat[1] + "," + flat[2] : "");
      Integer id = cache.get(key);
      if (id == null) {
        id = out.size() / MeshData.FLOATS_PER_VERTEX;
        cache.put(key, id);
        final float[] p = pos.get(r[0]);
        final float[] nn = r[2] >= 0 ? nor.get(r[2]) : flat;
        final float[] tt = r[1] >= 0 ? tex.get(r[1]) : new float[] { 0f, 0f };
        out.add(p[0]);
        out.add(p[1]);
        out.add(p[2]);
        out.add(nn[0]);
        out.add(nn[1]);
        out.add(nn[2]);
        out.add(tt[0]);
        out.add(tt[1]);
      }
      ids[i] = id;
    }
    for (int i = 1; i < n - 1; i++) {
      indices.add(ids[0]);
      indices.add(ids[i]);
      indices.add(ids[i + 1]);
    }
  }

  private static float[] flatNormal(float[] a, float[] b, float[] c) {
    final Vector3f n = new Vector3f(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        .cross(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
    if (n.lengthSquared() > 0f) {
      n.normalize();
    } else {
      n.set(0f, 1f, 0f);
    }
    return new float[] { n.x, n.y, n.z };
  }
}
