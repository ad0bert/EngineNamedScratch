package engine.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Per-user directories for a game, following each platform's convention:
 *
 * <table>
 * <tr><th></th><th>config (settings)</th><th>data (saves)</th></tr>
 * <tr><td>Windows</td><td colspan="2">{@code %APPDATA%\<id>}</td></tr>
 * <tr><td>macOS</td><td colspan="2">{@code ~/Library/Application Support/<id>}</td></tr>
 * <tr><td>Linux, BSD</td><td>{@code $XDG_CONFIG_HOME/<id>} or {@code ~/.config/<id>}</td>
 * <td>{@code $XDG_DATA_HOME/<id>} or {@code ~/.local/share/<id>}</td></tr>
 * </table>
 *
 * The directories are created on first use. The system property {@code engine.userDir} puts both under one
 * directory instead (portable installs, tests).
 */
public final class UserDirs {
  private final Path config;
  private final Path data;

  private UserDirs(Path config, Path data) {
    this.config = config;
    this.data = data;
  }

  /** The directories for an application id (see {@link EngineConfig#id()}). */
  public static UserDirs of(String appId) {
    return of(appId, System.getProperty("os.name", ""), System.getProperty("user.home", "."), System.getenv());
  }

  static UserDirs of(String appId, String osName, String home, Map<String, String> env) {
    final var override = System.getProperty("engine.userDir");
    if (override != null) {
      final var base = Path.of(override, appId);
      return new UserDirs(base.resolve("config"), base.resolve("data"));
    }
    final var os = osName.toLowerCase(Locale.ROOT);
    if (os.startsWith("windows")) {
      final var appData = env.getOrDefault("APPDATA", home + "\\AppData\\Roaming");
      final var dir = Path.of(appData, appId);
      return new UserDirs(dir, dir);
    }
    if (os.startsWith("mac")) {
      final var dir = Path.of(home, "Library", "Application Support", appId);
      return new UserDirs(dir, dir);
    }
    final var configHome = env.get("XDG_CONFIG_HOME");
    final var dataHome = env.get("XDG_DATA_HOME");
    return new UserDirs(
        (configHome != null && !configHome.isBlank() ? Path.of(configHome) : Path.of(home, ".config")).resolve(appId),
        (dataHome != null && !dataHome.isBlank() ? Path.of(dataHome) : Path.of(home, ".local", "share"))
            .resolve(appId));
  }

  /** Where settings go (created if missing). */
  public Path config() throws IOException {
    return Files.createDirectories(this.config);
  }

  /** Where saved games and other user data go (created if missing). */
  public Path data() throws IOException {
    return Files.createDirectories(this.data);
  }

  /** The config directory without creating it. */
  public Path configPath() {
    return this.config;
  }

  /** The data directory without creating it. */
  public Path dataPath() {
    return this.data;
  }
}
