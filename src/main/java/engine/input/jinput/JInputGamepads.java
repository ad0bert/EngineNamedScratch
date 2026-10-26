package engine.input.jinput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import engine.input.Gamepad;
import engine.input.GamepadBackend;
import engine.input.InputState;
import lombok.extern.slf4j.Slf4j;
import net.java.games.input.Component;
import net.java.games.input.Component.Identifier;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;

/**
 * Gamepads through JInput (DirectInput/raw input on Windows, evdev on Linux, IOKit on macOS). The native libraries
 * are unpacked from the classpath into a temporary directory. Pads are mapped to the {@link Gamepad} layout by the
 * names the platforms give their buttons and axes, which suits Xbox-style pads; others get a best guess.
 *
 * <p>
 * JInput only finds pads while scanning: one scan happens at start-up and another on {@link #rescan()} (the engine
 * asks for one when the window regains focus). A pad that is unplugged is noticed at once.
 */
@Slf4j
public final class JInputGamepads implements GamepadBackend {
  private static final Logger JINPUT_LOG = Logger.getLogger("net.java.games.input"); // held: JUL keeps weak refs

  private final Pad[] pads = new Pad[InputState.MAX_GAMEPADS];
  private volatile boolean rescanRequested;
  private volatile long lastScan;
  private boolean disposed;

  private JInputGamepads() {
  }

  /** Scans for pads; returns null (and logs why) if JInput cannot run on this machine. */
  public static JInputGamepads open(InputState state) {
    try {
      quietLogging();
      unpackNatives();
      final var g = new JInputGamepads();
      g.scan(state);
      return g;
    } catch (RuntimeException | LinkageError | IOException | ReflectiveOperationException e) {
      log.info("Gamepads are not available: {}", e.getMessage());
      return null;
    }
  }

  private static void quietLogging() {
    if (System.getProperty("jinput.loglevel") == null) {
      System.setProperty("jinput.loglevel", "OFF"); // it reports every unreadable input device otherwise
    }
    JINPUT_LOG.setLevel(Level.OFF);
  }

  private static void unpackNatives() throws IOException {
    if (System.getProperty("net.java.games.input.librarypath") != null) {
      return;
    }
    final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    final String[] libs = os.contains("win") ? new String[] { "jinput-raw_64.dll", "jinput-dx8_64.dll",
        "jinput-wintab.dll" } : os.contains("mac") ? new String[] { "libjinput-osx.jnilib" }
            : new String[] { "libjinput-linux64.so" };
    final Path dir = Files.createTempDirectory("engine-jinput");
    dir.toFile().deleteOnExit();
    for (String lib : libs) {
      try (InputStream in = JInputGamepads.class.getClassLoader().getResourceAsStream(lib)) {
        if (in == null) {
          throw new IOException("native library " + lib + " is not on the classpath");
        }
        final var file = dir.resolve(lib);
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        file.toFile().deleteOnExit();
      }
    }
    System.setProperty("net.java.games.input.librarypath", dir.toAbsolutePath().toString());
  }

  /** A fresh environment: JInput's default one is a singleton that never notices new devices. */
  private static ControllerEnvironment environment() throws ReflectiveOperationException {
    final var type = Class.forName("net.java.games.input.DefaultControllerEnvironment");
    final var constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    return (ControllerEnvironment) constructor.newInstance();
  }

  private void scan(InputState state) throws ReflectiveOperationException {
    this.lastScan = System.nanoTime();
    for (int i = 0; i < this.pads.length; i++) {
      if (this.pads[i] != null) {
        state.gamepadDisconnected(i);
        this.pads[i] = null;
      }
    }
    int slot = 0;
    for (Controller c : environment().getControllers()) {
      if (slot >= this.pads.length) {
        break;
      }
      if (c.getType() == Controller.Type.GAMEPAD || c.getType() == Controller.Type.STICK) {
        this.pads[slot] = new Pad(c);
        state.gamepadConnected(slot, c.getName());
        log.info("Gamepad {}: {}", slot, c.getName());
        slot++;
      }
    }
    if (slot == 0) {
      log.info("No gamepads connected");
    }
  }

