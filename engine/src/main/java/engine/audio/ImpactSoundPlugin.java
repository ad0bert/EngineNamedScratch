package engine.audio;

import java.util.concurrent.ThreadLocalRandom;

import org.joml.Vector3f;

import engine.core.Engine;
import engine.core.EnginePlugin;
import engine.messaging.IMessageReceiver;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.CollisionEvent;
import engine.physics.Rigidbody;
import engine.serialization.ComponentRegistry;
import engine.serialization.FieldSerializer;

/**
 * Collision sounds: listens for collision messages and plays the {@link ImpactSound} of the body that was hit
 * hardest, louder for harder impacts. A default plugin.
 */
public final class ImpactSoundPlugin implements EnginePlugin {
  private Sounds receiver;

  @Override
  public String id() {
    return "engine.impact-sounds";
  }

  @Override
  public void install(Engine engine) {
    this.receiver = new Sounds(engine.audio());
    engine.messages().register(this.receiver, MessageQueue.COLLISION);
  }

  @Override
  public void uninstall(Engine engine) {
    if (this.receiver != null) {
      engine.messages().unregister(this.receiver, MessageQueue.COLLISION);
      this.receiver = null;
    }
  }

  @Override
  public void registerComponents(ComponentRegistry registry) {
    registry.register(new FieldSerializer<>("ImpactSound", ImpactSound.class, ctx -> new ImpactSound()));
  }

  /** Plays impact sounds on {@code audio} for the collision messages it receives. */
  public static IMessageReceiver receiver(AudioSystem audio) {
    return new Sounds(audio);
  }

  private static final class Sounds implements IMessageReceiver {
    private final AudioSystem audio;
    private final Vector3f tmp = new Vector3f();

    Sounds(AudioSystem audio) {
      this.audio = audio;
    }

    @Override
    public void receiveMessage(MessageObject message) {
      if (message.getPayload() instanceof CollisionEvent c && c.started()) {
        final var ia = c.a().findOrNull(ImpactSound.class);
        final var ib = c.b().findOrNull(ImpactSound.class);
        if (ia == null && ib == null) {
          return;
        }
        final float impulse = Math.max(ia != null ? ia.impulse() : 0f, ib != null ? ib.impulse() : 0f);
        // the faster-changing body is the one that was hit hardest; it makes the sound
        final var sound = ia == null ? ib : ib == null ? ia : ia.impulse() >= ib.impulse() ? ia : ib;
        impact(sound, impulse);
      }
    }

    private void impact(ImpactSound s, float impulse) {
      if (!s.isEnabled() || s.clip == null || s.cooldown > 0f) {
        return;
      }
      final float strength = ImpactSound.strength(impulse, s.minImpulse, s.maxImpulse);
      if (strength <= 0f) {
        return;
      }
      final var body = s.entity().findOrNull(Rigidbody.class);
      final float mass = body != null ? Math.max(0.01f, body.mass) : 1f;
      float pitch = s.massPitch > 0f ? (float) Math.pow(mass, -s.massPitch) : 1f;
      pitch *= 1f + (ThreadLocalRandom.current().nextFloat() * 2f - 1f) * s.pitchVariation;
      if (this.audio.play(s.clip, s.entity().transform().worldPosition(this.tmp), s.volume * strength, pitch,
          AudioBus.EFFECTS, s.priority)) {
        s.cooldown = s.minInterval;
      }
    }
  }
}
