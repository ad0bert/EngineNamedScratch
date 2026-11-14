package engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import engine.audio.ImpactSoundPlugin;
import engine.debug.DebugOverlayPlugin;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.input.FirstPersonInputPlugin;
import engine.input.MouseReleasePlugin;
import engine.net.NetConfig;
import engine.serialization.ComponentRegistry;
import engine.serialization.FieldSerializer;

class PluginTest {
  private static final EngineConfig CONFIG = new EngineConfig("Plugin test", "1", 8, 8, 1 / 60.0);

  private static Engine start(List<EnginePlugin> plugins, List<String> log) {
    final var engine = new Engine(CONFIG, new Game() {
      @Override
      public List<EnginePlugin> plugins() {
        return plugins;
      }

      @Override
      public void init(Engine e) {
        log.add("game init");
      }

      @Override
      public void shutdown(Engine e) {
        log.add("game shutdown");
      }
    });
    engine.startHeadless();
    return engine;
  }

  /** A component of a plugin, to be saved with scenes. */
  public static final class Marker extends engine.ecs.Component {
    public int value;
  }

  /** Counts steps with a system of its own and records its life. */
  static final class Counter implements EnginePlugin, ISystem {
    final String name;
    final List<String> log;
    final List<String> needs;
    int steps;

    Counter(String name, List<String> log, String... needs) {
      this.name = name;
      this.log = log;
      this.needs = List.of(needs);
    }

    @Override
    public String id() {
      return this.name;
    }

    @Override
    public List<String> requires() {
      return this.needs;
    }

    @Override
    public void install(Engine engine) {
      this.log.add("install " + this.name);
      engine.addSystem(this);
    }

    @Override
    public void uninstall(Engine engine) {
      this.log.add("uninstall " + this.name);
      engine.removeSystem(this);
    }

    @Override
    public void registerComponents(ComponentRegistry registry) {
      registry.register(new FieldSerializer<>("Marker", Marker.class, ctx -> new Marker()));
    }

    @Override
    public void update(Scene scene, double dt) {
      this.steps++;
    }
  }

  @Test
  void theDefaultsGiveWhatTheEngineUsedToAlwaysDo() {
    final var engine = new Engine(CONFIG, e -> {
    });
    engine.startHeadless();
    final var actions = engine.input().actions();
    assertTrue(actions.contains(MouseReleasePlugin.ACTION));
    assertTrue(actions.contains(DebugOverlayPlugin.ACTION));
    assertTrue(actions.contains("forward") && actions.contains("jump"));
    assertTrue(engine.plugin(DebugOverlayPlugin.class).isPresent());
    assertTrue(engine.hasPlugin("engine.impact-sounds"));
    assertEquals(4, engine.plugins().size());
    assertNotNull(engine.components().forType("ImpactSound"));
    assertNotNull(engine.components().forType("FirstPersonController"));
    engine.stop();
  }

  @Test
  void aGameCanHaveNoneOfThem() {
    final var engine = start(List.of(), new ArrayList<>());
    assertTrue(engine.input().actions().isEmpty(), "no bindings: " + engine.input().actions());
    assertTrue(engine.plugins().isEmpty());
    assertNull(engine.components().forType("ImpactSound"));
    assertNotNull(engine.components().forType("Rigidbody"), "the core is always there");
    engine.runTicks(3);
    engine.stop();
  }

  @Test
  void defaultsCanBeLeftOutOrAddedTo() {
    final var without = EnginePlugins.defaultsWithout(DebugOverlayPlugin.class, FirstPersonInputPlugin.class);
    assertEquals(List.of(MouseReleasePlugin.class, ImpactSoundPlugin.class),
        without.stream().map(Object::getClass).toList());
    final var log = new ArrayList<String>();
    final var more = EnginePlugins.defaultsAnd(new Counter("extra", log));
    assertEquals(5, more.size());
    assertEquals("extra", more.getLast().id());
  }

  @Test
  void pluginsAreInstalledInOrderBeforeTheGameAndRemovedInReverseAfterIt() {
    final var log = new ArrayList<String>();
    final var a = new Counter("a", log);
    final var b = new Counter("b", log, "a");
    final var engine = start(List.of(a, b), log);
    assertEquals(List.of("install a", "install b", "game init"), log);
    engine.runTicks(5);
    assertEquals(5, a.steps);
    assertEquals(5, b.steps);
    assertTrue(engine.plugin(Counter.class).orElseThrow() == a, "the first of a class");
    assertNotNull(engine.components().forType("Marker"), "its components can be saved");
    log.clear();
    engine.stop();
    assertEquals(List.of("game shutdown", "uninstall b", "uninstall a"), log);
    assertTrue(engine.plugins().isEmpty());
  }

  @Test
  void requirementsAndDuplicatesAreChecked() {
    final var log = new ArrayList<String>();
    final var e = assertThrows(IllegalArgumentException.class,
        () -> EnginePlugins.check(List.of(new Counter("b", log, "a"), new Counter("a", log))));
    assertTrue(e.getMessage().contains("needs a"), e.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> EnginePlugins.check(List.of(new Counter("a", log), new Counter("a", log))));
    assertThrows(IllegalArgumentException.class, () -> start(List.of(new Counter("b", log, "a")), log));
    assertTrue(log.isEmpty(), "nothing was installed: " + log);
  }

  @Test
  void networkedPluginsAreInTheProtocol() {
    final EnginePlugin rules = new EnginePlugin() {
      @Override
      public String id() {
        return "game.rules";
      }

      @Override
      public String version() {
        return "2";
      }

      @Override
      public boolean networked() {
        return true;
      }

      @Override
      public void install(Engine engine) {
      }
    };
    final var plain = start(EnginePlugins.defaults(), new ArrayList<>());
    final var withRules = start(EnginePlugins.defaultsAnd(rules), new ArrayList<>());
    assertEquals("", plain.pluginSignature(), "the default plugins do not matter to the other side");
    assertEquals("game.rules@2", withRules.pluginSignature());
    assertEquals(NetConfig.forGame(CONFIG).protocolId(), NetConfig.forGame(plain).protocolId());
    assertNotEquals(NetConfig.forGame(plain).protocolId(), NetConfig.forGame(withRules).protocolId());
    assertFalse(plain.hasPlugin("game.rules"));
    plain.stop();
    withRules.stop();
  }
}
