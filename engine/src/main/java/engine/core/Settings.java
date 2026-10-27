package engine.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Player settings kept in a properties file, typically {@code UserDirs.of(id).config().resolve("settings.properties")}:
 * typed getters with defaults, and a {@link #save} that never leaves a half-written file. A missing or unreadable file
 * gives empty settings.
 */
@Slf4j
public class Settings {
  private final Path file;
  private final Properties values = new Properties();

  private Settings(Path file) {
    this.file = file;
  }

  public static Settings load(Path file) {
    final var s = new Settings(file);
    try (InputStream in = Files.newInputStream(file)) {
      s.values.load(in);
    } catch (NoSuchFileException e) {
      // first start
    } catch (IOException e) {
      log.warn("Could not read settings {}, using defaults", file, e);
    }
    return s;
  }

  public Path file() {
    return this.file;
  }

  public String get(String key, String fallback) {
    return this.values.getProperty(key, fallback);
  }

  public boolean getBoolean(String key, boolean fallback) {
    final var v = this.values.getProperty(key);
    return v != null ? Boolean.parseBoolean(v.trim()) : fallback;
  }

  public int getInt(String key, int fallback) {
    try {
      final var v = this.values.getProperty(key);
      return v != null ? Integer.parseInt(v.trim()) : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public float getFloat(String key, float fallback) {
    try {
      final var v = this.values.getProperty(key);
      return v != null ? Float.parseFloat(v.trim()) : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public Settings set(String key, Object value) {
    this.values.setProperty(key, String.valueOf(value));
    return this;
  }

  public Settings remove(String key) {
    this.values.remove(key);
    return this;
  }

  /** Writes the settings (to a temporary file first, then moved into place). */
  public void save() throws IOException {
    final var dir = this.file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    final var tmp = Files.createTempFile(dir, this.file.getFileName().toString(), ".tmp");
    try {
      try (OutputStream out = Files.newOutputStream(tmp)) {
        this.values.store(out, null);
      }
      try {
        Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
