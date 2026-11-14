package engine.net.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

import engine.ecs.Component;
import engine.ecs.Entity;
import engine.net.BitReader;
import engine.net.BitWriter;
import engine.net.LoopbackNetwork.Conditions;
import engine.physics.Rigidbody;

/** What games plug into replication: their own commands and motors, copy hooks, the shape of their world. */
class ExtensionPointsTest {
  /** A game's own command: a sideways slide and a boost button. Nothing like a walking command. */
  record Slide(long tick, double viewTick, float speed, int buttons) implements InputCommand {
    static final int BOOST = 1;

    static final CommandCodec<Slide> CODEC = new CommandCodec<>() {
      @Override
      public Class<Slide> type() {
        return Slide.class;
      }

      @Override
      public void write(Slide c, BitWriter out) {
        out.writeFloat(c.speed, -10f, 10f, 12).writeBits(c.buttons, 1);
      }

      @Override
      public Slide read(BitReader in, long tick, double viewTick) {
        return new Slide(tick, viewTick, in.readFloat(-10f, 10f, 12), (int) in.readBits(1));
      }

      @Override
      public Slide idle(long tick) {
        return new Slide(tick, -1, 0f, 0);
      }
    };

    @Override
    public Slide at(long newTick, double newViewTick) {
      return new Slide(newTick, newViewTick, this.speed, this.buttons);
    }
  }

  /** Moves its entity along x by itself; twice as fast while boosting. */
  static final class SlideMotor extends PredictedMotor<Slide> {
    SlideMotor() {
      super(Slide.class);
    }

    @Override
    protected void move(Slide command, float dt) {
      entity().transform().position().x += command.speed() * dt * (isDown(Slide.BOOST) ? 2f : 1f);
    }

    @Override
    protected void writePredicted(BitWriter out) {
      out.writeVector(entity().transform().position());
    }

    @Override
    protected void readPredicted(BitReader in) {
      in.readVector(entity().transform().position());
    }
  }

  /** A component of the game's that must stand still on copies. */
  static final class Engine extends Component {
    boolean running = true;
  }

  private static ReplicationBed slidingBed(int guests) {
    final var bed = new ReplicationBed(r -> {
      r.setCommandCodec(Slide.CODEC);
      r.registerSpawner("slider", ctx -> {
        final var e = new Entity("slider");
        e.add(new SlideMotor());
        e.add(new Engine());
        return e;
      });
    });
    for (int i = 0; i < guests; i++) {
      bed.addGuest();
    }
    bed.connectAll();
    return bed;
  }

  @Test
  void aGamesOwnCommandsArePredicted() {
    final var bed = slidingBed(1);
    bed.network.conditions(new Conditions(0.04, 0.01, 0.02, 0.01));
    final var client = bed.clients.getFirst();
    final var onHost = bed.server.spawn("slider", new Vector3f(), client.client().id());
    bed.step(30);
    final var mine = client.predictedEntities().getFirst();
    final int[] n = new int[1];
    client.setCommandSource((tick, dt) -> {
      final int i = n[0]++;
      return new Slide(tick, -1, i < 120 ? 3f : 0f, i >= 60 && i < 90 ? Slide.BOOST : 0);
    });
    bed.step(2);
    assertTrue(mine.transform().position().x > 0f, "moves at once");
    bed.step(300);
    // 1.5 s at 3 m/s plus 0.5 s boosted to 6 m/s: 7.5 m (give or take the quantized speed)
    assertEquals(7.5f, onHost.transform().position().x, 0.05f);
    assertEquals(onHost.transform().position().x, mine.transform().position().x, 1e-3f);
    assertTrue(bed.server.commandOf(client.client().id()) instanceof Slide);
  }

  @Test
  void commandsOfTheWrongTypeAreRefused() {
    final var bed = slidingBed(0);
    final var player = bed.server.spawn("slider", new Vector3f());
    bed.server.setLocalPlayer(player, (tick, dt) -> new StandardCommand(tick, 0, 1, 0, 0, 0, -1));
    final var e = assertThrows(IllegalStateException.class, () -> bed.step(1));
    assertTrue(e.getMessage().contains("setCommandCodec"), e.getMessage());
  }

  @Test
  void copyHooksPrepareTheGamesComponents() {
    final var bed = new ReplicationBed(r -> r.registerSpawner("car", ctx -> {
      final var e = new Entity("car");
      e.add(new Engine());
      e.add(new Rigidbody());
      return e;
    }));
    final var client = bed.addGuest();
    client.addCopyHook(copy -> copy.get(Engine.class).running = false);
    bed.connectAll();
    final var car = bed.server.spawn("car", new Vector3f());
    bed.step(10);
    final var copy = bed.copy(0, car);
    assertFalse(copy.get(Engine.class).running, "the game's hook ran");
    assertTrue(copy.get(Rigidbody.class).kinematic, "and the engine's physics hook");
    assertTrue(car.get(Engine.class).running);

    client.removeCopyHook(CopyHook.PHYSICS);
    final var other = bed.server.spawn("car", new Vector3f());
    bed.step(10);
    assertFalse(bed.copy(0, other).get(Rigidbody.class).kinematic, "the physics hook can go");
  }

  /** A world that is a ring of 100 m along x: what leaves at 50 comes back at -50. */
  static final WorldSpace RING = new WorldSpace() {
    @Override
    public Vector3f delta(Vector3fc from, Vector3fc to, Vector3f dest) {
      float dx = (to.x() - from.x()) % 100f;
      if (dx > 50f) {
        dx -= 100f;
      } else if (dx < -50f) {
        dx += 100f;
      }
      return dest.set(dx, to.y() - from.y(), to.z() - from.z());
    }

    @Override
    public float distanceSquared(Vector3fc a, Vector3fc b) {
      return delta(a, b, new Vector3f()).lengthSquared();
    }

    @Override
    public Vector3f normalize(Vector3f p) {
      p.x = ((p.x + 50f) % 100f + 100f) % 100f - 50f;
      return p;
    }

    @Override
    public boolean wraps() {
      return true;
    }
  };

  @Test
  void aWorldSpaceShapesRelevanceAndBlending() {
    final var a = new Entity("a");
    a.transform().position().set(48f, 0, 0);
    final var b = new Entity("b");
    b.transform().position().set(-48f, 0, 0);
    assertTrue(RelevanceFilter.byDistance(5f, RING).isRelevant(null, b, List.of(a), false));
    assertFalse(RelevanceFilter.byDistance(5f).isRelevant(null, b, List.of(a), false));
    final var half = RING.lerp(new Vector3f(48f, 0, 0), new Vector3f(-48f, 0, 0), 0.75f, new Vector3f());
    assertEquals(-49f, half.x, 1e-4f, "three quarters of the short way, across the seam");
    assertEquals(new Vector3f(1f, 2f, 3f), WorldSpace.EUCLIDEAN.lerp(new Vector3f(), new Vector3f(2f, 4f, 6f), 0.5f,
        new Vector3f()));
  }
}
