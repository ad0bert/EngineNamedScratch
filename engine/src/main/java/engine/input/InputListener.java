package engine.input;

import java.util.function.BiConsumer;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;

import engine.messaging.IMessageService;
import engine.messaging.MessageObject;
import engine.messaging.MessageQueue;

/**
 * NEWT listener (window thread). Updates {@link InputState} and posts raw events to the message service. While the
 * mouse is captured it reports deltas from the window centre and asks the window to warp the pointer back.
 */
public class InputListener implements KeyListener, MouseListener {
  private final IMessageService messages;
  private final InputState state;
  private volatile boolean captured;
  private volatile int centerX;
  private volatile int centerY;
  private volatile BiConsumer<Integer, Integer> warp = (x, y) -> {
  };

  public InputListener(IMessageService messages, InputState state) {
    this.messages = messages;
    this.state = state;
  }

  public void setWarp(BiConsumer<Integer, Integer> warp) {
    this.warp = warp;
  }

  public void setCaptured(boolean captured, int width, int height) {
    this.captured = captured;
    this.centerX = width / 2;
    this.centerY = height / 2;
  }

  public boolean isCaptured() {
    return this.captured;
  }

  private void post(MessageQueue q, Object event) {
    this.messages.post(MessageObject.builder().sender(this).queue(q).payload(event).build());
  }

  @Override
  public void keyPressed(KeyEvent e) {
    if (!e.isAutoRepeat()) {
      this.state.keyDown(e.getKeySymbol());
      post(MessageQueue.KEYBOARD, e);
    }
    // text input wants auto-repeat
    final char special = special(e);
    if (special != 0) {
      this.state.typed(special);
    } else if (e.isPrintableKey() && !Character.isISOControl(e.getKeyChar()) && !e.isControlDown()
        && !e.isMetaDown()) {
      this.state.typed(e.getKeyChar());
    }
  }

  /** The editing or navigation key as an {@link InputState} key character, or 0. */
  private static char special(KeyEvent e) {
    final int shift = e.isShiftDown() ? InputState.SHIFT : 0;
    final boolean command = e.isControlDown() || e.isMetaDown(); // Ctrl, or Cmd on macOS
    return switch (e.getKeySymbol()) {
    case KeyEvent.VK_BACK_SPACE -> '\b';
    case KeyEvent.VK_ENTER -> '\n';
    case KeyEvent.VK_TAB -> e.isShiftDown() ? InputState.KEY_BACKTAB : '\t';
    case KeyEvent.VK_DELETE -> e.isShiftDown() ? InputState.KEY_CUT : InputState.KEY_DELETE;
    case KeyEvent.VK_INSERT -> e.isShiftDown() ? InputState.KEY_PASTE : command ? InputState.KEY_COPY : 0;
    case KeyEvent.VK_LEFT -> (char) (InputState.KEY_LEFT + shift);
    case KeyEvent.VK_RIGHT -> (char) (InputState.KEY_RIGHT + shift);
    case KeyEvent.VK_UP -> (char) (InputState.KEY_UP + shift);
    case KeyEvent.VK_DOWN -> (char) (InputState.KEY_DOWN + shift);
    case KeyEvent.VK_HOME -> (char) (InputState.KEY_HOME + shift);
    case KeyEvent.VK_END -> (char) (InputState.KEY_END + shift);
    case KeyEvent.VK_A -> command ? InputState.KEY_SELECT_ALL : 0;
    case KeyEvent.VK_C -> command ? InputState.KEY_COPY : 0;
    case KeyEvent.VK_X -> command ? InputState.KEY_CUT : 0;
    case KeyEvent.VK_V -> command ? InputState.KEY_PASTE : 0;
    default -> 0;
    };
  }

  @Override
  public void keyReleased(KeyEvent e) {
    if (!e.isAutoRepeat()) {
      this.state.keyUp(e.getKeySymbol());
      post(MessageQueue.KEYBOARD, e);
    }
  }

  @Override
  public void mouseMoved(MouseEvent e) {
    this.state.mouseMovedTo(e.getX(), e.getY());
    if (this.captured) {
      final int dx = e.getX() - this.centerX;
      final int dy = e.getY() - this.centerY;
      if (dx != 0 || dy != 0) {
        this.state.mouseMoved(dx, dy);
        this.warp.accept(this.centerX, this.centerY);
      }
    }
    post(MessageQueue.MOUSE, e);
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    mouseMoved(e);
  }

  @Override
  public void mouseClicked(MouseEvent e) {
    post(MessageQueue.MOUSE, e);
  }

  @Override
  public void mouseEntered(MouseEvent e) {
  }

  @Override
  public void mouseExited(MouseEvent e) {
  }

  @Override
  public void mousePressed(MouseEvent e) {
    this.state.mouseMovedTo(e.getX(), e.getY());
    this.state.mouseButton(e.getButton(), true);
    post(MessageQueue.MOUSE, e);
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    this.state.mouseMovedTo(e.getX(), e.getY());
    this.state.mouseButton(e.getButton(), false);
    post(MessageQueue.MOUSE, e);
  }

  @Override
  public void mouseWheelMoved(MouseEvent e) {
    final float[] rotation = e.getRotation();
    this.state.mouseWheel(rotation.length > 1 ? rotation[1] : 0f);
    post(MessageQueue.MOUSE, e);
  }
}
