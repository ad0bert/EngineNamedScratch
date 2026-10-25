package engine.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record EngineConfig(String title, String version, int width, int height, double updateStep) {

  public static EngineConfig load(String resource) {
    final var p = new Properties();
    try (InputStream in = EngineConfig.class.getClassLoader().getResourceAsStream(resource)) {
      if (in != null) {
        p.load(in);
      } else {
        log.warn("Config {} not found, using defaults", resource);
      }
    } catch (IOException e) {
      log.warn("Could not read config {}, using defaults", resource, e);
    }
    return new EngineConfig(p.getProperty("app.title", "Engine"), p.getProperty("app.version", "0.0.0"),
        Integer.parseInt(p.getProperty("app.sizeX", "800")), Integer.parseInt(p.getProperty("app.sizeY", "600")),
        1.0 / Double.parseDouble(p.getProperty("app.updatesPerSecond", "60")));
  }
}