  /** Ignored within two seconds of the last scan (focus changes come in bursts, and every scan opens devices). */
  @Override
  public void rescan() {
    if (System.nanoTime() - this.lastScan > 2_000_000_000L) {
      this.rescanRequested = true;
    }
  }

  @Override
  public void poll(InputState state) {
    if (this.disposed) {
      return;
    }
    if (this.rescanRequested) {
      this.rescanRequested = false;
      try {
        scan(state);
      } catch (ReflectiveOperationException | RuntimeException e) {
        log.info("Gamepad scan failed: {}", e.getMessage());
      }
    }
    for (int i = 0; i < this.pads.length; i++) {
      final var pad = this.pads[i];
      if (pad != null && !pad.read(state, i)) {
        log.info("Gamepad {} disconnected: {}", i, pad.controller.getName());
        state.gamepadDisconnected(i);
        this.pads[i] = null;
      }
    }
  }

  @Override
  public void dispose() {
    this.disposed = true;
  }

  /** One controller and where its components go in the standard layout. */
  private static final class Pad {
    final Controller controller;
    final Component[] buttons = new Component[Gamepad.BUTTON_COUNT];
    final Component[] axes = new Component[Gamepad.AXIS_COUNT];
    Component pov;
    /** DirectInput reports both triggers on one Z axis (left positive, right negative). */
    Component sharedTriggers;

    Pad(Controller c) {
      this.controller = c;
      for (Component comp : c.getComponents()) {
        final var id = comp.getIdentifier();
        if (id instanceof Identifier.Button) {
          final int b = button(id);
          if (b >= 0 && this.buttons[b] == null) {
            this.buttons[b] = comp;
          }
        } else if (id == Identifier.Axis.X) {
          this.axes[Gamepad.AXIS_LEFT_X] = comp;
        } else if (id == Identifier.Axis.Y) {
          this.axes[Gamepad.AXIS_LEFT_Y] = comp;
        } else if (id == Identifier.Axis.RX) {
          this.axes[Gamepad.AXIS_RIGHT_X] = comp;
        } else if (id == Identifier.Axis.RY) {
          this.axes[Gamepad.AXIS_RIGHT_Y] = comp;
        } else if (id == Identifier.Axis.Z) {
          this.axes[Gamepad.AXIS_LEFT_TRIGGER] = comp;
        } else if (id == Identifier.Axis.RZ) {
          this.axes[Gamepad.AXIS_RIGHT_TRIGGER] = comp;
        } else if (id == Identifier.Axis.POV) {
          this.pov = comp;
        }
      }
      if (this.axes[Gamepad.AXIS_LEFT_TRIGGER] != null && this.axes[Gamepad.AXIS_RIGHT_TRIGGER] == null) {
        this.sharedTriggers = this.axes[Gamepad.AXIS_LEFT_TRIGGER];
        this.axes[Gamepad.AXIS_LEFT_TRIGGER] = null;
      }
    }

    /** Named buttons (Linux, macOS) or numbered ones in XInput order (Windows). */
    private static int button(Identifier id) {
      if (id == Identifier.Button.A || id == Identifier.Button._0 || id == Identifier.Button.TRIGGER) {
        return Gamepad.BUTTON_A;
      } else if (id == Identifier.Button.B || id == Identifier.Button._1 || id == Identifier.Button.THUMB) {
        return Gamepad.BUTTON_B;
      } else if (id == Identifier.Button.X || id == Identifier.Button._2 || id == Identifier.Button.THUMB2) {
        return Gamepad.BUTTON_X;
      } else if (id == Identifier.Button.Y || id == Identifier.Button._3 || id == Identifier.Button.TOP) {
        return Gamepad.BUTTON_Y;
      } else if (id == Identifier.Button.LEFT_THUMB || id == Identifier.Button._4 || id == Identifier.Button.TOP2) {
        return Gamepad.BUTTON_LEFT_BUMPER;
      } else if (id == Identifier.Button.RIGHT_THUMB || id == Identifier.Button._5
          || id == Identifier.Button.PINKIE) {
        return Gamepad.BUTTON_RIGHT_BUMPER;
      } else if (id == Identifier.Button.SELECT || id == Identifier.Button._6 || id == Identifier.Button.BASE) {
        return Gamepad.BUTTON_BACK;
      } else if (id == Identifier.Button.START || id == Identifier.Button._7 || id == Identifier.Button.BASE2) {
        return Gamepad.BUTTON_START;
      } else if (id == Identifier.Button.LEFT_THUMB3 || id == Identifier.Button._8
          || id == Identifier.Button.BASE3) {
        return Gamepad.BUTTON_LEFT_STICK;
      } else if (id == Identifier.Button.RIGHT_THUMB3 || id == Identifier.Button._9
          || id == Identifier.Button.BASE4) {
        return Gamepad.BUTTON_RIGHT_STICK;
      } else if (id == Identifier.Button.MODE || id == Identifier.Button._10) {
        return Gamepad.BUTTON_GUIDE;
      }
      return -1;
    }

