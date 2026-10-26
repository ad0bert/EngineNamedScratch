package engine.resources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import engine.animation.AnimationClip;
import engine.animation.Channel;
import engine.animation.Track;
import engine.render.ImageData;
import engine.render.MeshData;
import engine.serialization.Json;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads glTF 2.0 ({@code .gltf} with embedded or external buffers and images, or binary {@code .glb}) into plain CPU
 * data: the node hierarchy, meshes as {@link MeshData} (one per primitive), metallic-roughness materials, decoded
 * images, skins and animations as {@link AnimationClip}s whose channels name the nodes. {@link GltfModel} turns the
 * result into GPU resources and entities.
 *
 * <p>
 * Texture coordinates are flipped to the engine's bottom-up images, and tangents are computed from them (a file's
 * own tangents assume the other orientation). Supported: triangle primitives with positions, normals (computed if
 * missing), the first uv set, four joints and weights, any index type; normalized integer attributes; sparse
 * accessors are not supported, nor morph targets, cameras, lights or extensions other than
 * {@code KHR_materials_unlit} and {@code KHR_materials_emissive_strength}.
 */
@Slf4j
public final class GltfLoader {
  private static final int GLB_MAGIC = 0x46546C67;
  private static final int CHUNK_JSON = 0x4E4F534A;
  private static final int CHUNK_BIN = 0x004E4942;

  /** A node with its local transform; {@code name} is unique within the asset (made so if needed). */
  public record Node(String name, int parent, int[] children, Vector3f translation, Quaternionf rotation,
      Vector3f scale, int mesh, int skin) {
  }

  public record Primitive(MeshData mesh, int material) {
  }

  public record Mesh(String name, List<Primitive> primitives) {
  }

  /** A texture reference: the image and whether its sampler clamps instead of repeating. */
  public record TextureRef(int image, boolean clamp) {
  }

  /** A metallic-roughness material; texture references are null when absent. */
  public record Material(String name, float[] baseColor, TextureRef baseColorTexture, float metallic,
      float roughness, TextureRef metallicRoughnessTexture, TextureRef normalTexture, float normalScale,
      float[] emissive, TextureRef emissiveTexture, String alphaMode, float alphaCutoff, boolean doubleSided,
      boolean unlit) {
  }

  public record Skin(String name, int[] joints, Matrix4f[] inverseBind) {
  }

  public record Asset(String name, List<Node> nodes, int[] roots, List<Mesh> meshes, List<Material> materials,
      List<ImageData> images, List<Skin> skins, List<AnimationClip> animations) {
  }

  private final Map<String, Object> doc;
  private final List<ByteBuffer> buffers = new ArrayList<>();
  private final String name;

