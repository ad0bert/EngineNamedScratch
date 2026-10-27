package engine.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Start-up settings, usually from an {@code application.properties} on the classpath:
 *
 * <pre>
 * app.title=My Game
 * app.version=1.0.0
 * app.id=my-game            # names the per-user directories (UserDirs); default: from the title
 * app.sizeX=1280
 * app.sizeY=720
 * app.updatesPerSecond=60
 * app.fullscreen=false      # borderless fullscreen on the main monitor
 * app.vsync=true
 * </pre>
 *
 * The system properties {@code engine.fullscreen} and {@code engine.vsync} override the file.
 */
@Slf4j
public record EngineConfig(String title, String version, String id, int width, int height, double updateStep,
    boolean fullscreen, boolean vsync) {

  /** Windowed, with vsync, and an id derived from the title. */
  public EngineConfig(String title, String version, int width, int height, double updateStep) {
    this(title, version, idOf(title), width, height, updateStep, false, true);
  }

  public EngineConfig withFullscreen(boolean value) {
    return new EngineConfig(this.title, this.version, this.id, this.width, this.height, this.updateStep, value,
        this.vsync);
  }

  public EngineConfig withVsync(boolean value) {
    return new EngineConfig(this.title, this.version, this.id, this.width, this.height, this.updateStep,
        this.fullscreen, value);
  }

  public EngineConfig withSize(int w, int h) {
    return new EngineConfig(this.title, this.version, this.id, w, h, this.updateStep, this.fullscreen, this.vsync);
  }

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
    return of(p);
  }

  /** A config from properties (see the class comment); missing keys get defaults. */
  public static EngineConfig of(Properties p) {
    final var title = p.getProperty("app.title", "Engine");
    return new EngineConfig(title, p.getProperty("app.version", "0.0.0"), p.getProperty("app.id", idOf(title)),
        Integer.parseInt(p.getProperty("app.sizeX", "800")), Integer.parseInt(p.getProperty("app.sizeY", "600")),
        1.0 / Double.parseDouble(p.getProperty("app.updatesPerSecond", "60")),
        Boolean.parseBoolean(System.getProperty("engine.fullscreen", p.getProperty("app.fullscreen", "false"))),
        Boolean.parseBoolean(System.getProperty("engine.vsync", p.getProperty("app.vsync", "true"))));
  }

  /** A file-name friendly id: {@code "My Game!"} becomes {@code "my-game"}. */
  static String idOf(String title) {
    final var id = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return id.isEmpty() ? "game" : id;
  }
}
