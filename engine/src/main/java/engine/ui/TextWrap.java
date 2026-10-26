package engine.ui;

import java.util.ArrayList;
import java.util.List;

/** Greedy word wrapping for any font: breaks text into lines no wider than a width, measured by the caller. */
public final class TextWrap {
  private TextWrap() {
  }

  /** Width of a piece of text in pixels (for example {@code s -> canvas.textWidth(font, s, 1f)}). */
  @FunctionalInterface
  public interface Measure {
    float width(String text);
  }

  /**
   * The lines of {@code text} wrapped to {@code width}: lines break at spaces (the space at a break is dropped),
   * explicit line breaks are kept, and a word wider than a whole line is broken between characters.
   */
  public static List<String> wrap(String text, float width, Measure measure) {
    final List<String> lines = new ArrayList<>();
    for (final String paragraph : text.split("\n", -1)) {
      String line = null; // nothing on the current line yet
      for (final String word : paragraph.split(" ", -1)) {
        if (line != null) {
          final var candidate = line + " " + word;
          if (measure.width(candidate) <= width) {
            line = candidate;
            continue;
          }
          lines.add(line);
        }
        var rest = word;
        while (measure.width(rest) > width && rest.codePointCount(0, rest.length()) > 1) { // longer than a line
          final int cut = longestFit(rest, width, measure);
          lines.add(rest.substring(0, cut));
          rest = rest.substring(cut);
        }
        line = rest;
      }
      lines.add(line == null ? "" : line);
    }
    return lines;
  }

  /** The longest prefix (in whole code points, at least one) that fits. */
  private static int longestFit(String s, float width, Measure measure) {
    int fit = s.offsetByCodePoints(0, 1);
    for (int i = fit; i < s.length();) {
      final int next = s.offsetByCodePoints(i, 1);
      if (measure.width(s.substring(0, next)) > width) {
        break;
      }
      fit = next;
      i = next;
    }
    return fit;
  }
}