  private GltfLoader(Map<String, Object> doc, ByteBuffer glbBinary, String name, Function<String, byte[]> resolve)
      throws IOException {
    this.doc = doc;
    this.name = name;
    for (Object o : list("buffers")) {
      final var b = Json.asObject(o);
      final var uri = (String) b.get("uri");
      final byte[] data;
      if (uri == null) {
        if (glbBinary == null) {
          throw new IOException(name + ": a buffer without uri outside a .glb");
        }
        this.buffers.add(glbBinary);
        continue;
      }
      data = uri(uri, resolve);
      this.buffers.add(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
    }
  }

  /**
   * Parses a .gltf or .glb file.
   *
   * @param resolve loads a file referred to by a relative uri (external buffers and images)
   */
  public static Asset load(byte[] bytes, String name, Function<String, byte[]> resolve) throws IOException {
    try {
      final var head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      Map<String, Object> doc;
      ByteBuffer bin = null;
      if (bytes.length >= 12 && head.getInt(0) == GLB_MAGIC) {
        if (head.getInt(4) != 2) {
          throw new IOException(name + ": glb version " + head.getInt(4) + " is not supported");
        }
        int at = 12;
        doc = null;
        while (at + 8 <= bytes.length) {
          final int length = head.getInt(at);
          final int type = head.getInt(at + 4);
          if (type == CHUNK_JSON) {
            doc = Json.asObject(Json.parse(new String(bytes, at + 8, length, StandardCharsets.UTF_8)));
          } else if (type == CHUNK_BIN && bin == null) {
            bin = ByteBuffer.wrap(bytes, at + 8, length).slice().order(ByteOrder.LITTLE_ENDIAN);
          }
          at += 8 + length;
        }
        if (doc == null) {
          throw new IOException(name + ": glb without a JSON chunk");
        }
      } else {
        doc = Json.asObject(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
      }
      final var asset = Json.asObject(doc.getOrDefault("asset", Map.of()));
      final var version = String.valueOf(asset.get("version"));
      if (!version.startsWith("2")) {
        throw new IOException(name + ": glTF version " + version + " is not supported");
      }
      return new GltfLoader(doc, bin, name, resolve).read();
    } catch (IllegalArgumentException | IndexOutOfBoundsException | ClassCastException e) {
      throw new IOException(name + ": " + e.getMessage(), e);
    }
  }

  private static byte[] uri(String uri, Function<String, byte[]> resolve) throws IOException {
    if (uri.startsWith("data:")) {
      final int comma = uri.indexOf(',');
      if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
        throw new IOException("only base64 data uris are supported");
      }
      return Base64.getDecoder().decode(uri.substring(comma + 1));
    }
    final var data = resolve.apply(URLDecoder.decode(uri, StandardCharsets.UTF_8));
    if (data == null) {
      throw new IOException("cannot find " + uri);
    }
    return data;
  }

  private List<Object> list(String key) {
    final var v = this.doc.get(key);
    return v == null ? List.of() : Json.asList(v);
  }

  private Map<String, Object> item(String key, int index) {
    return Json.asObject(list(key).get(index));
  }

  private Asset read() throws IOException {
    final var nodes = readNodes();
    final var meshes = new ArrayList<Mesh>();
    for (Object o : list("meshes")) {
      meshes.add(readMesh(Json.asObject(o)));
    }
    final var materials = new ArrayList<Material>();
    for (Object o : list("materials")) {
      materials.add(readMaterial(Json.asObject(o)));
    }
    final var images = new ArrayList<ImageData>();
    for (Object o : list("images")) {
      images.add(readImage(Json.asObject(o)));
    }
    final var skins = new ArrayList<Skin>();
    for (Object o : list("skins")) {
      final var s = Json.asObject(o);
      final int[] joints = Json.asInts(s.get("joints"));
      final var ibm = new Matrix4f[joints.length];
      final float[] m = s.containsKey("inverseBindMatrices") ? floats(Json.asInt(s.get("inverseBindMatrices")))
          : null;
      for (int j = 0; j < joints.length; j++) {
        ibm[j] = m != null ? new Matrix4f().set(m, j * 16) : new Matrix4f();
      }
      skins.add(new Skin((String) s.get("name"), joints, ibm));
    }
    final var animations = new ArrayList<AnimationClip>();
    final var list = list("animations");
    for (int i = 0; i < list.size(); i++) {
      animations.add(readAnimation(Json.asObject(list.get(i)), i, nodes));
    }
    final int[] roots;
    final var scenes = list("scenes");
    if (!scenes.isEmpty()) {
      final int scene = this.doc.containsKey("scene") ? Json.asInt(this.doc.get("scene")) : 0;
      roots = Json.asInts(Json.asObject(scenes.get(scene)).getOrDefault("nodes", List.of()));
    } else {
      roots = nodes.stream().filter(n -> n.parent() < 0).mapToInt(nodes::indexOf).toArray();
    }
    return new Asset(this.name, nodes, roots, meshes, materials, images, skins, animations);
  }

  private List<Node> readNodes() {
    final var raw = list("nodes");
    final int[] parents = new int[raw.size()];
    Arrays.fill(parents, -1);
    for (int i = 0; i < raw.size(); i++) {
      for (int c : Json.asInts(Json.asObject(raw.get(i)).getOrDefault("children", List.of()))) {
        parents[c] = i;
      }
    }
    final Set<String> used = new HashSet<>();
    final List<Node> out = new ArrayList<>();
    for (int i = 0; i < raw.size(); i++) {
      final var n = Json.asObject(raw.get(i));
      String nodeName = (String) n.get("name");
      if (nodeName == null || nodeName.isEmpty()) {
        nodeName = "node" + i;
      }
      if (!used.add(nodeName)) {
        nodeName = nodeName + "#" + i; // channels find nodes by name: keep names unique
        used.add(nodeName);
      }
      final var t = new Vector3f();
      final var r = new Quaternionf();
      final var s = new Vector3f(1f);
      if (n.containsKey("matrix")) {
        final var m = new Matrix4f().set(Json.asFloats(n.get("matrix")));
        m.getTranslation(t);
        m.getUnnormalizedRotation(r).normalize();
        m.getScale(s);
      } else {
        if (n.containsKey("translation")) {
          final float[] v = Json.asFloats(n.get("translation"));
          t.set(v[0], v[1], v[2]);
        }
        if (n.containsKey("rotation")) {
          final float[] v = Json.asFloats(n.get("rotation"));
          r.set(v[0], v[1], v[2], v[3]).normalize();
        }
        if (n.containsKey("scale")) {
          final float[] v = Json.asFloats(n.get("scale"));
          s.set(v[0], v[1], v[2]);
        }
      }
      out.add(new Node(nodeName, parents[i], Json.asInts(n.getOrDefault("children", List.of())), t, r, s,
          n.containsKey("mesh") ? Json.asInt(n.get("mesh")) : -1, n.containsKey("skin") ? Json.asInt(n.get("skin"))
              : -1));
    }
    return out;
  }

  private Mesh readMesh(Map<String, Object> m) {
    final List<Primitive> out = new ArrayList<>();
    for (Object o : Json.asList(m.get("primitives"))) {
      final var p = Json.asObject(o);
      final int mode = p.containsKey("mode") ? Json.asInt(p.get("mode")) : 4;
      if (mode != 4) {
        log.warn("{}: primitive mode {} is not supported (only triangles), skipped", this.name, mode);
        continue;
      }
      final var attributes = Json.asObject(p.get("attributes"));
      final float[] pos = floats(Json.asInt(attributes.get("POSITION")));
      final int n = pos.length / 3;
      final float[] nor = attributes.containsKey("NORMAL") ? floats(Json.asInt(attributes.get("NORMAL"))) : null;
      final float[] uv = attributes.containsKey("TEXCOORD_0") ? floats(Json.asInt(attributes.get("TEXCOORD_0")))
          : new float[n * 2];
      int[] indices;
      if (p.containsKey("indices")) {
        indices = ints(Json.asInt(p.get("indices")));
      } else {
        indices = new int[n];
        for (int i = 0; i < n; i++) {
          indices[i] = i;
        }
      }
      final float[] normals = nor != null ? nor : smoothNormals(pos, indices);
      final float[] v = new float[n * MeshData.INPUT_FLOATS_PER_VERTEX];
      for (int i = 0; i < n; i++) {
        final int o8 = i * MeshData.INPUT_FLOATS_PER_VERTEX;
        System.arraycopy(pos, i * 3, v, o8, 3);
        System.arraycopy(normals, i * 3, v, o8 + 3, 3);
        v[o8 + 6] = uv[i * 2];
        v[o8 + 7] = 1f - uv[i * 2 + 1]; // glTF images are top-down, the engine's bottom-up
      }
      var data = MeshData.triangles(v, indices);
      if (attributes.containsKey("JOINTS_0") && attributes.containsKey("WEIGHTS_0")) {
        final float[] joints = floats(Json.asInt(attributes.get("JOINTS_0")));
        final float[] weights = floats(Json.asInt(attributes.get("WEIGHTS_0")));
        final float[] skin = new float[n * MeshData.SKIN_FLOATS_PER_VERTEX];
        for (int i = 0; i < n; i++) {
          System.arraycopy(joints, i * 4, skin, i * 8, 4);
          System.arraycopy(weights, i * 4, skin, i * 8 + 4, 4);
        }
        data = data.withSkin(skin);
      }
      out.add(new Primitive(data, p.containsKey("material") ? Json.asInt(p.get("material")) : -1));
    }
    return new Mesh((String) m.get("name"), out);
  }

  private static float[] smoothNormals(float[] pos, int[] indices) {
    final float[] n = new float[pos.length];
    for (int t = 0; t + 2 < indices.length; t += 3) {
      final int a = indices[t] * 3;
      final int b = indices[t + 1] * 3;
      final int c = indices[t + 2] * 3;
      final float e1x = pos[b] - pos[a];
      final float e1y = pos[b + 1] - pos[a + 1];
      final float e1z = pos[b + 2] - pos[a + 2];
      final float e2x = pos[c] - pos[a];
      final float e2y = pos[c + 1] - pos[a + 1];
      final float e2z = pos[c + 2] - pos[a + 2];
      final float nx = e1y * e2z - e1z * e2y;
      final float ny = e1z * e2x - e1x * e2z;
      final float nz = e1x * e2y - e1y * e2x;
      for (int k : new int[] { a, b, c }) { // area-weighted
        n[k] += nx;
        n[k + 1] += ny;
        n[k + 2] += nz;
      }
    }
    for (int i = 0; i < n.length; i += 3) {
      final float len = (float) Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
      if (len > 0f) {
        n[i] /= len;
        n[i + 1] /= len;
        n[i + 2] /= len;
      } else {
        n[i + 1] = 1f;
      }
    }
    return n;
  }

  private Material readMaterial(Map<String, Object> m) {
    final var pbr = Json.asObject(m.getOrDefault("pbrMetallicRoughness", Map.of()));
    final float[] base = pbr.containsKey("baseColorFactor") ? Json.asFloats(pbr.get("baseColorFactor"))
        : new float[] { 1, 1, 1, 1 };
    final float[] emissive = m.containsKey("emissiveFactor") ? Json.asFloats(m.get("emissiveFactor"))
        : new float[] { 0, 0, 0 };
    final var ext = Json.asObject(m.getOrDefault("extensions", Map.of()));
    if (ext.containsKey("KHR_materials_emissive_strength")) {
      final float k = Json.asFloat(Json.asObject(ext.get("KHR_materials_emissive_strength"))
          .getOrDefault("emissiveStrength", 1.0));
      for (int i = 0; i < 3; i++) {
        emissive[i] *= k;
      }
    }
    final var normal = m.containsKey("normalTexture") ? Json.asObject(m.get("normalTexture")) : null;
    return new Material((String) m.get("name"), base, texture(pbr.get("baseColorTexture")),
        Json.asFloat(pbr.getOrDefault("metallicFactor", 1.0)), Json.asFloat(pbr.getOrDefault("roughnessFactor", 1.0)),
        texture(pbr.get("metallicRoughnessTexture")), texture(normal),
        normal != null ? Json.asFloat(normal.getOrDefault("scale", 1.0)) : 1f, emissive,
        texture(m.get("emissiveTexture")), (String) m.getOrDefault("alphaMode", "OPAQUE"),
        Json.asFloat(m.getOrDefault("alphaCutoff", 0.5)), Boolean.TRUE.equals(m.get("doubleSided")),
        ext.containsKey("KHR_materials_unlit"));
  }

  private TextureRef texture(Object info) {
    if (info == null) {
      return null;
    }
    final var tex = item("textures", Json.asInt(Json.asObject(info).get("index")));
    if (!tex.containsKey("source")) {
      log.warn("{}: a texture without an image (extension images are not supported)", this.name);
      return null;
    }
    boolean clamp = false;
    if (tex.containsKey("sampler")) {
      final var s = item("samplers", Json.asInt(tex.get("sampler")));
      clamp = Json.asInt(s.getOrDefault("wrapS", 10497.0)) == 33071 && Json.asInt(s.getOrDefault("wrapT", 10497.0))
          == 33071;
    }
    return new TextureRef(Json.asInt(tex.get("source")), clamp);
  }

  private ImageData readImage(Map<String, Object> img) throws IOException {
    final byte[] bytes;
    if (img.containsKey("bufferView")) {
      final var view = bufferView(Json.asInt(img.get("bufferView")));
      bytes = new byte[view.remaining()];
      view.get(bytes);
    } else {
      bytes = uri((String) img.get("uri"), u -> null);
    }
    return ImageLoader.load(new ByteArrayInputStream(bytes), this.name + " image");
  }

  private AnimationClip readAnimation(Map<String, Object> a, int index, List<Node> nodes) {
    final var clip = new AnimationClip(a.containsKey("name") ? (String) a.get("name") : "animation" + index);
    final var samplers = Json.asList(a.get("samplers"));
    for (Object o : Json.asList(a.get("channels"))) {
      final var ch = Json.asObject(o);
      final var target = Json.asObject(ch.get("target"));
      if (!target.containsKey("node")) {
        continue;
      }
      final var path = (String) target.get("path");
      final Channel.Property property = switch (path) {
      case "translation" -> Channel.Property.POSITION;
      case "rotation" -> Channel.Property.ROTATION;
      case "scale" -> Channel.Property.SCALE;
      default -> null; // morph target weights
      };
      if (property == null) {
        log.warn("{}: animation path {} is not supported", this.name, path);
        continue;
      }
      final var sampler = Json.asObject(samplers.get(Json.asInt(ch.get("sampler"))));
      final float[] times = floats(Json.asInt(sampler.get("input")));
      final float[] values = floats(Json.asInt(sampler.get("output")));
      final var interpolation = switch ((String) sampler.getOrDefault("interpolation", "LINEAR")) {
      case "STEP" -> Track.Interpolation.STEP;
      case "CUBICSPLINE" -> Track.Interpolation.CUBIC_SPLINE;
      default -> Track.Interpolation.LINEAR;
      };
      final var node = nodes.get(Json.asInt(target.get("node")));
      clip.add(Channel.transform(node.name(), property,
          new Track(times, values, property.width, interpolation, null)));
    }
    return clip;
  }

  // ----- accessors

  private ByteBuffer bufferView(int index) {
    final var v = item("bufferViews", index);
    final var buffer = this.buffers.get(Json.asInt(v.get("buffer")));
    final int offset = Json.asInt(v.getOrDefault("byteOffset", 0.0));
    final int length = Json.asInt(v.get("byteLength"));
    return buffer.duplicate().position(offset).limit(offset + length).slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int components(String type) {
    return switch (type) {
    case "SCALAR" -> 1;
    case "VEC2" -> 2;
    case "VEC3" -> 3;
    case "VEC4" -> 4;
    case "MAT2" -> 4;
    case "MAT3" -> 9;
    case "MAT4" -> 16;
    default -> throw new IllegalArgumentException("accessor type " + type);
    };
  }

  private static int size(int componentType) {
    return switch (componentType) {
    case 5120, 5121 -> 1;
    case 5122, 5123 -> 2;
    case 5125, 5126 -> 4;
    default -> throw new IllegalArgumentException("component type " + componentType);
    };
  }

  /** An accessor's elements as floats (normalized integers scaled to 0..1 or -1..1). */
  float[] floats(int accessor) {
    final var a = item("accessors", accessor);
    if (a.containsKey("sparse")) {
      throw new IllegalArgumentException("sparse accessors are not supported");
    }
    final int count = Json.asInt(a.get("count"));
    final int comps = components((String) a.get("type"));
    final int type = Json.asInt(a.get("componentType"));
    final boolean normalized = Boolean.TRUE.equals(a.get("normalized"));
    final float[] out = new float[count * comps];
    if (!a.containsKey("bufferView")) {
      return out; // all zeros by the spec
    }
    final int viewIndex = Json.asInt(a.get("bufferView"));
    final var view = bufferView(viewIndex);
    final int stride = stride(viewIndex, comps * size(type));
    final int base = Json.asInt(a.getOrDefault("byteOffset", 0.0));
    for (int i = 0; i < count; i++) {
      for (int c = 0; c < comps; c++) {
        final int at = base + i * stride + c * size(type);
        out[i * comps + c] = switch (type) {
        case 5126 -> view.getFloat(at);
        case 5121 -> normalized ? (view.get(at) & 0xFF) / 255f : view.get(at) & 0xFF;
        case 5120 -> normalized ? Math.max(view.get(at) / 127f, -1f) : view.get(at);
        case 5123 -> normalized ? (view.getShort(at) & 0xFFFF) / 65535f : view.getShort(at) & 0xFFFF;
        case 5122 -> normalized ? Math.max(view.getShort(at) / 32767f, -1f) : view.getShort(at);
        case 5125 -> (float) (view.getInt(at) & 0xFFFFFFFFL);
        default -> throw new IllegalArgumentException("component type " + type);
        };
      }
    }
    return out;
  }

  /** An accessor's elements as ints (indices). */
  int[] ints(int accessor) {
    final var a = item("accessors", accessor);
    final int count = Json.asInt(a.get("count")) * components((String) a.get("type"));
    final int type = Json.asInt(a.get("componentType"));
    final int[] out = new int[count];
    if (!a.containsKey("bufferView")) {
      return out;
    }
    final int viewIndex = Json.asInt(a.get("bufferView"));
    final var view = bufferView(viewIndex);
    final int stride = stride(viewIndex, size(type));
    final int base = Json.asInt(a.getOrDefault("byteOffset", 0.0));
    for (int i = 0; i < count; i++) {
      final int at = base + i * stride;
      out[i] = switch (type) {
      case 5121 -> view.get(at) & 0xFF;
      case 5123 -> view.getShort(at) & 0xFFFF;
      case 5125 -> view.getInt(at);
      default -> throw new IllegalArgumentException("index component type " + type);
      };
    }
    return out;
  }

  private int stride(int viewIndex, int elementSize) {
    final var v = item("bufferViews", viewIndex);
    return v.containsKey("byteStride") ? Json.asInt(v.get("byteStride")) : elementSize;
  }
}
