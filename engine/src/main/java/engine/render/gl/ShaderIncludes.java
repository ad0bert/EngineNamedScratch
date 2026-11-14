package engine.render.gl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Expands {@code #include "path"} lines in shader sources. A path is looked up next to the including file first, then
 * in the engine's {@code shaders/} directory (so {@code #include "lib/surface.glsl"} finds the engine's library from
 * any shader), then as a resource path of its own ({@code "shaders/lib/surface.glsl"}); a leading {@code /} skips
 * the first. Each file is included once per shader, so libraries can
 * include each other freely. {@code #line} directives keep compiler messages pointing at the right file and line:
 * the number before the line number is the index in {@link Expanded#files()}.
 */
final class ShaderIncludes {
  private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s+[\"<]([^\">]+)[\">]\\s*(//.*)?$");
  private static final int MAX_DEPTH = 32;
  private static final String SHADER_ROOT = "shaders/";

  /** The expanded text, and every file that went into it (the shader itself first). */
  record Expanded(String text, List<String> files) {
    /** "0 = shaders/basic.frag, 1 = ..." for error messages. */
    String legend() {
      final var sb = new StringBuilder();
      for (int i = 0; i < this.files.size(); i++) {
        sb.append(i == 0 ? "" : ", ").append(i).append(" = ").append(this.files.get(i));
      }
      return sb.toString();
    }
  }

  private ShaderIncludes() {
  }

  /**
   * Expands {@code resource}, reading files with {@code reader} (null for a file that does not exist).
   *
   * @throws IllegalArgumentException if the shader or an included file does not exist, or includes nest too deep
   */
  static Expanded expand(String resource, Function<String, String> reader) {
    final String text = reader.apply(resource);
    if (text == null) {
      throw new IllegalArgumentException("Shader resource not found: " + resource);
    }
    final List<String> files = new ArrayList<>();
    files.add(resource);
    final var out = new StringBuilder(text.length() + 1024);
    append(out, resource, text, 0, reader, files, new HashSet<>(Set.of(resource)), 0);
    return new Expanded(out.toString(), List.copyOf(files));
  }

  private static void append(StringBuilder out, String resource, String text, int index,
      Function<String, String> reader, List<String> files, Set<String> seen, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("Includes nest deeper than " + MAX_DEPTH + " at " + resource);
    }
    final String[] lines = text.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      final var m = INCLUDE.matcher(lines[i]);
      if (!m.matches()) {
        out.append(lines[i]);
        if (i < lines.length - 1) {
          out.append('\n');
        }
        continue;
      }
      final String target = resolve(resource, m.group(1), reader);
      if (target == null) {
        throw new IllegalArgumentException(resource + ":" + (i + 1) + ": cannot find included file " + m.group(1));
      }
      if (seen.add(target)) {
        final int child = files.size();
        files.add(target);
        out.append("#line 1 ").append(child).append('\n');
        append(out, target, reader.apply(target), child, reader, files, seen, depth + 1);
        out.append('\n');
      }
      // back in this file, at the line after the include
      out.append("#line ").append(i + 2).append(' ').append(index).append('\n');
    }
  }

  /**
   * The resource path of {@code include} as written in {@code from}: next to {@code from}, else in the engine's
   * {@code shaders/} directory (so {@code "lib/surface.glsl"} works from anywhere), else as a path of its own. Null if
   * none of them has it.
   */
  static String resolve(String from, String include, Function<String, String> reader) {
    final int slash = from.lastIndexOf('/');
    final String trimmed = include.startsWith("/") ? include.substring(1) : include;
    final String[] candidates = { include.startsWith("/") ? null : slash < 0 ? include
        : from.substring(0, slash + 1) + include, SHADER_ROOT + trimmed, trimmed };
    for (String c : candidates) {
      final String path = c == null ? null : normalize(c);
      if (path != null && reader.apply(path) != null) {
        return path;
      }
    }
    return null;
  }

  /** Removes "." and resolves ".." segments; null if it climbs above the root. */
  static String normalize(String path) {
    final List<String> parts = new ArrayList<>();
    for (String p : path.split("/")) {
      if (p.isEmpty() || p.equals(".")) {
        continue;
      }
      if (p.equals("..")) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.removeLast();
      } else {
        parts.add(p);
      }
    }
    return String.join("/", parts);
  }
}
