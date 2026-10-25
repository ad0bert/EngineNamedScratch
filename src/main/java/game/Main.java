package game;

import engine.core.Engine;
import engine.core.EngineConfig;

public final class Main {
  private Main() {
  }

  public static void main(String[] args) throws InterruptedException {
    new Engine(EngineConfig.load("application.properties"), new DemoGame()).run();
  }
}
