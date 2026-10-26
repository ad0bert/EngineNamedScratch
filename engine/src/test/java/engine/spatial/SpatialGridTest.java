package engine.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SpatialGridTest {

  private static Set<Integer> idsInCells(SpatialGrid grid) {
    final Set<Integer> ids = new HashSet<>();
    grid.forEachCell((list, x0, y0, z0, x1, y1, z1) -> {
      for (int i = 0; i < list.size(); i++) {
        ids.add(list.get(i));
      }
    });
    return ids;
  }

  @Test
  void smallSphereLivesInOneCellAndCellBoundsContainIt() {
    final var grid = new SpatialGrid(10f);
    grid.insert(7, 12f, -3f, 25f, 0.5f);
    assertEquals(1, grid.occupiedCells());
    grid.forEachCell((list, x0, y0, z0, x1, y1, z1) -> {
      assertEquals(7, list.get(0));
      assertTrue(x0 <= 12f && 12f <= x1 && y0 <= -3f && -3f <= y1 && z0 <= 25f && 25f <= z1);
      assertEquals(10f, x1 - x0, 1e-5f);
    });
  }

  @Test
  void sphereOnABoundaryIsRegisteredInEveryCellItTouches() {
    final var grid = new SpatialGrid(10f);
    grid.insert(1, 10f, 5f, 5f, 1f); // straddles x = 10
    assertEquals(2, grid.occupiedCells());
    final int[] seen = { 0 };
    grid.forEachCell((list, x0, y0, z0, x1, y1, z1) -> seen[0] += list.size());
    assertEquals(2, seen[0]);
  }

  @Test
  void spheresBiggerThanACellGoToTheLargeList() {
    final var grid = new SpatialGrid(10f);
    grid.insert(3, 0f, 0f, 0f, 500f);
    grid.insert(4, 5f, 5f, 5f, 1f);
    assertEquals(1, grid.large().size());
    assertEquals(3, grid.large().get(0));
    assertEquals(1, grid.occupiedCells());
    assertEquals(Set.of(4), idsInCells(grid));
  }

  @Test
  void removeAndReinsertMoveAnId() {
    final var grid = new SpatialGrid(10f);
    grid.insert(1, 5f, 5f, 5f, 1f);
    grid.insert(1, 95f, 5f, 5f, 1f); // re-insert elsewhere replaces the old position
    assertEquals(1, grid.occupiedCells());
    grid.forEachCell((list, x0, y0, z0, x1, y1, z1) -> assertTrue(x0 > 80f));

    grid.remove(1);
    assertEquals(0, grid.occupiedCells());
    grid.remove(1); // removing twice or unknown ids is harmless
    grid.remove(99);

    grid.insert(2, 0f, 0f, 0f, 100f);
    grid.remove(2);
    assertEquals(0, grid.large().size());
  }

  @Test
  void negativeCoordinatesWork() {
    final var grid = new SpatialGrid(4f);
    grid.insert(0, -1f, -1f, -1f, 0.2f);
    grid.insert(1, 1f, 1f, 1f, 0.2f);
    assertEquals(2, grid.occupiedCells());
    grid.forEachCell((list, x0, y0, z0, x1, y1, z1) -> {
      final int id = list.get(0);
      final float v = id == 0 ? -1f : 1f;
      assertTrue(x0 <= v && v <= x1);
    });
  }

  @Test
  void boxQueryVisitsOnlyCellsNearTheBoxButNeverMissesOne() {
    final var grid = new SpatialGrid(10f);
    for (int x = 0; x < 50; x++) {
      for (int z = 0; z < 50; z++) {
        grid.insert(x * 50 + z, x * 10f + 5f, 5f, z * 10f + 5f, 1f);
      }
    }
    assertEquals(2500, grid.occupiedCells());

    final Set<Integer> hit = new HashSet<>();
    final int[] visited = { 0 };
    grid.forEachCellInBox(100f, 0f, 200f, 140f, 9f, 240f, (list, x0, y0, z0, x1, y1, z1) -> {
      visited[0]++;
      for (int i = 0; i < list.size(); i++) {
        hit.add(list.get(i));
      }
    });
    assertTrue(visited[0] <= 25, "looked at " + visited[0] + " cells instead of all 2500");
    for (int x = 10; x <= 14; x++) {
      for (int z = 20; z <= 24; z++) {
        assertTrue(hit.contains(x * 50 + z), "cell " + x + "," + z + " must be visited");
      }
    }

    // a box covering everything, or lying outside, still behaves
    final int[] all = { 0 };
    grid.forEachCellInBox(-1e6f, -1e6f, -1e6f, 1e6f, 1e6f, 1e6f, (list, x0, y0, z0, x1, y1, z1) -> all[0]++);
    assertEquals(2500, all[0]);
    final int[] none = { 0 };
    grid.forEachCellInBox(1e4f, 0f, 0f, 2e4f, 10f, 10f, (list, x0, y0, z0, x1, y1, z1) -> none[0]++);
    assertEquals(0, none[0]);
  }

  @Test
  void clearEmptiesEverything() {
    final var grid = new SpatialGrid(2f);
    grid.insert(0, 0f, 0f, 0f, 0.5f);
    grid.insert(1, 0f, 0f, 0f, 50f);
    grid.clear();
    assertEquals(0, grid.occupiedCells());
    assertEquals(0, grid.large().size());
    assertFalse(idsInCells(grid).contains(0));
  }
}
