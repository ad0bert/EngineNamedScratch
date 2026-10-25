package engine.physics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.messaging.IMessageService;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;

/** Brute-force O(n^2) overlap detection; emits enter/exit {@link CollisionEvent}s. Fine for small scenes. */
public class CollisionSystem implements ISystem {
  private final IMessageService messages;
  private Set<Pair> active = new HashSet<>();

  public CollisionSystem(IMessageService messages) {
    this.messages = messages;
  }

  private record Pair(long a, long b) {
    static Pair of(AabbCollider x, AabbCollider y) {
      final long i = x.entity().id();
      final long j = y.entity().id();
      return new Pair(Math.min(i, j), Math.max(i, j));
    }
  }

  @Override
  public void update(Scene scene, double dt) {
    final List<AabbCollider> colliders = scene.components(AabbCollider.class);
    final Set<Pair> now = new HashSet<>();
    for (int i = 0; i < colliders.size(); i++) {
      for (int j = i + 1; j < colliders.size(); j++) {
        final AabbCollider a = colliders.get(i);
        final AabbCollider b = colliders.get(j);
        if (a.entity() != b.entity() && a.overlaps(b)) {
          final Pair p = Pair.of(a, b);
          now.add(p);
          if (!this.active.contains(p)) {
            emit(a, b, true);
          }
        }
      }
    }
    for (Pair p : this.active) {
      if (!now.contains(p)) {
        scene.stream().filter(e -> e.id() == p.a()).findFirst().ifPresent(ea -> scene.stream()
            .filter(e -> e.id() == p.b()).findFirst().ifPresent(eb -> this.messages.send(MessageObject.builder()
                .sender(this).queue(MessageQueue.COLLISION).payload(new CollisionEvent(ea, eb, false)).build())));
      }
    }
    this.active = now;
  }

  private void emit(AabbCollider a, AabbCollider b, boolean started) {
    this.messages.send(MessageObject.builder().sender(this).queue(MessageQueue.COLLISION)
        .payload(new CollisionEvent(a.entity(), b.entity(), started)).build());
  }
}
