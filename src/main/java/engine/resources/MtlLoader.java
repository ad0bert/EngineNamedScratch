package engine.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the subset of Wavefront MTL the engine uses: Kd, Ks, Ns, d and map_Kd. */
public final class MtlLoader {
  private MtlLoader() {
  }

  /**
   * @param kd diffuse colour; @param specular average of Ks; @param shininess Ns clamped to 1..128;
   *           @param alpha d (Tr is ignored: exporters disagree on its meaning); @param diffuseMap texture file
   *           relative to the .mtl, or null
   */
  public record MtlMaterial(String name, float[] kd, float specular, float shininess, float alpha,
      String diffuseMap) {
  }

  public static Map<String, MtlMaterial> parse(InputStream in) throws IOException {
    final Map<String, MtlMaterial> result = new LinkedHashMap<>();
    String name = null;
    float[] kd = { 1f, 1f, 1f };
    float specular = 0f;
    float shininess = 32f;
    float alpha = 1f;
    String map = null;

    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        final String[] t = line.split("\\s+");
        try {
          switch (t[0]) {
          case "newmtl" -> {
            if (name != null) {
              result.put(name, new MtlMaterial(name, kd, specular, shininess, alpha, map));
            }
            name = line.substring(6).trim();
            kd = new float[] { 1f, 1f, 1f };
            specular = 0f;
            shininess = 32f;
            alpha = 1f;
            map = null;
          }
          case "Kd" -> kd = new float[] { Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]) };
          case "Ks" -> specular = (Float.parseFloat(t[1]) + Float.parseFloat(t[2]) + Float.parseFloat(t[3])) / 3f;
          case "Ns" -> shininess = Math.max(1f, Math.min(128f, Float.parseFloat(t[1])));
          case "d" -> alpha = Float.parseFloat(t[1]);
          case "map_Kd" -> map = t[t.length - 1]; // options such as -s are skipped; the file name comes last
          default -> {
            // Ka, illum, Tr, bump maps ... ignored
          }
          }
        } catch (RuntimeException e) {
          throw new IOException("Bad MTL line: " + line, e);
        }
      }
    }
    if (name != null) {
      result.put(name, new MtlMaterial(name, kd, specular, shininess, alpha, map));
    }
    return result;
  }
}
