package engine.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the subset of Wavefront MTL the engine uses: Kd, Ks, Ns, d, map_Kd and normal maps (map_Bump/bump/norm). */
public final class MtlLoader {
  private MtlLoader() {
  }

  /**
   * @param kd diffuse colour; @param specular average of Ks; @param shininess Ns clamped to 1..128;
   *           @param alpha d (Tr is ignored: exporters disagree on its meaning); @param diffuseMap texture file
   *           relative to the .mtl, or null; @param normalMap tangent-space normal map file, or null;
   *           @param normalScale the {@code -bm} option of the normal map statement (default 1)
   */
  public record MtlMaterial(String name, float[] kd, float specular, float shininess, float alpha,
      String diffuseMap, String normalMap, float normalScale) {
  }

  private static final class Builder {
    final String name;
    float[] kd = { 1f, 1f, 1f };
    float specular;
    float shininess = 32f;
    float alpha = 1f;
    String diffuseMap;
    String normalMap;
    float normalScale = 1f;

    Builder(String name) {
      this.name = name;
    }

    MtlMaterial build() {
      return new MtlMaterial(this.name, this.kd, this.specular, this.shininess, this.alpha, this.diffuseMap,
          this.normalMap, this.normalScale);
    }
  }

  public static Map<String, MtlMaterial> parse(InputStream in) throws IOException {
    final Map<String, MtlMaterial> result = new LinkedHashMap<>();
    Builder cur = null;

    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        final var t = line.split("\\s+");
        try {
          if (t[0].equals("newmtl")) {
            if (cur != null) {
              result.put(cur.name, cur.build());
            }
            cur = new Builder(line.substring(6).trim());
            continue;
          }
          if (cur == null) {
            continue; // statements before the first newmtl have nothing to attach to
          }
          switch (t[0]) {
          case "Kd" -> cur.kd = new float[] { Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]) };
          case "Ks" -> cur.specular = (Float.parseFloat(t[1]) + Float.parseFloat(t[2]) + Float.parseFloat(t[3])) / 3f;
          case "Ns" -> cur.shininess = Math.max(1f, Math.min(128f, Float.parseFloat(t[1])));
          case "d" -> cur.alpha = Float.parseFloat(t[1]);
          case "map_Kd" -> cur.diffuseMap = parseMap(t, null);
          case "map_Bump", "map_bump", "bump", "norm" -> {
            final float[] scale = { 1f };
            cur.normalMap = parseMap(t, scale);
            cur.normalScale = scale[0];
          }
          default -> {
            // Ka, illum, Tr, other maps ... ignored
          }
          }
        } catch (RuntimeException e) {
          throw new IOException("Bad MTL line: " + line, e);
        }
      }
    }
    if (cur != null) {
      result.put(cur.name, cur.build());
    }
    return result;
  }

  /** Reads a texture statement: skips options (like {@code -s 1 1 1}), returns the file name; {@code -bm} goes to scale. */
  private static String parseMap(String[] t, float[] bumpScale) {
    int i = 1;
    while (i < t.length) {
      final var s = t[i];
      if (s.equals("-bm")) {
        if (bumpScale != null) {
          bumpScale[0] = Float.parseFloat(t[i + 1]);
        }
        i += 2;
      } else if (s.equals("-s") || s.equals("-o") || s.equals("-t")) {
        i++;
        while (i < t.length && isNumber(t[i])) { // 1 to 3 numbers
          i++;
        }
      } else if (s.equals("-mm")) {
        i += 3;
      } else if (s.startsWith("-")) {
        i += 2; // -clamp on, -blendu on, -texres 512, -imfchan r, -type sphere ...
      } else {
        return String.join(" ", java.util.Arrays.copyOfRange(t, i, t.length)); // file names may contain spaces
      }
    }
    return null;
  }

  private static boolean isNumber(String s) {
    try {
      Float.parseFloat(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
