package engine.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses Wavefront MTL: Kd, Ka, Ks, Ke, Ns, d, Tr, Ni, illum; the maps map_Kd, map_Ks, map_Ke, map_d,
 * map_Bump/bump/norm (normal or height maps) and disp/map_disp (height maps for parallax); the PBR extension Pr, Pm,
 * map_Pr, map_Pm; and the texture options -o and -s (uv offset and scale, taken from the first map that has them),
 * -clamp and -bm. Other options (-blendu, -mm, -imfchan, ...) are skipped.
 */
public final class MtlLoader {
  private MtlLoader() {
  }

  /**
   * @param kd          diffuse colour
   * @param specular    average of Ks, or 0 for {@code illum} 0 and 1 (no highlights)
   * @param shininess   Ns clamped to 1..128
   * @param alpha       d, or 1 - Tr when there is no d (a Tr that would make the material invisible is taken as
   *                    opaque: some exporters write the opacity there)
   * @param ke          emissive colour (Ke), black if absent
   * @param diffuseMap  texture files relative to the .mtl, or null: map_Kd
   * @param normalMap   map_Bump/bump/norm: a tangent-space normal map, or a grey height map the loader converts
   * @param normalScale the {@code -bm} option of the normal map statement (default 1)
   * @param specularMap map_Ks
   * @param emissiveMap map_Ke
   * @param alphaMap    map_d: cut-out mask (the brightness is the alpha)
   * @param heightMap   disp/map_disp: height map for parallax occlusion mapping
   * @param ka          ambient colour (Ka), or null when absent or black (black would hide the ambient light)
   * @param ior         index of refraction (Ni), NaN if absent
   * @param roughness   Pr (PBR), NaN if absent
   * @param metallic    Pm (PBR), NaN if absent
   * @param roughnessMap map_Pr: roughness in the brightness
   * @param metallicMap map_Pm: metallic in the brightness
   * @param uvScale     -s of the first map that has it ({1, 1} otherwise)
   * @param uvOffset    -o of the first map that has it ({0, 0} otherwise)
   * @param clamp       a map had {@code -clamp on}
   */
  public record MtlMaterial(String name, float[] kd, float specular, float shininess, float alpha, float[] ke,
      String diffuseMap, String normalMap, float normalScale, String specularMap, String emissiveMap,
      String alphaMap, String heightMap, float[] ka, float ior, float roughness, float metallic,
      String roughnessMap, String metallicMap, float[] uvScale, float[] uvOffset, boolean clamp) {

    /** True if the material uses the PBR extension (Pr, Pm or their maps). */
    public boolean pbr() {
      return !Float.isNaN(this.roughness) || !Float.isNaN(this.metallic) || this.roughnessMap != null
          || this.metallicMap != null;
    }
  }

  private static final class Builder {
    final String name;
    float[] kd = { 1f, 1f, 1f };
    float specular;
    float shininess = 32f;
    float alpha = 1f;
    float[] ke = { 0f, 0f, 0f };
    int illum = 2;
    String diffuseMap;
    String normalMap;
    float normalScale = 1f;
    String specularMap;
    String emissiveMap;
    String alphaMap;
    String heightMap;
    float[] ka;
    float ior = Float.NaN;
    float transmission = Float.NaN;
    boolean hasAlpha;
    float roughness = Float.NaN;
    float metallic = Float.NaN;
    String roughnessMap;
    String metallicMap;
    float[] uvScale;
    float[] uvOffset;
    boolean clamp;

    Builder(String name) {
      this.name = name;
    }

    MtlMaterial build() {
      float a = this.alpha;
      if (!this.hasAlpha && !Float.isNaN(this.transmission) && this.transmission < 0.999f) {
        a = 1f - this.transmission;
      }
      return new MtlMaterial(this.name, this.kd, this.illum < 2 ? 0f : this.specular, this.shininess, a, this.ke,
          this.diffuseMap, this.normalMap, this.normalScale, this.specularMap, this.emissiveMap, this.alphaMap,
          this.heightMap, this.ka, this.ior, this.roughness, this.metallic, this.roughnessMap, this.metallicMap,
          this.uvScale != null ? this.uvScale : new float[] { 1f, 1f },
          this.uvOffset != null ? this.uvOffset : new float[] { 0f, 0f }, this.clamp);
    }

