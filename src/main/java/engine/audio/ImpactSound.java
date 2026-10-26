package engine.audio;

import org.joml.Vector3f;

import engine.ecs.Component;
import engine.physics.Rigidbody;

/**
 * Plays a sound when the entity's rigidbody hits something, louder for harder impacts. The impulse is estimated from
 * the change of the body's velocity over the physics step in which the contact started ({@code mass * |dv|}), so it
 * needs a dynamic {@link Rigidbody} on the same entity; {@link AudioSystem} listens for the collision events.
 */
public class ImpactSound extends Component {
  public AudioClip clip;
  /** Volume of the hardest impact. */
  public float volume = 1f;
  /** Impulses (N s) below this are silent, from here the volume grows up to {@link #maxImpulse}. */
  public float minImpulse = 0.8f;
  public float maxImpulse = 10f;
  /** Pitch is varied randomly by up to this fraction, so repeated impacts do not sound identical. */
  public float pitchVariation = 0.12f;
  /** Heavier bodies sound lower: the pitch is divided by mass to this power (0 disables it). */
  public float massPitch = 0.15f;
  /** Seconds before this entity can make another impact sound. */
  public float minInterval = 0.08f;
  public int priority;

  final Vector3f lastVelocity = new Vector3f();
  float cooldown;

  public ImpactSound() {
  }

  public ImpactSound(AudioClip clip) {
    this.clip = clip;
  }

  /** Volume factor 0..1 for an impulse: silent below the minimum, then rising (square root) to 1 at the maximum. */
  public static float strength(float impulse, float minImpulse, float maxImpulse) {
    if (impulse <= minImpulse) {
      return 0f;
    }
    if (impulse >= maxImpulse || maxImpulse <= minImpulse) {
      return 1f;
    }
    return (float) Math.sqrt((impulse - minImpulse) / (maxImpulse - minImpulse));
  }

  /** Remembers the velocity before the physics step (component updates run before physics). */
  @Override
  public void update(double dt) {
    this.cooldown -= (float) dt;
    final var body = entity().findOrNull(Rigidbody.class);
    if (body != null) {
      this.lastVelocity.set(body.velocity);
    }
  }

  /** The estimated impulse of the step that just ended, or 0 without a dynamic body. */
  float impulse() {
    final var body = entity().findOrNull(Rigidbody.class);
    if (body == null || body.kinematic) {
      return 0f;
    }
    final float dx = body.velocity.x - this.lastVelocity.x;
    final float dy = body.velocity.y - this.lastVelocity.y;
    final float dz = body.velocity.z - this.lastVelocity.z;
    return body.mass * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}
