package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** Entities the host teleports: copies go straight there instead of sliding through the world. */
class TeleportTest {
  @Test
  void teleportedCopiesJumpInsteadOfSliding() {
    final var bed = new ReplicationBed();
    bed.addGuest();
    bed.connectAll();
    final var marker = bed.server.spawn("marker", new Vector3f(0, 0, 0));
    bed.step(30);
    final var copy = bed.copy(0, marker);
    marker.transform().position().set(8f, 0, 0);
    bed.server.teleported(marker);
    final List<Float> seen = new ArrayList<>();
    final var matrix = new Matrix4f();
    final var drawn = new Vector3f();
    for (int i = 0; i < 40; i++) {
      bed.step(1);
      seen.add(copy.transform().position().x);
      copy.transform().interpolatedWorldMatrix(0.5f, matrix).getTranslation(drawn);
      seen.add(drawn.x);
    }
    assertEquals(8f, copy.transform().position().x, 1e-3f, "arrived");
    assertTrue(seen.stream().allMatch(x -> x < 0.01f || x > 7.99f), "never in between: " + seen);

    // without the word from the host it slides, as before
    marker.transform().position().set(0f, 0, 0);
    final List<Float> slid = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      bed.step(1);
      slid.add(copy.transform().position().x);
    }
    assertTrue(slid.stream().anyMatch(x -> x > 1f && x < 7f), "slides when not told: " + slid);
  }

  @Test
  void lateJoinersGetTheTeleportCount() {
    final var bed = new ReplicationBed();
    final var marker = bed.server.spawn("marker", new Vector3f(0, 0, 0));
    bed.step(5);
    marker.transform().position().set(5f, 0, 0);
    bed.server.teleported(marker);
    bed.step(5);
    bed.addGuest();
    bed.connectAll();
    bed.step(30);
    final var copy = bed.copy(0, marker);
    marker.transform().position().set(6f, 0, 0); // an ordinary move after joining slides
    final List<Float> xs = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      bed.step(1);
      xs.add(copy.transform().position().x);
    }
    assertTrue(xs.stream().anyMatch(x -> x > 5.05f && x < 5.95f), "slid: " + xs);
  }
}