    /** Reads a texture statement's options into this material and returns the file name. */
    String map(String[] t) {
      final var options = new MapOptions();
      final var file = parseMap(t, options);
      if (options.scale != null && this.uvScale == null) {
        this.uvScale = options.scale;
      }
      if (options.offset != null && this.uvOffset == null) {
        this.uvOffset = options.offset;
      }
      this.clamp |= options.clamp;
      return file;
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
          case "Kd" -> cur.kd = color(t);
          case "Ka" -> {
            final float[] ka = color(t);
            cur.ka = ka[0] + ka[1] + ka[2] > 0f ? ka : null;
          }
          case "Ni" -> cur.ior = Float.parseFloat(t[1]);
          case "Tr" -> cur.transmission = Float.parseFloat(t[1]);
          case "Pr" -> cur.roughness = Float.parseFloat(t[1]);
          case "Pm" -> cur.metallic = Float.parseFloat(t[1]);
          case "map_Pr" -> cur.roughnessMap = cur.map(t);
          case "map_Pm" -> cur.metallicMap = cur.map(t);
          case "Ke" -> cur.ke = color(t);
          case "illum" -> cur.illum = Integer.parseInt(t[1]);
          case "Ks" -> cur.specular = (Float.parseFloat(t[1]) + Float.parseFloat(t[2]) + Float.parseFloat(t[3])) / 3f;
          case "Ns" -> cur.shininess = Math.max(1f, Math.min(128f, Float.parseFloat(t[1])));
          case "d" -> {
            cur.alpha = Float.parseFloat(t[1]);
            cur.hasAlpha = true;
          }
          case "map_Kd" -> cur.diffuseMap = cur.map(t);
          case "map_Ks" -> cur.specularMap = cur.map(t);
          case "map_Ke" -> cur.emissiveMap = cur.map(t);
          case "map_d" -> cur.alphaMap = cur.map(t);
          case "disp", "map_disp" -> cur.heightMap = cur.map(t);
          case "map_Bump", "map_bump", "bump", "norm" -> {
            final var options = new MapOptions();
            cur.normalMap = parseMap(t, options);
            cur.normalScale = options.bumpScale;
          }
          default -> {
            // Ps, Pc, aniso, refl ... not used
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

  /** {@code Kd r g b}; a single value means grey. */
  private static float[] color(String[] t) {
    final float r = Float.parseFloat(t[1]);
    return t.length < 4 ? new float[] { r, r, r }
        : new float[] { r, Float.parseFloat(t[2]), Float.parseFloat(t[3]) };
  }

  /** Options of a texture statement. */
  private static final class MapOptions {
    float bumpScale = 1f;
    float[] scale;
    float[] offset;
    boolean clamp;
  }

  /** Reads a texture statement's options into {@code options} and returns the file name. */
  private static String parseMap(String[] t, MapOptions options) {
    int i = 1;
    while (i < t.length) {
      final var s = t[i];
      if (s.equals("-bm")) {
        options.bumpScale = Float.parseFloat(t[i + 1]);
        i += 2;
      } else if (s.equals("-s") || s.equals("-o") || s.equals("-t")) {
        final float[] v = { s.equals("-s") ? 1f : 0f, s.equals("-s") ? 1f : 0f };
        int n = 0;
        i++;
        while (i < t.length && isNumber(t[i])) { // 1 to 3 numbers; u and v are the first two
          if (n < 2) {
            v[n] = Float.parseFloat(t[i]);
          }
          n++;
          i++;
        }
        if (n == 1 && s.equals("-s")) {
          v[1] = v[0]; // one number scales both
        }
        if (s.equals("-s")) {
          options.scale = v;
        } else if (s.equals("-o")) {
          options.offset = v;
        }
      } else if (s.equals("-clamp")) {
        options.clamp = i + 1 < t.length && t[i + 1].equalsIgnoreCase("on");
        i += 2;
      } else if (s.equals("-mm")) {
        i += 3;
      } else if (s.startsWith("-")) {
        i += 2; // -blendu on, -texres 512, -imfchan r, -type sphere ...
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
