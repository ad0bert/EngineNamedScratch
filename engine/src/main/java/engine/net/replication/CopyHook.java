package engine.net.replication;

import engine.ecs.Entity;
import engine.physics.CharacterController;
import engine.physics.Rigidbody;

/**
 * Prepares a client's copy of a host entity, after its spawner built it and before it is shown: the host moves the
 * copy, so whatever would move it here has to stand still. {@link #PHYSICS} (installed by default) makes rigid bodies
 * kinematic and turns character controllers off; a game adds hooks for its own components
 * ({@link ClientReplication#addCopyHook}). Not called for the client's own predicted players.
 */
@FunctionalInterface
public interface CopyHook {
  /** The engine's physics: rigid bodies become kinematic, character controllers are turned off. */
  CopyHook PHYSICS = copy -> {
    for (int i = 0; i < copy.componentCount(); i++) {
      switch (copy.componentAt(i)) {
        case Rigidbody body -> body.kinematic = true;
        case CharacterController walker -> walker.setEnabled(false);
        default -> {
        }
      }
    }
  };

  void prepare(Entity copy);
}
