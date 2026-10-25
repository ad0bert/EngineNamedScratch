package engine.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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

import engine.debug.DebugOverlay;
import engine.ecs.ComponentUpdateSystem;
import engine.ecs.ISystem;
import engine.ecs.Scene;
import engine.input.FirstPersonController;
import engine.input.InputListener;
import engine.input.InputState;
import engine.messaging.IMessageService;
import engine.messaging.MessageService;
import engine.physics.CollisionSystem;
import engine.render.Renderer;
import engine.resources.ResourceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the window, the GL loop and all subsystems. Everything (update, messages, rendering) runs on the animator's GL
 * thread, so scene state needs no locking; only input and window events arrive from other threads and go through
 * {@link InputState} and {@link IMessageService#post}.
 *
 * <p>
 * System properties: {@code engine.debugGL=true} wraps GL in {@link DebugGL3}; {@code engine.exitAfterFrames=N} closes
 * the window after N rendered frames (smoke tests); {@code engine.captureMouse=false} starts with a free pointer.
 */
@Slf4j
public class Engine {
  private final EngineConfig config;
  private final Game game;
  private final Scene scene = new Scene();
  private final IMessageService messages = new MessageService();
  private final InputState input = new InputState();
  private final InputListener inputListener = new InputListener(this.messages, this.input);
  private final List<ISystem> systems = new ArrayList<>();
  private final FixedTimestep timestep;
  private final Renderer renderer = new Renderer();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final int exitAfterFrames = Integer.getInteger("engine.exitAfterFrames", 0);

  private GLWindow window;
  private Animator animator;
  private ResourceManager resources;

  private volatile double fps;
  private long frameCount;
  private long fpsWindowStart;
  private long fpsWindowFrames;

  public Engine(EngineConfig config, Game game) {
    this.config = config;
    this.game = game;
    this.timestep = new FixedTimestep(config.updateStep(), 0.25);
  }

  public EngineConfig config() {
    return this.config;
  }

  public Scene scene() {
    return this.scene;
  }

  public IMessageService messages() {
    return this.messages;
  }

  public InputState input() {
    return this.input;
  }

  public ResourceManager resources() {
    return this.resources;
  }

  public double fps() {
    return this.fps;
  }

  public void addSystem(ISystem system) {
    this.systems.add(system);
  }

  public void setTitle(String title) {
    if (this.window != null) {
      this.window.setTitle(title);
    }
  }

  /** Opens the window and blocks until it is closed. */
  public void run() throws InterruptedException {
    final GLCapabilities caps = new GLCapabilities(GLProfile.get(GLProfile.GL3));
    caps.setDepthBits(24);
    this.window = GLWindow.create(caps);
    this.window.setTitle(this.config.title());
    this.window.setSize(this.config.width(), this.config.height());
    this.window.setDefaultCloseOperation(WindowClosingMode.DISPOSE_ON_CLOSE);
    this.window.addKeyListener(this.inputListener);
    this.window.addMouseListener(this.inputListener);
    this.inputListener.setWarp(this.window::warpPointer);
    this.window.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (!Engine.this.inputListener.isCaptured()) {
          captureMouse(true);
        }
      }
    });
    this.window.addWindowListener(new WindowAdapter() {
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
    log.info("Engine stopped after {} frames", this.frameCount);
  }

  /** Requests shutdown; safe to call from any thread. */
  public void stop() {
    new Thread(() -> this.window.destroy(), "engine-stop").start();
  }

  public void captureMouse(boolean capture) {
    this.inputListener.setCaptured(capture, this.window.getSurfaceWidth(), this.window.getSurfaceHeight());
    this.window.setPointerVisible(!capture);
    this.window.confinePointer(capture);
  }

  private void step(double dt) {
    this.scene.flush();
    this.messages.dispatchPending();
    if (this.input.wasActionPressed("release_mouse")) {
      captureMouse(false);
    }
    for (ISystem s : this.systems) {
      s.update(this.scene, dt);
    }
    this.game.update(this, dt);
    this.input.endFrame();
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

  private final class Loop implements GLEventListener {
    @Override
    public void init(GLAutoDrawable drawable) {
      if (Boolean.getBoolean("engine.debugGL")) {
        drawable.setGL(new DebugGL3(drawable.getGL().getGL3()));
      }
      final GL3 gl = drawable.getGL().getGL3();
      gl.setSwapInterval(1);
      log.info("OpenGL {} on {}", gl.glGetString(GL3.GL_VERSION), gl.glGetString(GL3.GL_RENDERER));
      Engine.this.resources = new ResourceManager(gl);
      Engine.this.renderer.init(gl, Engine.this.resources.white());

      Engine.this.input.bind("release_mouse", KeyEvent.VK_ESCAPE);
      FirstPersonController.bindDefaults(Engine.this.input);
      Engine.this.addSystem(new ComponentUpdateSystem());
      Engine.this.addSystem(new CollisionSystem(Engine.this.messages));
      Engine.this.addSystem(new DebugOverlay(Engine.this));
      Engine.this.game.init(Engine.this);
      Engine.this.scene.flush();
      Engine.this.fpsWindowStart = System.nanoTime();
      captureMouse(Boolean.parseBoolean(System.getProperty("engine.captureMouse", "true")));
    }

    @Override
    public void display(GLAutoDrawable drawable) {
      final GL3 gl = drawable.getGL().getGL3();
      Engine.this.timestep.advance(System.nanoTime(), Engine.this::step);
      Engine.this.scene.flush();
      Engine.this.renderer.render(gl, Engine.this.scene, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
      Engine.this.frameCount++;
      trackFps();
      if (Engine.this.exitAfterFrames > 0 && Engine.this.frameCount >= Engine.this.exitAfterFrames) {
        stop();
      }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
      Engine.this.renderer.resize(drawable.getGL().getGL3(), width, height);
      if (Engine.this.inputListener.isCaptured()) {
        Engine.this.inputListener.setCaptured(true, width, height);
      }
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
      final GL3 gl = drawable.getGL().getGL3();
      Engine.this.game.shutdown(Engine.this);
      Engine.this.renderer.dispose(gl);
      Engine.this.resources.dispose();
    }
  }
}
