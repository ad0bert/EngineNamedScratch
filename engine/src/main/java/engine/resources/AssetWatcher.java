package engine.resources;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notices files created or changed under asset directories, for hot reloading. {@link #poll} (called once a frame,
 * it never blocks) returns the asset paths (relative to their directory, with forward slashes: the paths
 * {@link Assets} and {@link ResourceManager} take) that changed and then stayed untouched for {@link #settleMillis},
 * each once, so a file an editor writes in several steps is reported when it is complete. Subdirectories are watched
 * too, including ones created later. Uses the platform's file notifications ({@link WatchService}); on systems
 * without them (macOS) the JDK polls, and changes show up after a few seconds.
 */
public final class AssetWatcher implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AssetWatcher.class);

  private final WatchService service;
  /** Per registered directory: the root it belongs to. */
  private final Map<WatchKey, Path[]> directories = new HashMap<>();
  /** Changed paths and when they last changed (nanoTime), oldest first. */
  private final Map<String, Long> pending = new LinkedHashMap<>();
  /** How long a file must stay unchanged before it is reported. */
  public long settleMillis = 150;

  public AssetWatcher(List<Path> roots) throws IOException {
    this.service = FileSystems.getDefault().newWatchService();
    for (final var root : roots) {
      if (Files.isDirectory(root)) {
        registerTree(root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize());
      } else {
        log.warn("Not watching {}: not a directory", root);
      }
    }
  }

  private void registerTree(Path root, Path dir) throws IOException {
    try (Stream<Path> dirs = Files.walk(dir)) {
      for (final var d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
        final var key = d.register(this.service, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        this.directories.put(key, new Path[] { d, root });
      }
    }
  }

  /** Number of directories watched. */
  public int watchedDirectories() {
    return this.directories.size();
  }

  /** The asset paths that changed and have settled since the last call; never blocks. */
  public List<String> poll() {
    collect();
    final List<String> out = new ArrayList<>();
    final long settled = System.nanoTime() - this.settleMillis * 1_000_000L;
    for (final Iterator<Map.Entry<String, Long>> it = this.pending.entrySet().iterator(); it.hasNext();) {
      final var e = it.next();
      if (e.getValue() <= settled) {
        out.add(e.getKey());
        it.remove();
      }
    }
    return out;
  }

  private void collect() {
    WatchKey key;
    try {
      while ((key = this.service.poll()) != null) {
        final var dir = this.directories.get(key);
        for (final WatchEvent<?> event : key.pollEvents()) {
          if (dir == null || event.kind() == StandardWatchEventKinds.OVERFLOW) {
            continue;
          }
          final var file = dir[0].resolve((Path) event.context());
          if (Files.isDirectory(file)) {
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
              try {
                registerTree(dir[1], file); // and report what was copied into it already
                try (Stream<Path> files = Files.walk(file)) {
                  files.filter(Files::isRegularFile).forEach(f -> touch(dir[1], f));
                }
              } catch (IOException e) {
                log.warn("Cannot watch {}", file, e);
              }
            }
            continue;
          }
          touch(dir[1], file);
        }
        if (!key.reset()) {
          this.directories.remove(key); // the directory is gone
        }
      }
    } catch (ClosedWatchServiceException e) {
      // closed: nothing more to report
    }
  }

  private void touch(Path root, Path file) {
    final var path = root.relativize(file).toString().replace('\\', '/');
    this.pending.remove(path); // keep the map ordered by the last change
    this.pending.put(path, System.nanoTime());
  }

  @Override
  public void close() {
    try {
      this.service.close();
    } catch (IOException e) {
      log.debug("Closing the watch service", e);
    }
  }
}
