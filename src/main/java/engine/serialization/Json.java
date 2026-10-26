package engine.serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer (RFC 8259), enough for scenes, prefabs and glTF without a dependency. Objects are read
 * as {@code LinkedHashMap<String, Object>} (keeping the order), arrays as {@code List<Object>}, numbers as
 * {@code Double}, plus {@code String}, {@code Boolean} and {@code null}. The writer accepts the same plus any
 * {@code Number} and float/int arrays; non-finite numbers, which JSON cannot hold, are written as the strings
 * {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"} (and read back by {@link #asFloat}).
 */
public final class Json {
  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  /** @throws IllegalArgumentException with the position, if the text is not valid JSON */
  public static Object parse(String text) {
    final var p = new Json(text);
    p.skipSpace();
    final var value = p.value();
    p.skipSpace();
    if (p.i != text.length()) {
      throw p.error("unexpected text after the value");
    }
    return value;
  }

  private IllegalArgumentException error(String message) {
    int line = 1;
    int column = 1;
    for (int k = 0; k < Math.min(this.i, this.s.length()); k++) {
      if (this.s.charAt(k) == '\n') {
        line++;
        column = 1;
      } else {
        column++;
      }
    }
    return new IllegalArgumentException("JSON: " + message + " at line " + line + ", column " + column);
  }

  private void skipSpace() {
    while (this.i < this.s.length()) {
      final char c = this.s.charAt(this.i);
      if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
        break;
      }
      this.i++;
    }
  }

  private Object value() {
    if (this.i >= this.s.length()) {
      throw error("unexpected end");
    }
    final char c = this.s.charAt(this.i);
    return switch (c) {
    case '{' -> object();
    case '[' -> array();
    case '"' -> string();
    case 't' -> literal("true", Boolean.TRUE);
    case 'f' -> literal("false", Boolean.FALSE);
    case 'n' -> literal("null", null);
    default -> {
      if (c == '-' || c >= '0' && c <= '9') {
        yield number();
      }
      throw error("unexpected character '" + c + "'");
    }
    };
  }

  private Object literal(String word, Object value) {
    if (!this.s.startsWith(word, this.i)) {
      throw error("expected " + word);
    }
    this.i += word.length();
    return value;
  }

  private Map<String, Object> object() {
    final Map<String, Object> out = new LinkedHashMap<>();
    this.i++;
    skipSpace();
    if (peek() == '}') {
      this.i++;
      return out;
    }
    while (true) {
      skipSpace();
      if (peek() != '"') {
        throw error("expected a key");
      }
      final var key = string();
      skipSpace();
      expect(':');
      skipSpace();
      out.put(key, value());
      skipSpace();
      if (peek() == ',') {
        this.i++;
      } else {
        expect('}');
        return out;
      }
    }
  }

  private List<Object> array() {
    final List<Object> out = new ArrayList<>();
    this.i++;
    skipSpace();
    if (peek() == ']') {
      this.i++;
      return out;
    }
    while (true) {
      skipSpace();
      out.add(value());
      skipSpace();
      if (peek() == ',') {
        this.i++;
      } else {
        expect(']');
        return out;
      }
    }
  }

  private char peek() {
    if (this.i >= this.s.length()) {
      throw error("unexpected end");
    }
    return this.s.charAt(this.i);
  }

  private void expect(char c) {
    if (peek() != c) {
      throw error("expected '" + c + "'");
    }
    this.i++;
  }

  private String string() {
    this.i++; // opening quote
    final var sb = new StringBuilder();
    while (true) {
      final char c = peek();
      this.i++;
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      final char e = peek();
      this.i++;
      switch (e) {
      case '"', '\\', '/' -> sb.append(e);
      case 'b' -> sb.append('\b');
      case 'f' -> sb.append('\f');
      case 'n' -> sb.append('\n');
      case 'r' -> sb.append('\r');
      case 't' -> sb.append('\t');
      case 'u' -> {
        if (this.i + 4 > this.s.length()) {
          throw error("short unicode escape");
        }
        sb.append((char) Integer.parseInt(this.s.substring(this.i, this.i + 4), 16));
        this.i += 4;
      }
      default -> throw error("bad escape \\" + e);
      }
    }
  }

  private Double number() {
    final int start = this.i;
    while (this.i < this.s.length()) {
      final char c = this.s.charAt(this.i);
      if (c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
        this.i++;
      } else {
        break;
      }
    }
    try {
      return Double.valueOf(this.s.substring(start, this.i));
    } catch (NumberFormatException e) {
      throw error("bad number");
    }
  }

  /** Writes a value as indented JSON (two spaces, short numeric arrays on one line). */
  public static String write(Object value) {
    final var sb = new StringBuilder();
    write(sb, value, 0);
    return sb.append('\n').toString();
  }

  private static void write(StringBuilder sb, Object v, int indent) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String str) {
      quote(sb, str);
    } else if (v instanceof Boolean b) {
      sb.append(b.booleanValue());
    } else if (v instanceof Float f) {
      number(sb, f.floatValue(), Float.toString(f));
    } else if (v instanceof Double d) {
      number(sb, d.doubleValue(), Double.toString(d));
    } else if (v instanceof Number n) {
      sb.append(n.longValue());
    } else if (v instanceof Enum<?> e) {
      quote(sb, e.name());
    } else if (v instanceof float[] a) {
      sb.append('[');
      for (int k = 0; k < a.length; k++) {
        sb.append(k > 0 ? ", " : "");
        number(sb, a[k], Float.toString(a[k]));
      }
      sb.append(']');
    } else if (v instanceof int[] a) {
      sb.append('[');
      for (int k = 0; k < a.length; k++) {
        sb.append(k > 0 ? ", " : "").append(a[k]);
      }
      sb.append(']');
    } else if (v instanceof Map<?, ?> m) {
      if (m.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int k = 0;
      for (var e : m.entrySet()) {
        sb.append("  ".repeat(indent + 1));
        quote(sb, String.valueOf(e.getKey()));
        sb.append(": ");
        write(sb, e.getValue(), indent + 1);
        sb.append(++k < m.size() ? ",\n" : "\n");
      }
      sb.append("  ".repeat(indent)).append('}');
    } else if (v instanceof List<?> l) {
      if (l.isEmpty()) {
        sb.append("[]");
        return;
      }
      final boolean flat = l.stream().allMatch(x -> x instanceof Number || x instanceof Boolean);
      if (flat) {
        sb.append('[');
        for (int k = 0; k < l.size(); k++) {
          sb.append(k > 0 ? ", " : "");
          write(sb, l.get(k), indent);
        }
        sb.append(']');
        return;
      }
      sb.append("[\n");
      for (int k = 0; k < l.size(); k++) {
        sb.append("  ".repeat(indent + 1));
        write(sb, l.get(k), indent + 1);
        sb.append(k + 1 < l.size() ? ",\n" : "\n");
      }
      sb.append("  ".repeat(indent)).append(']');
    } else {
      throw new IllegalArgumentException("cannot write " + v.getClass().getName() + " as JSON");
    }
  }

  private static void number(StringBuilder sb, double value, String text) {
    if (Double.isNaN(value)) {
      sb.append("\"NaN\"");
    } else if (Double.isInfinite(value)) {
      sb.append(value > 0 ? "\"Infinity\"" : "\"-Infinity\"");
    } else if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      sb.append((long) value); // 2 rather than 2.0
    } else {
      sb.append(text);
    }
  }

  private static void quote(StringBuilder sb, String str) {
    sb.append('"');
    for (int k = 0; k < str.length(); k++) {
      final char c = str.charAt(k);
      switch (c) {
      case '"' -> sb.append("\\\"");
      case '\\' -> sb.append("\\\\");
      case '\n' -> sb.append("\\n");
      case '\r' -> sb.append("\\r");
      case '\t' -> sb.append("\\t");
      default -> {
        if (c < 0x20) {
          sb.append(String.format("\\u%04x", (int) c));
        } else {
          sb.append(c);
        }
      }
      }
    }
    sb.append('"');
  }

  /** A number (or one of the non-finite strings the writer produces) as a float. */
  public static float asFloat(Object v) {
    if (v instanceof Number n) {
      return n.floatValue();
    }
    if (v instanceof String str) {
      return switch (str) {
      case "NaN" -> Float.NaN;
      case "Infinity" -> Float.POSITIVE_INFINITY;
      case "-Infinity" -> Float.NEGATIVE_INFINITY;
      default -> Float.parseFloat(str);
      };
    }
    throw new IllegalArgumentException("not a number: " + v);
  }

  public static int asInt(Object v) {
    return (int) Math.round(((Number) v).doubleValue());
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> asObject(Object v) {
    if (!(v instanceof Map)) {
      throw new IllegalArgumentException("not a JSON object: " + v);
    }
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> asList(Object v) {
    if (!(v instanceof List)) {
      throw new IllegalArgumentException("not a JSON array: " + v);
    }
    return (List<Object>) v;
  }

  /** The numbers of a JSON array as floats. */
  public static float[] asFloats(Object v) {
    final var list = asList(v);
    final float[] out = new float[list.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = asFloat(list.get(k));
    }
    return out;
  }

  public static int[] asInts(Object v) {
    final var list = asList(v);
    final int[] out = new int[list.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = asInt(list.get(k));
    }
    return out;
  }
}
