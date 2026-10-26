package engine.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The desktop clipboard through AWT; {@link #INSTANCE} falls back to memory when AWT has none. */
final class SystemClipboard implements Clipboard {
  private static final Logger LOG = LoggerFactory.getLogger(SystemClipboard.class);
  static final Clipboard INSTANCE = create();

  private final java.awt.datatransfer.Clipboard awt;
  private final Clipboard fallback = Clipboard.inMemory();

  private SystemClipboard(java.awt.datatransfer.Clipboard awt) {
    this.awt = awt;
  }

  private static Clipboard create() {
    if (GraphicsEnvironment.isHeadless()) {
      return Clipboard.inMemory();
    }
    try {
      return new SystemClipboard(Toolkit.getDefaultToolkit().getSystemClipboard());
    } catch (RuntimeException | Error e) { // HeadlessException, AWTError without a display
      LOG.info("no system clipboard ({}), copying and pasting within the program only", e.toString());
      return Clipboard.inMemory();
    }
  }

  @Override
  public String getText() {
    try {
      final var contents = this.awt.getContents(null);
      if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        return (String) contents.getTransferData(DataFlavor.stringFlavor);
      }
      return "";
    } catch (Exception e) { // busy (another program has it open) or unsupported data
      LOG.debug("clipboard not readable: {}", e.toString());
      return this.fallback.getText();
    }
  }

  @Override
  public void setText(String text) {
    this.fallback.setText(text);
    try {
      this.awt.setContents(new StringSelection(text), null);
    } catch (IllegalStateException e) { // busy
      LOG.debug("clipboard not writable: {}", e.toString());
    }
  }
}
