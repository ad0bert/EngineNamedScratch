package engine.render.gl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL3;

import engine.render.Light;

/** Compiled and linked GLSL program loaded from classpath resources. */
final class ShaderProgram {
  private final int id;
  private final Map<String, Integer> uniforms = new HashMap<>();
  private final FloatBuffer buf16 = Buffers.newDirectFloatBuffer(16);
  private FloatBuffer bufArray = Buffers.newDirectFloatBuffer(16 * Light.MAX_CASCADES);

  ShaderProgram(GL3 gl, String vertexResource, String fragmentResource) {
    final int vs = compile(gl, GL3.GL_VERTEX_SHADER, vertexResource);
    final int fs = compile(gl, GL3.GL_FRAGMENT_SHADER, fragmentResource);
    this.id = gl.glCreateProgram();
    gl.glAttachShader(this.id, vs);
    gl.glAttachShader(this.id, fs);
    gl.glLinkProgram(this.id);
    final int[] ok = new int[1];
    gl.glGetProgramiv(this.id, GL3.GL_LINK_STATUS, ok, 0);
    if (ok[0] == GL3.GL_FALSE) {
      throw new IllegalStateException("Shader link failed: " + infoLog(gl, this.id, true));
    }
    gl.glDeleteShader(vs);
    gl.glDeleteShader(fs);
  }

  private static int compile(GL3 gl, int type, String resource) {
    final String source;
    try (InputStream in = ShaderProgram.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("Shader resource not found: " + resource);
      }
      source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read shader " + resource, e);
    }
    final int shader = gl.glCreateShader(type);
    gl.glShaderSource(shader, 1, new String[] { source }, new int[] { source.length() }, 0);
    gl.glCompileShader(shader);
    final int[] ok = new int[1];
    gl.glGetShaderiv(shader, GL3.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == GL3.GL_FALSE) {
      throw new IllegalStateException("Shader compile failed (" + resource + "): " + infoLog(gl, shader, false));
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
    gl.glDeleteProgram(this.id);
  }
}
