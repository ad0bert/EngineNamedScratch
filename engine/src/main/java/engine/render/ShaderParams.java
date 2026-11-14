package engine.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Values for a custom shader's uniforms by name: floats, ints, vectors, matrices and textures. The renderer sets them
 * on the shader before drawing (textures are bound to free texture units and the sampler set to them); names the
 * shader does not have are ignored. Values are passed as they are: colours here are not converted from sRGB.
 *
 * <pre>{@code
 * material.params.set("uWaveHeight", 0.3f).set("uDeepColor", new Vector3f(0, 0.1f, 0.2f)).set("uFoam", foamTexture);
 * }</pre>
 *
 * Changed on the update thread like the rest of a material; the renderer reads it on the same thread.
 */
public final class ShaderParams {
  private final Map<String, Object> values = new LinkedHashMap<>();
  private final Map<String, Object> view = Collections.unmodifiableMap(this.values);

  public ShaderParams set(String name, float value) {
    this.values.put(name, value);
    return this;
  }

  public ShaderParams set(String name, int value) {
    this.values.put(name, value);
    return this;
  }

  public ShaderParams set(String name, Vector2fc value) {
    this.values.put(name, new Vector2f(value));
    return this;
  }

  public ShaderParams set(String name, Vector3fc value) {
    this.values.put(name, new Vector3f(value));
    return this;
  }

  public ShaderParams set(String name, Vector4fc value) {
    this.values.put(name, new Vector4f(value));
    return this;
  }

  public ShaderParams set(String name, Matrix4fc value) {
    this.values.put(name, new Matrix4f(value));
    return this;
  }

  /** A texture for a {@code sampler2D} uniform; null removes it. */
  public ShaderParams set(String name, GpuTexture texture) {
    if (texture == null) {
      this.values.remove(name);
    } else {
      this.values.put(name, texture);
    }
    return this;
  }

  public ShaderParams remove(String name) {
    this.values.remove(name);
    return this;
  }

  public ShaderParams clear() {
    this.values.clear();
    return this;
  }

  /** The value of {@code name}: a Float, Integer, Vector2f/3f/4f, Matrix4f or GpuTexture; null if not set. */
  public Object get(String name) {
    return this.values.get(name);
  }

  public boolean isEmpty() {
    return this.values.isEmpty();
  }

  public int size() {
    return this.values.size();
  }

  /** Every value, in the order first set. Read-only; vectors and matrices must not be changed through it. */
  public Map<String, Object> values() {
    return this.view;
  }

  public void forEach(BiConsumer<String, Object> action) {
    this.values.forEach(action);
  }

  /** Copies every value of {@code other} into this one. */
  public ShaderParams setAll(ShaderParams other) {
    other.values.forEach(this.values::put);
    return this;
  }
}
