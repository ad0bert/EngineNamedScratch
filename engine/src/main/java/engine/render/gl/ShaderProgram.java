package engine.render.gl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.Light;

/**
 * Compiled and linked GLSL program, from the shader source ({@link #setSource}) or the classpath resources. Live
 * programs are kept in a registry so they can be compiled again (hot reloading); a program that fails to compile then
 * keeps its old version.
 */
final class ShaderProgram {
  private static final Logger log = LoggerFactory.getLogger(ShaderProgram.class);
  private static final List<ShaderProgram> LIVE = new ArrayList<>();
  private static volatile Function<String, String> source = path -> null;

  private int id;
  private final String vertexResource;
  private final String fragmentResource;
  private final Map<String, Integer> uniforms = new HashMap<>();
  private final FloatBuffer buf16 = Buffers.newDirectFloatBuffer(16);
  private FloatBuffer bufArray = Buffers.newDirectFloatBuffer(16 * Light.MAX_CASCADES);

  ShaderProgram(GL3 gl, String vertexResource, String fragmentResource) {
    this.vertexResource = vertexResource;
    this.fragmentResource = fragmentResource;
    this.id = link(gl, vertexResource, fragmentResource);
    LIVE.add(this);
  }

  /** Shader text by resource path, or null for the built-in one. */
  static void setSource(Function<String, String> value) {
    source = value != null ? value : path -> null;
  }

  /** Compiles the programs using {@code resource} (all for null) again; returns how many failed. */
  static int reloadAll(GL3 gl, String resource) {
    int failed = 0;
    int done = 0;
    for (final var p : LIVE) {
      if (resource == null || resource.equals(p.vertexResource) || resource.equals(p.fragmentResource)) {
        done++;
        if (!p.reload(gl)) {
          failed++;
        }
      }
    }
    if (done > 0) {
      log.info("Reloaded {} shader program(s) for {} ({} failed)", done, resource != null ? resource : "all",
          failed);
    }
    return failed;
  }

  /** Forgets every program (the context is going away). */
  static void forgetAll() {
    LIVE.clear();
  }

  /**
   * Links the program again from the current sources. On success the old program is deleted and the uniforms that
   * are set once (single ints and floats, such as sampler units) are carried over; on failure it keeps running.
   */
  boolean reload(GL3 gl) {
    final int fresh;
    try {
      fresh = link(gl, this.vertexResource, this.fragmentResource);
    } catch (IllegalStateException | IllegalArgumentException e) {
      log.error("{} + {} did not compile; keeping the old version: {}", this.vertexResource, this.fragmentResource,
          e.getMessage());
      return false;
    }
    copyUniforms(gl, this.id, fresh);
    gl.glDeleteProgram(this.id);
    this.id = fresh;
    this.uniforms.clear();
    return true;
  }

  private static void copyUniforms(GL3 gl, int from, int to) {
    final int[] count = new int[1];
    gl.glGetProgramiv(from, GL3.GL_ACTIVE_UNIFORMS, count, 0);
    final byte[] name = new byte[256];
    final int[] length = new int[1];
    final int[] size = new int[1];
    final int[] type = new int[1];
    final int[] ints = new int[4];
    final float[] floats = new float[4];
    gl.glUseProgram(to);
    for (int i = 0; i < count[0]; i++) {
      gl.glGetActiveUniform(from, i, name.length, length, 0, size, 0, type, 0, name, 0);
      if (size[0] != 1) {
        continue; // arrays are uploaded every frame
      }
      final String n = new String(name, 0, length[0], StandardCharsets.US_ASCII);
      final int src = gl.glGetUniformLocation(from, n);
      final int dst = gl.glGetUniformLocation(to, n);
      if (src < 0 || dst < 0) {
        continue;
      }
      switch (type[0]) {
        case GL3.GL_INT, GL3.GL_BOOL, GL3.GL_SAMPLER_2D, GL3.GL_SAMPLER_3D, GL3.GL_SAMPLER_CUBE,
            GL3.GL_SAMPLER_2D_ARRAY, GL3.GL_SAMPLER_2D_SHADOW, GL3.GL_SAMPLER_2D_ARRAY_SHADOW, GL3.GL_SAMPLER_BUFFER,
            GL3.GL_SAMPLER_CUBE_SHADOW -> {
          gl.glGetUniformiv(from, src, ints, 0);
          gl.glUniform1i(dst, ints[0]);
        }
        case GL3.GL_FLOAT -> {
          gl.glGetUniformfv(from, src, floats, 0);
          gl.glUniform1f(dst, floats[0]);
        }
        case GL3.GL_FLOAT_VEC2 -> {
          gl.glGetUniformfv(from, src, floats, 0);
          gl.glUniform2f(dst, floats[0], floats[1]);
        }
        case GL3.GL_FLOAT_VEC3 -> {
          gl.glGetUniformfv(from, src, floats, 0);
          gl.glUniform3f(dst, floats[0], floats[1], floats[2]);
        }
        case GL3.GL_FLOAT_VEC4 -> {
          gl.glGetUniformfv(from, src, floats, 0);
          gl.glUniform4f(dst, floats[0], floats[1], floats[2], floats[3]);
        }
        default -> {
          // matrices and the rest are set before each use
        }
      }
    }
    gl.glUseProgram(0);
  }

