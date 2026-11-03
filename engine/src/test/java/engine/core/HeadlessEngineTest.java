package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;
import engine.physics.BoxCollider;
import engine.physics.MeshCollider;
import engine.physics.Rigidbody;
import engine.render.Material;
import engine.render.MeshFactory;
import engine.render.MeshRenderer;
import engine.render.NullRenderBackend;

class HeadlessEngineTest {
  private static final double STEP = 1 / 60.0;
  private final List<String> log = new ArrayList<>();

  private static EngineConfig config() {
    return new EngineConfig("Headless", "1", 8, 8, STEP);
  }

  /** A game that logs its lifecycle and every step's order of events. */
  private class LoggingGame implements Game {
    @Override
    public void init(Engine engine) {
      HeadlessEngineTest.this.log.add("init");
      engine.addSystem(new ISystem() {
        @Override
        public void update(Scene scene, double dt) {
          HeadlessEngineTest.this.log.add("system " + engine.tick());
        }
      });
      engine.addStepHook(new StepHook() {
        @Override
        public void beforeSystems(Engine e, long tick, double dt) {
          HeadlessEngineTest.this.log.add("before " + tick);
        }

        @Override
        public void afterStep(Engine e, long tick, double dt) {
          HeadlessEngineTest.this.log.add("after " + tick);
        }
      });
    }

    @Override
    public void update(Engine engine, double dt) {
      HeadlessEngineTest.this.log.add("game " + engine.tick());
    }

    @Override
    public void shutdown(Engine engine) {
      HeadlessEngineTest.this.log.add("shutdown");
    }
  }

  @Test
  void runsStepsInOrderWithNumberedTicks() {
    final var engine = new Engine(config(), new LoggingGame());
    assertEquals(0, engine.tick());
    engine.startHeadless();
    assertTrue(engine.isHeadless());
    assertInstanceOf(NullRenderBackend.class, engine.backend());
    assertNotNull(engine.resources());
    assertNotNull(engine.audio());
    engine.runTicks(2);
    assertEquals(2, engine.tick());
    engine.stop();
    assertEquals(List.of("init", "before 1", "system 1", "game 1", "after 1", "before 2", "system 2", "game 2",
        "after 2", "shutdown"), this.log);
  }

  @Test
  void hooksRunWhilePausedAndSystemsDoNot() {
    final var engine = new Engine(config(), new LoggingGame());
    engine.startHeadless();
    engine.setPaused(true);
    engine.runTicks(1);
    engine.stop();
    assertEquals(List.of("init", "before 1", "game 1", "after 1", "shutdown"), this.log);
  }

  @Test
  void postedMessagesArriveBeforeTheHooksAndPressesAreStillVisibleAfter() {
    final var engine = new Engine(config(), e -> {
    });
    engine.startHeadless();
    engine.input().bind("jump", (short) 32);
    final var seen = new ArrayList<String>();
    engine.messages().register(m -> seen.add("message " + m.getPayload()), MessageQueue.GAME);
    engine.addStepHook(new StepHook() {
      @Override
      public void beforeSystems(Engine e, long tick, double dt) {
        seen.add("before");
      }

      @Override
      public void afterStep(Engine e, long tick, double dt) {
        seen.add("after jump=" + e.input().wasActionPressed("jump"));
      }
    });
    engine.messages().post(MessageObject.builder().queue(MessageQueue.GAME).payload("hello").build());
    engine.input().keyDown((short) 32);
    engine.runTicks(2);
    engine.stop();
    assertEquals(List.of("message hello", "before", "after jump=true", "before", "after jump=false"), seen);
  }

  @Test
  void hooksCanBeRemoved() {
    final var engine = new Engine(config(), e -> {
    });
    engine.startHeadless();
    final var calls = new AtomicLong();
    final StepHook hook = new StepHook() {
      @Override
      public void afterStep(Engine e, long tick, double dt) {
        calls.incrementAndGet();
      }
    };
    engine.addStepHook(hook);
    engine.runTicks(3);
    engine.removeStepHook(hook);
    engine.runTicks(3);
    engine.stop();
    assertEquals(3, calls.get());
  }

