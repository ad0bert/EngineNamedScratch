package engine.core;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import engine.render.RenderBackend;
import engine.resources.AssetWatcher;
import engine.resources.ResourceManager;

/**
 * Applies changed asset files while the game runs: shaders are compiled again, textures and meshes loaded again in
 * place, and the game's listeners hear of every changed path (to reload a level, a prefab, a config...). Polled once a
 * frame on the GL thread by {@link Engine}.
 */
final class HotReload {
  private static final Logger log = LoggerFactory.getLogger(HotReload.class);

  private final AssetWatcher watcher;
  private final ResourceManager resources;
  private final RenderBackend backend;
  private final List<Consumer<String>> listeners;

  HotReload(AssetWatcher watcher, ResourceManager resources, RenderBackend backend,
      List<Consumer<String>> listeners) {
    this.watcher = watcher;
    this.resources = resources;
    this.backend = backend;
    this.listeners = listeners;
  }

  static HotReload start(Engine engine, List<Consumer<String>> listeners) {
    final var roots = engine.assets().roots();
    if (roots.isEmpty()) {
      log.warn("Hot reloading is on, but there are no asset directories to watch (Assets.addRoot)");
    }
    try {
      final var watcher = new AssetWatcher(roots);
      log.info("Hot reloading: watching {} directories under {}", watcher.watchedDirectories(), roots);
      return new HotReload(watcher, engine.resources(), engine.backend(), listeners);
    } catch (IOException e) {
      log.error("Cannot watch the asset directories; hot reloading is off", e);
      return null;
    }
  }

  void poll() {
    for (final var path : this.watcher.poll()) {
      apply(path);
    }
  }

  void apply(String path) {
    int changed = this.resources.reload(path);
    if ((path.endsWith(".vert") || path.endsWith(".frag")) && this.backend.reloadShaders(path) == 0) {
      changed++; // a shader that failed was logged by the backend and keeps its old version
    }
    if (changed > 0) {
      log.info("Reloaded {}", path);
    }
    for (final var l : this.listeners) {
      try {
        l.accept(path);
      } catch (RuntimeException e) {
        log.error("Asset listener failed for {}", path, e);
      }
    }
  }

  void close() {
    this.watcher.close();
  }

  static List<Consumer<String>> newListenerList() {
    return new CopyOnWriteArrayList<>();
  }
}
