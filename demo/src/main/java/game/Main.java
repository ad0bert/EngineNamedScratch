package game;

import java.nio.file.Path;

import engine.core.Engine;
import engine.core.EngineConfig;

public final class Main {
  private Main() {
  }

  public static void main(String[] args) throws InterruptedException {
    final var engine = new Engine(EngineConfig.load("application.properties"), new DemoGame());
    // -Ddemo.assets=dir: files there replace the demo's (and the engine's shaders, e.g. dir/shaders/tonemap.frag);
    // with -Dengine.hotReload=true they are applied while it runs
    final var assets = System.getProperty("demo.assets");
    if (assets != null) {
      engine.assets().addRoot(Path.of(assets));
    }
    engine.run();
  }
}
