package engine.serialization;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.audio.AudioClip;
import engine.ecs.Entity;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.Material;

/**
 * Reads and writes an object's public instance fields by reflection: numbers, booleans, strings, enums, JOML vectors
 * and quaternions (as arrays; final ones are set in place), entities (as references), materials (by index) and
 * meshes, textures and sounds (by resource key). Other fields, and final fields of plain types, are left alone.
 * Superclass fields come first, each class in declaration order, so files are stable.
 */
public final class Fields {
  private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

  private Fields() {
  }

  static List<Field> of(Class<?> type) {
    return FIELDS.computeIfAbsent(type, t -> {
      final List<Class<?>> chain = new ArrayList<>();
      for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
        chain.add(0, c);
      }
      final List<Field> out = new ArrayList<>();
      for (Class<?> c : chain) {
        for (Field f : c.getDeclaredFields()) {
          final int m = f.getModifiers();
          if (Modifier.isPublic(m) && !Modifier.isStatic(m) && !Modifier.isTransient(m) && supported(f)) {
            out.add(f);
          }
        }
      }
      return out;
    });
  }

  private static boolean supported(Field f) {
    final var t = f.getType();
    final boolean fin = Modifier.isFinal(f.getModifiers());
    if (t == Vector2f.class || t == Vector3f.class || t == Vector4f.class || t == Quaternionf.class) {
      return true;
    }
    if (fin) {
      return false;
    }
    return t.isPrimitive() || t == String.class || t.isEnum() || t == Entity.class || t == Material.class
        || t == GpuMesh.class || t == GpuTexture.class || t == AudioClip.class;
  }

  /** Writes the supported public fields of {@code obj} into {@code out}, except those named in {@code skip}. */
  public static void write(Object obj, Map<String, Object> out, SerializationContext ctx, Set<String> skip) {
    for (Field f : of(obj.getClass())) {
      if (skip.contains(f.getName())) {
        continue;
      }
      try {
        final var v = toJson(f.get(obj), ctx);
        if (v != null) {
          out.put(f.getName(), v);
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Sets the fields named in {@code in}; unknown names are ignored, so older files still load. */
  public static void read(Object obj, Map<String, Object> in, SerializationContext ctx) {
    for (Field f : of(obj.getClass())) {
      if (!in.containsKey(f.getName())) {
        continue;
      }
      try {
        set(obj, f, in.get(f.getName()), ctx);
      } catch (IllegalAccessException | RuntimeException e) {
        throw new IllegalArgumentException("field " + obj.getClass().getSimpleName() + "." + f.getName() + ": "
            + e.getMessage(), e);
      }
    }
  }

  private static Object toJson(Object v, SerializationContext ctx) {
    if (v == null) {
      return null;
    }
    if (v instanceof Vector2f a) {
      return new float[] { a.x, a.y };
    } else if (v instanceof Vector3f a) {
      return new float[] { a.x, a.y, a.z };
    } else if (v instanceof Vector4f a) {
      return new float[] { a.x, a.y, a.z, a.w };
    } else if (v instanceof Quaternionf a) {
      return new float[] { a.x, a.y, a.z, a.w };
    } else if (v instanceof Entity e) {
      return ctx.entityId(e);
    } else if (v instanceof Material m) {
      return ctx.materialId(m);
    } else if (v instanceof GpuMesh || v instanceof GpuTexture || v instanceof AudioClip) {
      return ctx.key(v);
    } else if (v instanceof Character c) {
      return String.valueOf(c);
    }
    return v; // number, boolean, string, enum
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void set(Object obj, Field f, Object v, SerializationContext ctx) throws IllegalAccessException {
    final var t = f.getType();
    if (t == Vector2f.class) {
      final float[] a = Json.asFloats(v);
      ((Vector2f) f.get(obj)).set(a[0], a[1]);
    } else if (t == Vector3f.class) {
      final float[] a = Json.asFloats(v);
      ((Vector3f) f.get(obj)).set(a[0], a[1], a[2]);
    } else if (t == Vector4f.class) {
      final float[] a = Json.asFloats(v);
      ((Vector4f) f.get(obj)).set(a[0], a[1], a[2], a[3]);
    } else if (t == Quaternionf.class) {
      final float[] a = Json.asFloats(v);
      ((Quaternionf) f.get(obj)).set(a[0], a[1], a[2], a[3]);
    } else if (t == float.class) {
      f.setFloat(obj, Json.asFloat(v));
    } else if (t == double.class) {
      f.setDouble(obj, Json.asFloat(v));
    } else if (t == int.class) {
      f.setInt(obj, Json.asInt(v));
    } else if (t == long.class) {
      f.setLong(obj, Math.round(((Number) v).doubleValue()));
    } else if (t == short.class) {
      f.setShort(obj, (short) Json.asInt(v));
    } else if (t == byte.class) {
      f.setByte(obj, (byte) Json.asInt(v));
    } else if (t == boolean.class) {
      f.setBoolean(obj, (Boolean) v);
    } else if (t == char.class) {
      f.setChar(obj, ((String) v).charAt(0));
    } else if (t == String.class) {
      f.set(obj, v);
    } else if (t.isEnum()) {
      f.set(obj, v == null ? null : Enum.valueOf((Class<Enum>) t, (String) v));
    } else if (t == Entity.class) {
      ctx.afterRead(() -> { // the entity may come later in the file
        try {
          f.set(obj, ctx.entity(v));
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      });
    } else if (t == Material.class) {
      f.set(obj, ctx.material(v));
    } else if (t == GpuMesh.class) {
      f.set(obj, ctx.mesh((String) v));
    } else if (t == GpuTexture.class) {
      f.set(obj, ctx.texture((String) v));
    } else if (t == AudioClip.class) {
      f.set(obj, ctx.sound((String) v));
    }
  }
}
