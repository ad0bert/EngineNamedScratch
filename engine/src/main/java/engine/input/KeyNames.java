package engine.input;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.jogamp.newt.event.KeyEvent;

/**
 * Names of keys for menus and settings files: those of NEWT's {@code KeyEvent.VK_} constants without the prefix
 * ({@code W}, {@code SPACE}, {@code F5}, {@code SHIFT}). Where several constants share a code, the first declared
 * name is used.
 */
public final class KeyNames {
  private static final Map<Short, String> NAMES = new HashMap<>();
  private static final Map<String, Short> CODES = new HashMap<>();

  static {
    for (final var f : KeyEvent.class.getFields()) {
      if (f.getName().startsWith("VK_") && f.getType() == short.class && Modifier.isStatic(f.getModifiers())) {
        try {
          final short code = f.getShort(null);
          final String name = f.getName().substring(3);
          NAMES.putIfAbsent(code, name);
          CODES.putIfAbsent(name, code);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private KeyNames() {
  }

  /** The key's name, or its code in hex for keys NEWT has no constant for. */
  public static String name(short key) {
    final var name = NAMES.get(key);
    return name != null ? name : String.format(Locale.ROOT, "0x%04X", key & 0xFFFF);
  }

  /** The key with this name (any case; also {@code 0x...} codes), or -1. */
  public static int code(String name) {
    final var upper = name.trim().toUpperCase(Locale.ROOT);
    if (upper.startsWith("0X")) {
      try {
        return Integer.parseInt(upper.substring(2), 16) & 0xFFFF;
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    final var code = CODES.get(upper.startsWith("VK_") ? upper.substring(3) : upper);
    return code != null ? code & 0xFFFF : -1;
  }
}
