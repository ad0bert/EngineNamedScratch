package engine.terrain;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.physics.MeshCollider;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshRenderer;
import engine.resources.ResourceManager;

/**
 * Turns a {@link Heightfield} into entities: square chunks (children of one root) with a mesh each, coarser meshes for
 * the distance (every second, fourth... sample) and a {@link MeshCollider} at full detail, so bodies, casts,
 * character controllers and navigation grids treat it like any other ground. Chunks have skirts (a strip hanging
 * down from their edges) that hide the cracks where chunks of different detail meet. Normals are smooth (from the
 * heights around each sample); uvs are in metres divided by {@link Settings#textureSize}, so a tiling texture
 * repeats evenly.
 */
public final class Terrain {
  /** How the terrain is cut and detailed. */
  public static final class Settings {
    /** Cells along each side of a chunk; a multiple of {@code 2^(levels - 1)}. */
    public int chunkCells = 32;
    /** Levels of detail per chunk (1: full detail only). */
    public int levels = 3;
    /** Camera distance from which the first coarser level is drawn; each further level from twice the last. */
    public float lodDistance = 40f;
    /** Metres one repeat of the material's texture covers. */
    public float textureSize = 4f;
    /** How far the skirts hang below the edges, in metres. */
    public float skirtDepth = 2f;
    /** Give the chunks colliders. */
    public boolean colliders = true;
    /** Chunks are static geometry (culled through the grid). */
    public boolean staticGeometry = true;
  }

  private Terrain() {
  }

  /**
   * Adds the terrain to the scene: a root entity named {@code name} (move it to place the terrain) with one child per
   * chunk. The meshes are uploaded through {@code resources}, which owns (and disposes) them.
   */
  public static Entity build(Scene scene, ResourceManager resources, Heightfield field, Material material,
      Settings settings, String name) {
    final int step = 1 << Math.max(0, settings.levels - 1);
    if (settings.chunkCells <= 0 || settings.chunkCells % step != 0) {
      throw new IllegalArgumentException("chunkCells must be a positive multiple of " + step);
    }
    final var root = new Entity(name);
    scene.add(root);
    final int cellsX = field.columns() - 1;
    final int cellsZ = field.rows() - 1;
    for (int r0 = 0; r0 < cellsZ; r0 += settings.chunkCells) {
      for (int c0 = 0; c0 < cellsX; c0 += settings.chunkCells) {
        final int w = Math.min(settings.chunkCells, cellsX - c0);
        final int d = Math.min(settings.chunkCells, cellsZ - r0);
        final var chunk = new Entity(name + "-" + c0 / settings.chunkCells + "-" + r0 / settings.chunkCells);
        chunk.transform().setParent(root.transform());
        chunk.transform().position().set(c0 * field.spacing(), 0f, r0 * field.spacing());
        final var full = chunkMesh(field, c0, r0, w, d, 1, settings.textureSize, settings.skirtDepth);
        final var renderer = chunk.add(new MeshRenderer(resources.mesh(full), material));
        for (int level = 1; level < settings.levels; level++) {
          final int s = 1 << level;
          if (w % s != 0 || d % s != 0) {
            break; // a partial chunk at the edge keeps the levels that fit
          }
          renderer.addLod(settings.lodDistance * (1 << (level - 1)),
              resources.mesh(chunkMesh(field, c0, r0, w, d, s, settings.textureSize, settings.skirtDepth)));
        }
        renderer.setStaticGeometry(settings.staticGeometry);
        if (settings.colliders) {
          final var surface = chunkMesh(field, c0, r0, w, d, 1, settings.textureSize, 0f);
          chunk.add(new MeshCollider(positions(surface), surface.indices()));
        }
        scene.add(chunk);
      }
    }
    return root;
  }

  /**
   * The mesh of the chunk of {@code w x d} cells from sample (c0, r0), every {@code step}-th sample, in the chunk's
   * local space (its first sample at the origin); with skirts of {@code skirt} metres unless that is 0.
   */
  public static MeshData chunkMesh(Heightfield f, int c0, int r0, int w, int d, int step, float textureSize,
      float skirt) {
    final int nx = w / step + 1;
    final int nz = d / step + 1;
    final float sp = f.spacing();
    final List<float[]> verts = new ArrayList<>();
    final var n = new Vector3f();
    for (int j = 0; j < nz; j++) {
      for (int i = 0; i < nx; i++) {
        final int c = c0 + i * step;
        final int r = r0 + j * step;
        f.normal(c, r, n);
        verts.add(new float[] { i * step * sp, f.sample(c, r), j * step * sp, n.x, n.y, n.z, c * sp / textureSize,
            r * sp / textureSize });
      }
    }
    final List<Integer> idx = new ArrayList<>();
    for (int j = 0; j < nz - 1; j++) {
      for (int i = 0; i < nx - 1; i++) {
        final int a = j * nx + i;
        final int b = a + 1;
        final int c = a + nx;
        final int e = c + 1;
        // counter-clockwise from above, split along (a, e) like Heightfield.heightAt
        idx.add(a);
        idx.add(e);
        idx.add(b);
        idx.add(a);
        idx.add(c);
        idx.add(e);
      }
    }
    if (skirt > 0f) {
      // each edge, walked round the chunk: a quad per cell from the edge down to the skirt's foot
      addSkirt(verts, idx, nx, nz, skirt, 0, 0, 1, 0, nx); // z = 0 edge, along +x
      addSkirt(verts, idx, nx, nz, skirt, nx - 1, 0, 0, 1, nz); // x = max edge, along +z
      addSkirt(verts, idx, nx, nz, skirt, nx - 1, nz - 1, -1, 0, nx); // z = max edge, along -x
      addSkirt(verts, idx, nx, nz, skirt, 0, nz - 1, 0, -1, nz); // x = 0 edge, along -z
    }
    final float[] v = new float[verts.size() * MeshData.INPUT_FLOATS_PER_VERTEX];
    for (int k = 0; k < verts.size(); k++) {
      System.arraycopy(verts.get(k), 0, v, k * MeshData.INPUT_FLOATS_PER_VERTEX, MeshData.INPUT_FLOATS_PER_VERTEX);
    }
    return MeshData.triangles(v, idx.stream().mapToInt(Integer::intValue).toArray());
  }

  private static void addSkirt(List<float[]> verts, List<Integer> idx, int nx, int nz, float depth, int i0, int j0,
      int di, int dj, int count) {
    int prevTop = -1;
    int prevFoot = -1;
    for (int k = 0; k < count; k++) {
      final int top = (j0 + dj * k) * nx + (i0 + di * k);
      final float[] t = verts.get(top);
      final float[] foot = t.clone();
      foot[1] -= depth;
      final int footIndex = verts.size();
      verts.add(foot);
      if (prevTop >= 0) { // faces outwards (the edges are walked round the chunk in one direction)
        idx.add(prevTop);
        idx.add(top);
        idx.add(prevFoot);
        idx.add(top);
        idx.add(footIndex);
        idx.add(prevFoot);
      }
      prevTop = top;
      prevFoot = footIndex;
    }
  }

  private static float[] positions(MeshData data) {
    final int n = data.vertices().length / MeshData.FLOATS_PER_VERTEX;
    final float[] p = new float[n * 3];
    for (int v = 0; v < n; v++) {
      System.arraycopy(data.vertices(), v * MeshData.FLOATS_PER_VERTEX, p, v * 3, 3);
    }
    return p;
  }
}
