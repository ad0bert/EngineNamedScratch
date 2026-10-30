package engine.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import engine.ecs.ComponentUpdateSystem;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.messaging.MessageService;
import engine.physics.BoxCollider;
import engine.physics.CapsuleCollider;
import engine.physics.CharacterController;
import engine.physics.PhysicsSystem;
import engine.physics.Rigidbody;

class NavigationTest {
  private static final double DT = 1.0 / 60.0;

  private final PhysicsSystem physics = new PhysicsSystem(new MessageService());
  private final Scene scene = new Scene();
  private final ComponentUpdateSystem components = new ComponentUpdateSystem();

  private Entity box(float x, float y, float z, float hx, float hy, float hz) {
    final var e = new Entity("box");
    e.transform().position().set(x, y, z);
    e.add(new BoxCollider(hx, hy, hz));
    this.scene.add(e);
    return e;
  }

  /** A 20 x 20 floor (top at y = 0) with a wall along x = 0 from z = -10 to z = 6, so the way round is at z > 6. */
  private void walledLevel() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    box(0f, 1f, -2f, 0.25f, 1f, 8f);
  }

  private NavGrid bake() {
    this.scene.flush();
    return NavGrid.bake(this.physics, this.scene, new NavGrid.Settings(), -10f, -2f, -10f, 10f, 6f, 10f);
  }

  private static float length(List<Vector3f> path) {
    float len = 0f;
    for (int i = 1; i < path.size(); i++) {
      len += path.get(i).distance(path.get(i - 1));
    }
    return len;
  }

  @Test
  void findsTheWayRoundAWall() {
    walledLevel();
    final var grid = bake();
    assertFalse(grid.isWalkable(new Vector3f(0f, 0f, 0f)), "the wall");
    assertFalse(grid.isWalkable(new Vector3f(0.4f, 0f, 0f)), "too close to the wall for the agent's radius");
    assertTrue(grid.isWalkable(new Vector3f(-5f, 0f, 0f)));

    final var path = grid.findPath(new Vector3f(-5f, 0f, 0f), new Vector3f(5f, 0f, 0f));
    assertTrue(path.size() >= 3, "turns round the end of the wall: " + path);
    assertEquals(-5f, path.get(0).x, 0.5f);
    assertEquals(5f, path.get(path.size() - 1).x, 0.5f);
    for (final var p : path) {
      assertEquals(0f, p.y, 1e-3f);
    }
    assertTrue(path.stream().anyMatch(p -> p.z > 6f), "passes beyond z = 6");
    // straightened: close to the shortest way, about two straight lines past the corner at (0, 6.3)
    final float shortest = 2f * (float) Math.hypot(5f, 6.3f);
    assertTrue(length(path) < shortest * 1.1f, length(path) + " vs " + shortest);
  }

  @Test
  void aStraightPathHasTwoPoints() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    final var path = bake().findPath(new Vector3f(-8f, 0f, -8f), new Vector3f(7f, 0f, 5f));
    assertEquals(2, path.size());
  }

  @Test
  void climbsStepsButNotCliffsAndUsesEveryFloor() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    // a platform 1.2 m high from x = 2 to 6, z = 1.5 to 5.5, with a stair of four 0.3 m steps up its -z side
    box(4f, 0.6f, 3.5f, 2f, 0.6f, 2f);
    for (int i = 0; i < 4; i++) {
      final float h = 0.3f * (i + 1);
      box(4f, h / 2f, -0.25f + 0.5f * i, 2f, h / 2f, 0.25f);
    }
    final var grid = bake();
    final var top = new Vector3f(4f, 1.2f, 5f);
    assertTrue(grid.isWalkable(top));
    final var path = grid.findPath(new Vector3f(4f, 0f, -6f), top);
    assertFalse(path.isEmpty());
    assertEquals(1.2f, path.get(path.size() - 1).y, 1e-3f);
    for (int i = 1; i < path.size(); i++) { // never jumps up the cliff
      assertTrue(path.get(i).y - path.get(i - 1).y <= 1.25f);
    }
    assertTrue(path.stream().allMatch(p -> p.x > 2f && p.x < 6f), "comes up the stair, not round: " + path);

    grid.setBlocked(2f, -1.5f, 6f, 1.9f, true); // the stair
    assertTrue(grid.findPath(new Vector3f(4f, 0f, -6f), top).isEmpty(), "no other way up");
    grid.setBlocked(2f, -1.5f, 6f, 1.9f, false);
    assertFalse(grid.findPath(new Vector3f(4f, 0f, -6f), top).isEmpty());
  }

  @Test
  void keepsTheFloorUnderABridge() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    final var bridge = box(0f, 3f, 0f, 10f, 0.1f, 1f); // a deck at 3.1 over the floor
    final var grid = bake();
    assertTrue(grid.isWalkable(new Vector3f(0f, 0f, 0f)), "under it");
    assertTrue(grid.isWalkable(new Vector3f(0f, 3.1f, 0f)), "on it");
    assertEquals(3.1f, grid.nearestWalkable(new Vector3f(0f, 3.5f, 0f), 1f).y, 1e-3f);
    assertEquals(0f, grid.nearestWalkable(new Vector3f(0f, 1f, 0f), 1f).y, 1e-3f);

    // the deck goes; baking that region again finds only the floor
    this.scene.remove(bridge);
    this.scene.flush();
    grid.rebake(this.physics, this.scene, -10f, -1f, 10f, 1f);
    assertFalse(grid.isWalkable(new Vector3f(0f, 3.1f, 0f)));
    assertTrue(grid.isWalkable(new Vector3f(0f, 0f, 0f)));
  }

  @Test
  void ignoresDynamicBodiesWhenBaking() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    box(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f).add(new Rigidbody());
    assertTrue(bake().isWalkable(new Vector3f(0f, 0f, 0f)));
  }

  @Test
  void anAgentWithACharacterControllerWalksToTheGoal() {
    walledLevel();
    final var grid = bake();
    final var e = new Entity("npc");
    e.transform().position().set(-5f, 0.91f, 0f);
    e.add(new CapsuleCollider(0.3f, 0.6f));
    e.add(new CharacterController());
    final var agent = e.add(new NavAgent(grid));
    agent.speed = 4f;
    this.scene.add(e);
    this.scene.flush();
    agent.setDestination(new Vector3f(5f, 0f, 0f));
    double t = 0;
    while (!agent.hasArrived() && t < 15) {
      this.components.update(this.scene, DT);
      this.physics.update(this.scene, DT);
      t += DT;
    }
    assertTrue(agent.hasArrived(), "arrived after " + t + " s at " + e.transform().position());
    assertEquals(5f, e.transform().position().x, 0.4f);
    assertEquals(0f, e.transform().position().z, 0.4f);
    assertTrue(t < 8, "took " + t);
  }

  @Test
  void anAgentWithoutABodyFollowsTheFloors() {
    box(0f, -0.5f, 0f, 10f, 0.5f, 10f);
    box(4f, 0.15f, 0f, 2f, 0.15f, 10f); // a 0.3 m step from x = 2
    final var grid = bake();
    final var e = new Entity("ghost");
    e.transform().position().set(-4f, 0f, 0f);
    final var agent = e.add(new NavAgent(grid));
    assertNotNull(agent);
    this.scene.add(e);
    this.scene.flush();
    agent.setDestination(new Vector3f(4f, 0.3f, 0f));
    for (int i = 0; i < 600 && !agent.hasArrived(); i++) {
      this.components.update(this.scene, DT);
    }
    assertTrue(agent.hasArrived());
    assertEquals(0.3f, e.transform().position().y, 1e-3f);
    assertEquals(4f, e.transform().position().x, 0.3f);
  }

  @Test
  void reportsAnUnreachableGoal() {
    box(-5f, -0.5f, 0f, 4f, 0.5f, 10f);
    box(5f, -0.5f, 0f, 4f, 0.5f, 10f); // a 2 m gap between x = -1 and 1
    final var grid = bake();
    assertTrue(grid.findPath(new Vector3f(-5f, 0f, 0f), new Vector3f(5f, 0f, 0f)).isEmpty());
    final var e = new Entity("npc");
    final var agent = e.add(new NavAgent(grid));
    e.transform().position().set(-5f, 0f, 0f);
    this.scene.add(e);
    this.scene.flush();
    agent.setDestination(new Vector3f(5f, 0f, 0f));
    this.components.update(this.scene, DT);
    assertTrue(agent.isPathBlocked());
    assertEquals(-5f, e.transform().position().x, 1e-6f);
  }
}
