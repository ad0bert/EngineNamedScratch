package engine.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.joml.Vector3f;

import com.jogamp.newt.MonitorMode;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.MouseAdapter;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode;
import com.jogamp.opengl.DebugGL3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.Animator;

import engine.animation.AnimationSystem;
import engine.audio.AudioSystem;
import engine.audio.NullAudioBackend;
import engine.audio.al.AlAudioBackend;
import engine.debug.DebugOverlay;
import engine.debug.Profiler;
import engine.ecs.ComponentUpdateSystem;
import engine.ecs.Entity;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.input.FirstPersonController;
import engine.input.GamepadBackend;
import engine.input.InputListener;
import engine.input.InputState;
import engine.input.jinput.JInputGamepads;
import engine.messaging.IMessageService;
import engine.messaging.MessageQueue;
import engine.messaging.MessageService;
import engine.physics.PhysicsSystem;
import engine.physics.RayHit;
import engine.render.NullRenderBackend;
import engine.render.Overlay;
import engine.render.RenderBackend;
import engine.render.gl.GlBackend;
import engine.resources.Assets;
import engine.resources.ResourceManager;
import engine.serialization.SerializationContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the window, the GL loop and all subsystems. Everything (update, messages, rendering) runs on the animator's GL
 * thread, so scene state needs no locking; only input and window events arrive from other threads and go through
 * {@link InputState} and {@link IMessageService#post}.
 *
 * <p>
 * The window and loop are the JOGL platform layer; drawing goes through {@link RenderBackend}. Rendering is
 * interpolated between the last two fixed updates, so motion stays smooth at any frame rate.
 *
 * <p>
 * System properties: {@code engine.debugGL=true} wraps GL in {@link DebugGL3}; {@code engine.exitAfterFrames=N} closes
 * the window after N rendered frames (smoke tests); {@code engine.captureMouse=false} starts with a free pointer;
 * {@code engine.screenshot=file.png} saves the last frame before exiting (with {@code exitAfterFrames}) or, with
 * {@code engine.screenshotFrame=N}, frame N. A screenshot can also be requested with {@link #saveScreenshot}.
 * {@code engine.audio=false} turns sound off; without a sound device it is off anyway (the engine never fails to
 * start because of sound). {@code engine.gamepads=false} skips looking for gamepads. {@code engine.fullscreen} and
 * {@code engine.vsync} override the config. {@code engine.debugOverlay=false} starts with the debug text (F3)
 * hidden.
 *
 * <p>
 * A game either builds everything in {@link Game#init}, or pushes {@link GameState}s (title screen, levels, pause
 * menu) onto {@code states()}.
 *
 * <p>
 * Without a window ({@link #runHeadless}, {@link #startHeadless}, or {@code -Dengine.headless=true} for {@link #run})
 * the engine runs the same steps, systems, game and game states, with nothing drawn, no sound and no devices: for
 * servers, tools and tests. Every step has a number ({@link #tick()}), and {@link StepHook}s run at fixed points of
 * it.
 */
@Slf4j
public class Engine {
  @Getter private final EngineConfig config;
  private final Game game;
  /** The engine library's version (from the jar it was built into), e.g. {@code 0.8.0}. */
  public static final String VERSION = readVersion();

  @Getter private final Scene scene = new Scene();
  @Getter private final IMessageService messages = new MessageService();
  @Getter private final InputState input = new InputState();
  private final InputListener inputListener = new InputListener(this.messages, this.input);
  // copy-on-write: systems and overlays may add or remove others while the engine iterates them
  private final List<ISystem> systems = new CopyOnWriteArrayList<>();
  private final List<Overlay> overlays = new CopyOnWriteArrayList<>();
  private final List<StepHook> hooks = new CopyOnWriteArrayList<>();
  /** The overlays and, last, the game states' drawing. */
  private volatile List<Overlay> drawn = List.of();
  /** The game's screens and modes (title, levels, pause menu). */
  @Getter private final GameStates states = new GameStates(this);
  private final Overlay statesOverlay = this.states.overlay();
  /** Where resources are looked up: add directories to load assets from disk (mods, user content). */
  @Getter private final Assets assets = new Assets();
  /** Per-user directories for settings and saves, named after {@link EngineConfig#id()}. */
  @Getter private final UserDirs userDirs;
  private volatile boolean paused;
  private volatile boolean debugOverlay = Boolean.parseBoolean(System.getProperty("engine.debugOverlay", "true"));
  private volatile boolean vsync;
  private volatile boolean fullscreen;
  private volatile int startWidth;
  private volatile int startHeight;
  private boolean appliedVsync;
  private final FixedTimestep timestep;
  @Getter private final PhysicsSystem physics = new PhysicsSystem(this.messages);
  /** Where the frame time goes: each system, the game's update, rendering (shown by the F3 overlay). */
  @Getter private final Profiler profiler = new Profiler();
  private final Map<ISystem, Integer> systemSections = new IdentityHashMap<>();
  private final int gameSection = this.profiler.section("Game");
  private final int renderSection = this.profiler.section("Render");
  private final int reloadSection = this.profiler.section("Hot reload");
  private int hookSection = -1; // made when the first hook is added
  /** The number of the step being run, or of the last one run; 0 before the first. */
  private long tick;
  private volatile boolean headless;
  /** True while {@link #runHeadless} drives the steps (rather than {@link #runTicks}). */
  private volatile boolean headlessLoop;
  private volatile Thread headlessThread;
  /** Inside setup or a step: a stop() there is carried out afterwards. */
  private boolean busy;
  private boolean shutDown;
  private HotReload hotReload;
  private boolean hotReloadWanted = Boolean.getBoolean("engine.hotReload");
  private final List<Consumer<String>> assetListeners = HotReload.newListenerList();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final AtomicBoolean audioClosed = new AtomicBoolean();
  private final int exitAfterFrames = Integer.getInteger("engine.exitAfterFrames", 0);
  private volatile BooleanSupplier clickCaptures = () -> true;
  private volatile Path screenshot = System.getProperty("engine.screenshot") != null
      ? Path.of(System.getProperty("engine.screenshot"))
      : null;
  private final long screenshotFrame = Long.getLong("engine.screenshotFrame", 0L);

  private GLWindow window;
  private Animator animator;
  /** Held while the window is being changed (fullscreen, size), so that changes do not overlap. */
  private final Object windowChange = new Object();
  private volatile GamepadBackend gamepads = GamepadBackend.NONE;
  @Getter private RenderBackend backend;
  @Getter private ResourceManager resources;
  /** Sound: sources, one-shots, mixer volumes; silent if there is no sound device. */
  @Getter private AudioSystem audio;

  @Getter private volatile double fps;
  private long frameCount;
  private long fpsWindowStart;
  private long fpsWindowFrames;

  public Engine(EngineConfig config, Game game) {
    this.config = config;
    this.game = game;
    this.timestep = new FixedTimestep(config.updateStep(), 0.25);
    this.userDirs = UserDirs.of(config.id());
    this.vsync = config.vsync();
    this.fullscreen = config.fullscreen();
    this.startWidth = config.width();
    this.startHeight = config.height();
    this.drawn = List.of(this.statesOverlay);
  }

  private static String readVersion() {
    try (InputStream in = Engine.class.getResourceAsStream("version.properties")) {
      if (in != null) {
        final var p = new Properties();
        p.load(in);
        return p.getProperty("version", "unknown");
      }
    } catch (IOException e) {
      // fall through
    }
    return "unknown";
  }

  /** Nearest solid (non-trigger) collider hit by the ray, see {@link PhysicsSystem#raycast}. */
  public Optional<RayHit> raycast(Vector3f origin, Vector3f direction, float maxDistance) {
    return this.physics.raycast(this.scene, origin, direction, maxDistance);
  }

  public boolean isMouseCaptured() {
    return this.inputListener.isCaptured();
  }

  /**
   * Decides whether a click into the window captures the mouse (the default: always). A game with a user interface
   * passes something like {@code () -> !gui.wantsMouse()}, so clicks on widgets stay with the widgets. Called on the
   * window thread.
   */
  public void setClickCapturesMouse(BooleanSupplier when) {
    this.clickCaptures = when;
  }

  /** A context for saving or loading scenes and prefabs with this engine's resources and input. */
  public SerializationContext serialization() {
    return new SerializationContext(this.resources, this.input);
  }

  /** Looks for newly plugged-in gamepads at the next update (also done whenever the window regains focus). */
  public void rescanGamepads() {
    this.gamepads.rescan();
  }

  public void addSystem(ISystem system) {
    this.systems.add(system);
  }

  public void removeSystem(ISystem system) {
    this.systems.remove(system);
  }

  /** Runs {@code hook} in every step from the next one on, after the hooks added before it; see {@link StepHook}. */
  public void addStepHook(StepHook hook) {
    if (this.hookSection < 0) {
      this.hookSection = this.profiler.section("Step hooks");
    }
    this.hooks.add(hook);
  }

  public void removeStepHook(StepHook hook) {
    this.hooks.remove(hook);
  }

  /**
   * The number of the update step being run (inside a step), or of the last one (between steps): 1 in the first step,
   * then one more per step, also while paused. Steps are {@link EngineConfig#updateStep()} seconds long, so this is the
   * simulation's clock; networking uses it to say which step an input or a state belongs to.
   */
  public long tick() {
    return this.tick;
  }

  /**
   * How fast wall time turns into steps: 1 (the default) is real time. Each step still lasts exactly
   * {@link EngineConfig#updateStep()} of simulated time; a scale of 1.01 only runs steps 1 % more often. Meant for small
   * corrections, such as a networked client keeping its {@link #tick()} in line with the server's.
   */
  public void setTimeScale(double scale) {
    this.timestep.setTimeScale(scale);
  }

  public double timeScale() {
    return this.timestep.timeScale();
  }

  /** Whether the engine runs without a window ({@link #runHeadless} or {@link #startHeadless}). */
  public boolean isHeadless() {
    return this.headless;
  }

  /**
   * Starts the engine without a window, on the calling thread, and returns: the render backend draws nothing
   * ({@link NullRenderBackend}), sound is silent and no gamepads are looked for, but resources, physics, systems, the
   * game's {@link Game#init} and game states are set up as with {@link #run}. Then call {@link #runTicks} to run steps
   * (tests, tools, simulations faster than real time), and {@link #stop()} on the same thread when done. All of it
   * belongs to the thread that called this.
   */
  public void startHeadless() {
    if (this.headless || this.window != null) {
      throw new IllegalStateException("the engine has already been started");
    }
    this.headless = true;
    System.setProperty("java.awt.headless", "true");
    this.busy = true;
    try {
      setUp(new NullRenderBackend(), false);
    } finally {
      this.busy = false;
    }
    if (this.stopping.get() && !this.headlessLoop) {
      shutDown(); // the game stopped in its init
    }
  }

  /**
   * Runs {@code count} update steps right away, without waiting for wall time to pass (after {@link #startHeadless}).
   * Stops early if a step calls {@link #stop()}.
   */
  public void runTicks(int count) {
    if (!this.headless || this.headlessLoop) {
      throw new IllegalStateException("runTicks needs an engine started with startHeadless()");
    }
    if (this.shutDown) {
      throw new IllegalStateException("the engine has been stopped");
    }
    for (int i = 0; i < count && !this.stopping.get(); i++) {
      step(this.timestep.step());
      this.scene.flush();
    }
    if (this.stopping.get()) {
      shutDown();
    }
  }

  /**
   * Runs the engine without a window on the calling thread, in real time ({@link EngineConfig#updateStep()} per step,
   * scaled by {@link #setTimeScale}), and blocks until {@link #stop()} is called (from any thread, also from the game)
   * or the thread is interrupted. Everything else is as with {@link #startHeadless}. With
   * {@code -Dengine.exitAfterFrames=N} it stops after N steps.
   */
  public void runHeadless() {
    if (this.headless || this.window != null) {
      throw new IllegalStateException("the engine has already been started");
    }
    this.headlessThread = Thread.currentThread();
    this.headlessLoop = true;
    startHeadless();
    try {
      while (!this.stopping.get() && !Thread.currentThread().isInterrupted()) {
        this.timestep.advance(System.nanoTime(), this::step);
        this.scene.flush();
        if (this.exitAfterFrames > 0 && this.tick >= this.exitAfterFrames) {
          break;
        }
        if (!this.stopping.get()) {
          LockSupport.parkNanos(Math.max(100_000L, this.timestep.nanosUntilNextStep()));
        }
      }
    } finally {
      this.stopping.set(true);
      shutDown();
      log.info("Engine stopped after {} steps", this.tick);
    }
  }

  /** Adds 2D content drawn over the scene every frame, in registration order (the game states draw last). */
  public void addOverlay(Overlay overlay) {
    this.overlays.add(overlay);
    updateDrawn();
  }

  public void removeOverlay(Overlay overlay) {
    this.overlays.remove(overlay);
    updateDrawn();
  }

  private void updateDrawn() {
    final List<Overlay> list = new ArrayList<>(this.overlays);
    list.add(this.statesOverlay);
    this.drawn = List.copyOf(list);
  }

  /**
   * Shows or hides the debug text (FPS, entities, draw statistics; F3 toggles it). Visible by default, unless
   * {@code -Dengine.debugOverlay=false}; a game for players usually hides it in {@link Game#init}. Safe to call from
   * any thread, also before {@link #run}.
   */
  public void setDebugOverlayVisible(boolean value) {
    this.debugOverlay = value;
  }

  public boolean isDebugOverlayVisible() {
    return this.debugOverlay;
  }

  /**
   * Stops (or resumes) the simulation: systems other than those that {@link ISystem#runsWhilePaused run while
   * paused} skip their updates, so physics, animation and component updates stand still while the game and its states
   * still get updates and draw. The top game state can also pause the simulation (see {@link GameState#simulates}).
   */
  public void setPaused(boolean value) {
    this.paused = value;
  }

  /** Whether the scene is standing still, by {@link #setPaused} or because of the top game state. */
  public boolean isPaused() {
    return this.paused || !this.states.simulates();
  }

  /**
   * Switches between a window and borderless fullscreen on the window's monitor. Safe to call from any thread, also
   * before {@link #run}; the window changes shortly after.
   */
  public void setFullscreen(boolean value) {
    this.fullscreen = value;
    final var w = this.window;
    if (w != null) {
      new Thread(() -> changeWindow("fullscreen", () -> w.setFullscreen(value)), "engine-fullscreen").start();
    }
  }

  /**
   * Changes the window with the drawing paused. The animator holds the window's lock while it draws a frame and takes
   * it again right after; a change that needs the lock (fullscreen, size) could otherwise wait for it until it times
   * out, as it did on Windows with vsync ("Waited 5000ms for ... owner main-Animator"). One change at a time; a change
   * that fails anyway is logged, and the game goes on in the window it has.
   */
  private void changeWindow(String what, Runnable change) {
    synchronized (this.windowChange) {
      final var a = this.animator;
      final boolean pause = a != null && a.isStarted() && !a.isPaused();
      if (pause) {
        a.pause(); // returns once the animator is between frames and has let go of the window
      }
      try {
        change.run();
      } catch (RuntimeException e) {
        log.warn("Could not change the window ({}): {}", what, e.toString());
      } finally {
        if (pause) {
          a.resume();
        }
      }
    }
  }

  /** The fullscreen state last asked for (the window may take a moment to follow). */
  public boolean isFullscreen() {
    return this.fullscreen;
  }

  /** Waits for the display's refresh between frames (no tearing; frame rate capped at the refresh rate). */
  public void setVsync(boolean value) {
    this.vsync = value;
  }

  public boolean isVsync() {
    return this.vsync;
  }

  /** Resizes the window (its size when not fullscreen). Safe to call from any thread, also before {@link #run}. */
  public void setWindowSize(int width, int height) {
    this.startWidth = width;
    this.startHeight = height;
    final var w = this.window;
    if (w != null) {
      new Thread(() -> changeWindow("size", () -> w.setSize(width, height)), "engine-resize").start();
    }
  }

  /** The width of the drawing area in pixels. */
  public int width() {
    return this.window != null ? this.window.getSurfaceWidth() : this.startWidth;
  }

  public int height() {
    return this.window != null ? this.window.getSurfaceHeight() : this.startHeight;
  }

  /**
   * The resolutions the window's monitor supports, largest first, as {@code {width, height}}; useful for a settings
   * menu's window size choices. Empty before {@link #run} opened the window.
   */
  public List<int[]> monitorResolutions() {
    if (this.window == null) {
      return List.of();
    }
    final var sizes = new TreeSet<int[]>((a, b) -> a[0] != b[0] ? b[0] - a[0] : b[1] - a[1]);
    for (MonitorMode mode : this.window.getMainMonitor().getSupportedModes()) {
      final var res = mode.getSurfaceSize().getResolution();
      sizes.add(new int[] { res.getWidth(), res.getHeight() });
    }
    return List.copyOf(sizes);
  }

  public void setTitle(String title) {
    if (this.window != null) {
      this.window.setTitle(title);
    }
  }

  /** Opens the window and blocks until it is closed; with {@code -Dengine.headless=true}, {@link #runHeadless}. */
  public void run() throws InterruptedException {
    if (Boolean.getBoolean("engine.headless")) {
      runHeadless();
      return;
    }
    if (this.headless || this.window != null) {
      throw new IllegalStateException("the engine has already been started");
    }
    System.setProperty("java.awt.headless", "true"); // ImageIO/Java2D only; keeps macOS from showing an AWT app
    final var caps = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    caps.setDepthBits(24);
    this.window = GLWindow.create(caps);
    this.window.setTitle(this.config.title());
    this.window.setSize(this.startWidth, this.startHeight);
    this.window.setFullscreen(this.fullscreen);
    this.window.setDefaultCloseOperation(WindowClosingMode.DISPOSE_ON_CLOSE);
    this.window.addKeyListener(this.inputListener);
    this.window.addMouseListener(this.inputListener);
    this.inputListener.setWarp(this.window::warpPointer);
    this.window.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (!Engine.this.inputListener.isCaptured() && Engine.this.clickCaptures.getAsBoolean()) {
          captureMouse(true);
        }
      }
    });
    this.window.addWindowListener(new WindowAdapter() {
      @Override
      public void windowGainedFocus(WindowEvent e) {
        Engine.this.gamepads.rescan();
      }

      @Override
      public void windowLostFocus(WindowEvent e) {
        Engine.this.input.releaseAll();
        captureMouse(false);
      }

      @Override
      public void windowDestroyed(WindowEvent e) {
        Engine.this.animator.stop();
        Engine.this.closed.countDown();
      }
    });
    this.window.addGLEventListener(new Loop());

    this.animator = new Animator();
    this.animator.add(this.window);
    this.animator.start();
    this.window.setVisible(true);
    this.closed.await();
    closeAudio(); // the window may have been closed by the user rather than by stop()
    log.info("Engine stopped after {} frames", this.frameCount);
  }

  /** Saves the next rendered frame as a PNG; safe to call from any thread. */
  public void saveScreenshot(Path file) {
    this.screenshot = file;
  }

  private void captureIfRequested(int width, int height) {
    final var file = this.screenshot;
    if (file == null) {
      return;
    }
    final boolean due = this.screenshotFrame > 0 ? this.frameCount == this.screenshotFrame
        : this.exitAfterFrames <= 0 || this.frameCount >= this.exitAfterFrames;
    if (!due) {
      return;
    }
    this.screenshot = null;
    try {
      ImageIO.write(this.backend.readPixels(width, height).toBufferedImage(), "png", file.toFile());
      log.info("Saved screenshot {}", file.toAbsolutePath());
    } catch (IOException | RuntimeException e) {
      log.error("Could not save screenshot {}", file, e);
    }
  }

  /**
   * Requests shutdown; safe to call from any thread, and more than once (only the first call counts). The animator is
   * stopped before the window is destroyed, so no frame is drawn while the window goes away; the sound device is
   * closed last, after the window's dispose has released the clips. Without a window, {@link #runHeadless} returns
   * after the current step; an engine driven by {@link #runTicks} shuts down right away (call it on that thread).
   */
  public void stop() {
    if (this.headless) {
      if (this.stopping.compareAndSet(false, true)) {
        final var loop = this.headlessThread;
        if (loop != null) {
          LockSupport.unpark(loop); // runHeadless shuts down on its own thread
        } else if (!this.busy) {
          shutDown(); // started with startHeadless: this is its thread; inside a step, runTicks does it after
        }
      }
      return;
    }
    if (this.stopping.compareAndSet(false, true)) {
      new Thread(() -> {
        this.animator.stop();
        this.window.destroy();
        closeAudio();
      }, "engine-stop").start();
    }
  }

  /** Headless: what the window's dispose does, then the sound. */
  private void shutDown() {
    if (this.shutDown) {
      return;
    }
    this.shutDown = true;
    try {
      tearDown();
    } finally {
      closeAudio();
      this.closed.countDown();
    }
  }

  private void closeAudio() {
    if (this.audio != null && this.audioClosed.compareAndSet(false, true)) {
      this.audio.dispose();
    }
  }

  public void captureMouse(boolean capture) {
    if (this.window == null) {
      return; // headless, or not open yet
    }
    this.inputListener.setCaptured(capture, this.window.getSurfaceWidth(), this.window.getSurfaceHeight());
    this.window.setPointerVisible(!capture);
    this.window.confinePointer(capture);
  }

  private void step(double dt) {
    this.busy = true;
    try {
      runStep(dt);
    } finally {
      this.busy = false;
    }
  }

  private void runStep(double dt) {
    final long now = ++this.tick;
    this.states.applyPending();
    this.scene.flush();
    for (Entity e : this.scene.entities()) {
      e.transform().snapshot(); // previous state for render interpolation
    }
    this.messages.dispatchPending();
    this.gamepads.poll(this.input);
    if (this.input.wasActionPressed("release_mouse")) {
      captureMouse(false);
    }
    runHooks(now, dt, true);
    final boolean simulate = !isPaused();
    for (ISystem s : this.systems) {
      if (simulate || s.runsWhilePaused()) {
        this.profiler.begin(this.systemSections.computeIfAbsent(s, this::sectionName));
        try {
          s.update(this.scene, dt);
        } finally {
          this.profiler.end();
        }
      }
    }
    this.profiler.begin(this.gameSection);
    try {
      this.game.update(this, dt);
      this.states.update(dt);
    } finally {
      this.profiler.end();
    }
    runHooks(now, dt, false);
    this.input.endFrame();
  }

  private void runHooks(long now, double dt, boolean before) {
    if (this.hooks.isEmpty()) {
      return;
    }
    this.profiler.begin(this.hookSection);
    try {
      for (StepHook h : this.hooks) {
        if (before) {
          h.beforeSystems(this, now, dt);
        } else {
          h.afterStep(this, now, dt);
        }
      }
    } finally {
      this.profiler.end();
    }
  }

  /** "PhysicsSystem" becomes "Physics", anonymous systems keep their enclosing class's name. */
  private int sectionName(ISystem system) {
    String name = system.getClass().getSimpleName();
    if (name.isEmpty()) {
      name = system.getClass().getName().replaceAll(".*\\.", "").replaceAll("\\$\\d+$", "");
    }
    if (name.endsWith("System") && name.length() > "System".length()) {
      name = name.substring(0, name.length() - "System".length());
    }
    return this.profiler.section(name);
  }

  /**
   * Sound occlusion: a physics ray from the listener towards the sound, stopping short of it, that hits something
   * solid other than the listener's and the sound's own colliders (or their parents', like the player's capsule
   * around a head with the listener).
   */
  private boolean occluded(Vector3f from, Vector3f to, Entity listener, Entity source) {
    final var dir = new Vector3f(to).sub(from);
    final float dist = dir.length();
    if (dist < 0.5f) {
      return false;
    }
    return this.physics.raycast(this.scene, from, dir, dist - 0.25f,
        c -> !c.trigger && !holds(c.entity(), listener) && !holds(c.entity(), source)).isPresent();
  }

  /** True if {@code e} is {@code other} or one of its ancestors. */
  private static boolean holds(Entity e, Entity other) {
    if (other == null) {
      return false;
    }
    for (var t = other.transform(); t != null; t = t.parent()) {
      if (t == e.transform()) {
        return true;
      }
    }
    return false;
  }

  /** A shader file in an asset directory replaces the built-in one of the same path (e.g. shaders/basic.frag). */
  private String shaderFromAssets(String path) {
    final var file = this.assets.file(path);
    if (file == null) {
      return null;
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      log.error("Cannot read shader {}", file, e);
      return null;
    }
  }

  /**
   * Watches the asset directories ({@link Assets#addRoot}) and applies changed files while the game runs: shaders
   * are compiled again, textures and OBJ meshes loaded again in place, and {@link #onAssetChanged} listeners called
   * (also {@code -Dengine.hotReload=true}). Call on the GL thread (from the game) or before {@link #run()}.
   */
  public void setHotReload(boolean on) {
    this.hotReloadWanted = on;
    if (this.resources == null) {
      return; // started with the engine
    }
    if (on && this.hotReload == null) {
      this.hotReload = HotReload.start(this, this.assetListeners);
    } else if (!on && this.hotReload != null) {
      this.hotReload.close();
      this.hotReload = null;
    }
  }

  public boolean isHotReload() {
    return this.hotReload != null;
  }

  /**
   * Calls {@code listener} on the GL thread with the asset path (relative to its directory, e.g.
   * {@code levels/one.json}) of every file that changed while hot reloading is on, after the engine reloaded what
   * it knows how to.
   */
  public void onAssetChanged(Consumer<String> listener) {
    this.assetListeners.add(listener);
  }

  private void trackFps() {
    final long now = System.nanoTime();
    this.fpsWindowFrames++;
    final long elapsed = now - this.fpsWindowStart;
    if (elapsed >= 500_000_000L) {
      this.fps = this.fpsWindowFrames * 1e9 / elapsed;
      this.fpsWindowStart = now;
      this.fpsWindowFrames = 0;
    }
  }

  private void applyVsync(GL3 gl) {
    final boolean value = this.vsync;
    gl.setSwapInterval(value ? 1 : 0);
    this.appliedVsync = value;
  }

  /**
   * Everything after the graphics context exists: sound, resources, gamepads, the engine's systems, then the game.
   * {@code devices} false (headless) leaves out the sound device and gamepads.
   */
  private void setUp(RenderBackend renderer, boolean devices) {
    this.backend = renderer;
    this.backend.setShaderSource(this::shaderFromAssets);
    this.backend.init();
    final var sound = devices && Boolean.parseBoolean(System.getProperty("engine.audio", "true"))
        ? AlAudioBackend.open(32)
        : null;
    this.audio = new AudioSystem(sound != null ? sound : new NullAudioBackend());
    this.audio.setOcclusionTest(this::occluded);
    this.resources = new ResourceManager(this.backend, this.audio.backend(), this.assets);
    if (devices && Boolean.parseBoolean(System.getProperty("engine.gamepads", "true"))) {
      final var pads = JInputGamepads.open(this.input);
      this.gamepads = pads != null ? pads : GamepadBackend.NONE;
    }
    this.input.bind("release_mouse", KeyEvent.VK_ESCAPE);
    FirstPersonController.bindDefaults(this.input);
    addSystem(new ComponentUpdateSystem());
    addSystem(new AnimationSystem()); // before physics: animated kinematic bodies push others
    addSystem(this.physics);
    addSystem(this.audio); // after physics: sounds follow the bodies' new positions
    this.messages.register(this.audio, MessageQueue.COLLISION);
    final var debug = new DebugOverlay(this);
    addSystem(debug);
    addOverlay(debug);
    this.game.init(this);
    this.states.applyPending();
    this.scene.flush();
    if (this.hotReloadWanted) {
      this.hotReload = HotReload.start(this, this.assetListeners);
    }
    this.fpsWindowStart = System.nanoTime();
  }

  /** The game states, the game, then the engine's resources; the sound device is closed separately. */
  private void tearDown() {
    this.states.exitAll();
    this.game.shutdown(this);
    this.gamepads.dispose();
    if (this.hotReload != null) {
      this.hotReload.close();
      this.hotReload = null;
    }
    this.resources.dispose();
    this.backend.dispose();
  }

  private final class Loop implements GLEventListener {
    @Override
    public void init(GLAutoDrawable drawable) {
      if (Boolean.getBoolean("engine.debugGL")) {
        drawable.setGL(new DebugGL3(drawable.getGL().getGL3()));
      }
      final var gl = drawable.getGL().getGL3();
      applyVsync(gl);
      log.info("OpenGL {} on {}", gl.glGetString(GL3.GL_VERSION), gl.glGetString(GL3.GL_RENDERER));
      setUp(new GlBackend(() -> Engine.this.window.getGL().getGL3()), true);
      captureMouse(Boolean.parseBoolean(System.getProperty("engine.captureMouse", "true")));
    }

    @Override
    public void display(GLAutoDrawable drawable) {
      if (Engine.this.vsync != Engine.this.appliedVsync) {
        applyVsync(drawable.getGL().getGL3());
      }
      final var profiler = Engine.this.profiler;
      if (Engine.this.hotReload != null) {
        profiler.begin(Engine.this.reloadSection);
        Engine.this.hotReload.poll();
        profiler.end();
      }
      Engine.this.timestep.advance(System.nanoTime(), Engine.this::step);
      Engine.this.scene.flush();
      profiler.begin(Engine.this.renderSection);
      Engine.this.backend.render(Engine.this.scene, Engine.this.timestep.alpha(), drawable.getSurfaceWidth(),
          drawable.getSurfaceHeight(), Engine.this.drawn);
      profiler.end();
      profiler.endFrame();
      Engine.this.frameCount++;
      captureIfRequested(drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
      trackFps();
      if (Engine.this.exitAfterFrames > 0 && Engine.this.frameCount >= Engine.this.exitAfterFrames) {
        stop();
      }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
      Engine.this.backend.resize(width, height);
      if (Engine.this.inputListener.isCaptured()) {
        Engine.this.inputListener.setCaptured(true, width, height);
      }
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
      tearDown();
    }
  }
}