  @Test
  void physicsAndMeshesWorkWithoutAWindow() {
    final AtomicReference<Entity> crate = new AtomicReference<>();
    final var engine = new Engine(config(), e -> {
      final var floorMesh = MeshFactory.quad(20f, 20f);
      final var floor = new Entity("floor");
      floor.transform().rotation().rotateX((float) -Math.PI / 2);
      floor.add(new MeshRenderer(e.resources().mesh(floorMesh), new Material()));
      floor.add(new MeshCollider(e.resources().mesh(floorMesh).data()));
      e.scene().add(floor);
      final var box = new Entity("crate");
      box.transform().position().set(0f, 3f, 0f);
      box.add(new BoxCollider(0.5f, 0.5f, 0.5f));
      box.add(new Rigidbody());
      e.scene().add(box);
      crate.set(box);
    });
    engine.startHeadless();
    engine.runTicks(240);
    engine.stop();
    final float y = crate.get().transform().position().y;
    assertTrue(y < 1f && y > 0.3f, "the crate should have fallen onto the floor, is at " + y);
  }

  @Test
  void aStopFromInsideAStepEndsRunTicks() {
    final var engine = new Engine(config(), new LoggingGame() {
      @Override
      public void update(Engine e, double dt) {
        super.update(e, dt);
        if (e.tick() == 2) {
          e.stop();
        }
      }
    });
    engine.startHeadless();
    engine.runTicks(10);
    assertEquals(2, engine.tick());
    assertEquals("shutdown", this.log.getLast());
    assertThrows(IllegalStateException.class, () -> engine.runTicks(1));
  }

  @Test
  void aStopFromTheGamesInitShutsDownAfterTheSetup() {
    final var stopping = new LoggingGame() {
      @Override
      public void init(Engine e) {
        super.init(e);
        e.stop();
      }
    };
    final var manual = new Engine(config(), stopping);
    manual.startHeadless();
    assertEquals(List.of("init", "shutdown"), this.log);
    this.log.clear();
    final var loop = new Engine(config(), stopping);
    loop.runHeadless(); // returns at once
    assertEquals(List.of("init", "shutdown"), this.log);
    assertEquals(0, loop.tick());
  }

  @Test
  void startsOnlyOnce() {
    final var engine = new Engine(config(), e -> {
    });
    assertThrows(IllegalStateException.class, () -> engine.runTicks(1), "not started");
    engine.startHeadless();
    assertThrows(IllegalStateException.class, engine::startHeadless);
    assertThrows(IllegalStateException.class, engine::run);
    engine.stop();
  }

  @Test
  void runHeadlessKeepsRealTimeUntilStopped() throws InterruptedException {
    final var started = new CountDownLatch(1);
    final var engine = new Engine(config(), new LoggingGame() {
      @Override
      public void update(Engine e, double dt) {
        if (e.tick() == 10) {
          started.countDown();
        }
      }
    });
    final var loop = Thread.ofPlatform().name("headless-test").start(engine::runHeadless);
    assertTrue(started.await(5, TimeUnit.SECONDS), "should reach tick 10 within a few seconds");
    final long begin = System.nanoTime();
    final long ticksBefore = engine.tick();
    Thread.sleep(300);
    final double seconds = (System.nanoTime() - begin) / 1e9;
    final long ran = engine.tick() - ticksBefore;
    engine.stop();
    loop.join(Duration.ofSeconds(5).toMillis());
    assertFalse(loop.isAlive(), "runHeadless should return after stop()");
    assertSame("shutdown", this.log.getLast());
    final double expected = seconds / STEP;
    // real time, roughly: not as fast as possible, and not stalled
    assertTrue(ran > expected * 0.5 && ran < expected * 1.5 + 2, ran + " steps in " + seconds + " s");
  }
}