    /** Copies the pad's state into the slot; false if the pad is gone. */
    boolean read(InputState state, int slot) {
      if (!this.controller.poll()) {
        return false;
      }
      for (int b = 0; b < Gamepad.BUTTON_DPAD_UP; b++) {
        if (this.buttons[b] != null) {
          state.gamepadButton(slot, b, this.buttons[b].getPollData() > 0.5f);
        }
      }
      final float hat = this.pov != null ? this.pov.getPollData() : Component.POV.OFF;
      state.gamepadButton(slot, Gamepad.BUTTON_DPAD_UP, hat == Component.POV.UP || hat == Component.POV.UP_LEFT
          || hat == Component.POV.UP_RIGHT);
      state.gamepadButton(slot, Gamepad.BUTTON_DPAD_DOWN, hat == Component.POV.DOWN
          || hat == Component.POV.DOWN_LEFT || hat == Component.POV.DOWN_RIGHT);
      state.gamepadButton(slot, Gamepad.BUTTON_DPAD_LEFT, hat == Component.POV.LEFT
          || hat == Component.POV.UP_LEFT || hat == Component.POV.DOWN_LEFT);
      state.gamepadButton(slot, Gamepad.BUTTON_DPAD_RIGHT, hat == Component.POV.RIGHT
          || hat == Component.POV.UP_RIGHT || hat == Component.POV.DOWN_RIGHT);
      state.gamepadAxis(slot, Gamepad.AXIS_LEFT_X, value(Gamepad.AXIS_LEFT_X));
      state.gamepadAxis(slot, Gamepad.AXIS_LEFT_Y, -value(Gamepad.AXIS_LEFT_Y)); // the platforms report down as +
      state.gamepadAxis(slot, Gamepad.AXIS_RIGHT_X, value(Gamepad.AXIS_RIGHT_X));
      state.gamepadAxis(slot, Gamepad.AXIS_RIGHT_Y, -value(Gamepad.AXIS_RIGHT_Y));
      if (this.sharedTriggers != null) {
        final float z = this.sharedTriggers.getPollData();
        state.gamepadAxis(slot, Gamepad.AXIS_LEFT_TRIGGER, Math.max(0f, z));
        state.gamepadAxis(slot, Gamepad.AXIS_RIGHT_TRIGGER, Math.max(0f, -z));
      } else { // separate trigger axes are normalized to -1 (released) .. 1 (pulled)
        state.gamepadAxis(slot, Gamepad.AXIS_LEFT_TRIGGER, trigger(Gamepad.AXIS_LEFT_TRIGGER));
        state.gamepadAxis(slot, Gamepad.AXIS_RIGHT_TRIGGER, trigger(Gamepad.AXIS_RIGHT_TRIGGER));
      }
      return true;
    }

    private float value(int axis) {
      return this.axes[axis] != null ? this.axes[axis].getPollData() : 0f;
    }

    private float trigger(int axis) {
      return this.axes[axis] != null ? (this.axes[axis].getPollData() + 1f) * 0.5f : 0f;
    }
  }
}
