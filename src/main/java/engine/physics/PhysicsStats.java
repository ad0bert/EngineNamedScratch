package engine.physics;

/** Counters of the last physics step, for HUDs and tuning. */
public class PhysicsStats {
  public int colliders;
  public int bodies;
  public int sleepingBodies;
  /** Pairs that survived the broadphase and went to the narrowphase. */
  public int candidatePairs;
  public int contacts;
  public float cellSize;
}