  private static int link(GL3 gl, String vertexResource, String fragmentResource) {
    final int vs = compile(gl, GL3.GL_VERTEX_SHADER, vertexResource);
    final int fs;
    try {
      fs = compile(gl, GL3.GL_FRAGMENT_SHADER, fragmentResource);
    } catch (RuntimeException e) {
      gl.glDeleteShader(vs);
      throw e;
    }
    final int id = gl.glCreateProgram();
    gl.glAttachShader(id, vs);
    gl.glAttachShader(id, fs);
    gl.glLinkProgram(id);
    gl.glDeleteShader(vs);
    gl.glDeleteShader(fs);
    final int[] ok = new int[1];
    gl.glGetProgramiv(id, GL3.GL_LINK_STATUS, ok, 0);
    if (ok[0] == GL3.GL_FALSE) {
      final String message = infoLog(gl, id, true);
      gl.glDeleteProgram(id);
      throw new IllegalStateException("Shader link failed: " + message);
    }
    return id;
  }

  private static String read(String resource) {
    final String override = source.apply(resource);
    if (override != null) {
      return override;
    }
    try (InputStream in = ShaderProgram.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("Shader resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read shader " + resource, e);
    }
  }

  private static int compile(GL3 gl, int type, String resource) {
    final String source = read(resource);
    final int shader = gl.glCreateShader(type);
    gl.glShaderSource(shader, 1, new String[] { source }, new int[] { source.length() }, 0);
    gl.glCompileShader(shader);
    final int[] ok = new int[1];
    gl.glGetShaderiv(shader, GL3.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == GL3.GL_FALSE) {
      final String message = infoLog(gl, shader, false);
      gl.glDeleteShader(shader);
      throw new IllegalStateException("Shader compile failed (" + resource + "): " + message);
    }
    return shader;
  }

  private static String infoLog(GL3 gl, int object, boolean program) {
    final int[] len = new int[1];
    if (program) {
      gl.glGetProgramiv(object, GL3.GL_INFO_LOG_LENGTH, len, 0);
    } else {
      gl.glGetShaderiv(object, GL3.GL_INFO_LOG_LENGTH, len, 0);
    }
    final byte[] log = new byte[Math.max(len[0], 1)];
    final int[] written = new int[1];
    if (program) {
      gl.glGetProgramInfoLog(object, log.length, written, 0, log, 0);
    } else {
      gl.glGetShaderInfoLog(object, log.length, written, 0, log, 0);
    }
    return new String(log, 0, written[0], StandardCharsets.UTF_8);
  }

  void use(GL3 gl) {
    gl.glUseProgram(this.id);
  }

  private int loc(GL3 gl, String name) {
    return this.uniforms.computeIfAbsent(name, n -> gl.glGetUniformLocation(this.id, n));
  }

  void set(GL3 gl, String name, Matrix4f m) {
    m.get(this.buf16.clear());
    gl.glUniformMatrix4fv(loc(gl, name), 1, false, this.buf16);
  }

  void set(GL3 gl, String name, Vector3f v) {
    gl.glUniform3f(loc(gl, name), v.x, v.y, v.z);
  }

  void set(GL3 gl, String name, float x, float y) {
    gl.glUniform2f(loc(gl, name), x, y);
  }

  void set(GL3 gl, String name, float x, float y, float z) {
    gl.glUniform3f(loc(gl, name), x, y, z);
  }

  void set(GL3 gl, String name, Vector4f v) {
    gl.glUniform4f(loc(gl, name), v.x, v.y, v.z, v.w);
  }

  void set(GL3 gl, String name, float v) {
    gl.glUniform1f(loc(gl, name), v);
  }

  void set(GL3 gl, String name, int v) {
    gl.glUniform1i(loc(gl, name), v);
  }

  /** Uploads {@code count} vec4s to a {@code vec4 name[N]} uniform array. */
  void setVec4Array(GL3 gl, String name, float[] data, int count) {
    if (count > 0) {
      gl.glUniform4fv(loc(gl, name), count, data, 0);
    }
  }

  /** Uploads the first {@code count} matrices to a {@code mat4 name[N]} uniform array. */
  void setMat4Array(GL3 gl, String name, Matrix4f[] matrices, int count) {
    if (this.bufArray.capacity() < count * 16) {
      this.bufArray = Buffers.newDirectFloatBuffer(count * 16);
    }
    this.bufArray.clear();
    for (int i = 0; i < count; i++) {
      matrices[i].get(i * 16, this.bufArray);
    }
    this.bufArray.position(0).limit(count * 16);
    gl.glUniformMatrix4fv(loc(gl, name), count, false, this.bufArray);
  }

  /** Uploads {@code count} ints to an {@code int name[N]} uniform array. */
  void setIntArray(GL3 gl, String name, int[] data, int count) {
    if (count > 0) {
      gl.glUniform1iv(loc(gl, name), count, data, 0);
    }
  }

  /** Uploads {@code count} vec2s to a {@code vec2 name[N]} uniform array. */
  void setVec2Array(GL3 gl, String name, float[] data, int count) {
    if (count > 0) {
      gl.glUniform2fv(loc(gl, name), count, data, 0);
    }
  }

  /** Uploads the first {@code count} floats to a {@code float name[N]} uniform array. */
  void setFloatArray(GL3 gl, String name, float[] data, int count) {
    if (count > 0) {
      gl.glUniform1fv(loc(gl, name), count, data, 0);
    }
  }

  void dispose(GL3 gl) {
    LIVE.remove(this);
    gl.glDeleteProgram(this.id);
  }
}
