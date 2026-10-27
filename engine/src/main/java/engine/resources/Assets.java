package engine.resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where asset paths such as {@code textures/stone.png} are looked up: in the directories added with
 * {@link #addRoot} (the last added first), then on the classpath. A game ships its assets in its jar and can let
 * players or mods override any of them with files, e.g. {@code assets.addRoot(Path.of("mods/hd-textures"))}.
 *
 * <p>
 * Paths always use {@code /} and are relative; a path that would leave a root ({@code ../secret}) is not looked up
 * there. Thread-safe.
 */
public class Assets {
  private final List<Path> roots = new CopyOnWriteArrayList<>();
  private final ClassLoader classLoader;

  /** Only the classpath of the engine's class loader until roots are added. */
  public Assets() {
    this(Assets.class.getClassLoader());
  }

  public Assets(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /** Searches {@code dir} before the classpath and before the roots added earlier. */
  public Assets addRoot(Path dir) {
    this.roots.addFirst(dir.toAbsolutePath().normalize());
    return this;
  }

  public void removeRoot(Path dir) {
    this.roots.remove(dir.toAbsolutePath().normalize());
  }

  /** The directories searched before the classpath, in search order. */
  public List<Path> roots() {
    return List.copyOf(this.roots);
  }

  /**
   * Opens an asset; the caller closes the stream.
   *
   * @throws IOException if no root and nothing on the classpath has it
   */
  public InputStream open(String path) throws IOException {
    final var file = file(path);
    if (file != null) {
      return Files.newInputStream(file);
    }
    final var in = this.classLoader.getResourceAsStream(stripSlash(path));
    if (in == null) {
      throw new IOException("Asset not found: " + path);
    }
    return in;
  }

  public byte[] readAllBytes(String path) throws IOException {
    try (InputStream in = open(path)) {
      return in.readAllBytes();
    }
  }

  public String readString(String path) throws IOException {
    return new String(readAllBytes(path), StandardCharsets.UTF_8);
  }

  public boolean exists(String path) {
    return file(path) != null || this.classLoader.getResource(stripSlash(path)) != null;
  }

  /** The file a root holds for this path, or null if it comes from the classpath (or nowhere). */
  public Path file(String path) {
    final var relative = stripSlash(path);
    for (Path root : this.roots) {
      final var candidate = root.resolve(relative).normalize();
      if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static String stripSlash(String path) {
    return path.startsWith("/") ? path.substring(1) : path;
  }
}
