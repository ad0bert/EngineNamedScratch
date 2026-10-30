package engine.serialization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import engine.animation.AnimationLayer;
import engine.animation.Animator;
import engine.audio.AudioListener;
import engine.audio.AudioSource;
import engine.audio.ImpactSound;
import engine.ecs.Component;
import engine.ecs.Spinner;
import engine.input.FirstPersonController;
import engine.physics.BallJoint;
import engine.physics.BoxCollider;
import engine.physics.CapsuleCollider;
import engine.physics.CharacterController;
import engine.physics.DistanceJoint;
import engine.physics.HingeJoint;
import engine.physics.MeshCollider;
import engine.physics.Rigidbody;
import engine.physics.SphereCollider;
import engine.render.Camera;
import engine.render.Light;
import engine.render.MeshRenderer;
import engine.render.ParticleEmitter;

/**
 * Which component types can be saved, and how. {@link #defaults()} knows the engine's own components; games register
 * serializers for theirs. Components without a serializer are left out of saved files (logged once per type).
 */
public class ComponentRegistry {
  private final Map<String, ComponentSerializer<?>> byType = new LinkedHashMap<>();
  private final Map<Class<?>, ComponentSerializer<?>> byClass = new HashMap<>();

  /**
   * The engine's components: rendering (skins included), lights, camera, physics (colliders, bodies, joints,
   * characters), audio, animators, controllers.
   */
  public static ComponentRegistry defaults() {
    final var r = new ComponentRegistry();
    r.register(new MeshRendererSerializer());
    r.register(new FieldSerializer<>("Camera", Camera.class, ctx -> new Camera()));
    r.register(new FieldSerializer<>("Light", Light.class, ctx -> new Light()));
    r.register(new FieldSerializer<>("ParticleEmitter", ParticleEmitter.class, ctx -> new ParticleEmitter()));
    r.register(new FieldSerializer<>("Rigidbody", Rigidbody.class, ctx -> new Rigidbody()));
    r.register(new FieldSerializer<>("BoxCollider", BoxCollider.class, ctx -> new BoxCollider()));
    r.register(new FieldSerializer<>("SphereCollider", SphereCollider.class, ctx -> new SphereCollider()));
    r.register(new FieldSerializer<>("CapsuleCollider", CapsuleCollider.class, ctx -> new CapsuleCollider()));
    r.register(new FieldSerializer<>("CharacterController", CharacterController.class,
        ctx -> new CharacterController()));
    r.register(new MeshColliderSerializer());
    r.register(new FieldSerializer<>("BallJoint", BallJoint.class, ctx -> new BallJoint()));
    r.register(new FieldSerializer<>("HingeJoint", HingeJoint.class, ctx -> new HingeJoint()));
    r.register(new FieldSerializer<>("DistanceJoint", DistanceJoint.class, ctx -> new DistanceJoint()));
    r.register(new AudioSourceSerializer());
    r.register(new AnimatorSerializer());
    r.register(new FieldSerializer<>("AudioListener", AudioListener.class, ctx -> new AudioListener()));
    r.register(new FieldSerializer<>("ImpactSound", ImpactSound.class, ctx -> new ImpactSound()));
    r.register(new FieldSerializer<>("Spinner", Spinner.class, ctx -> new Spinner(0f, 0f, 0f)));
    r.register(new FirstPersonSerializer());
    return r;
  }

  /** Adds or replaces the serializer for its type name and class. */
  public ComponentRegistry register(ComponentSerializer<?> serializer) {
    this.byType.put(serializer.type(), serializer);
    this.byClass.put(serializer.componentClass(), serializer);
    return this;
  }

  /** The serializer for exactly this class (subclasses need their own, or they would lose their extra state). */
  @SuppressWarnings("unchecked")
  public <T extends Component> ComponentSerializer<T> forClass(Class<T> type) {
    return (ComponentSerializer<T>) this.byClass.get(type);
  }

  public ComponentSerializer<?> forType(String type) {
    return this.byType.get(type);
  }

  /** Renderers keep their mesh by resource key and their material by index into the file's shared materials. */
  static final class MeshRendererSerializer implements ComponentSerializer<MeshRenderer> {
    @Override
    public String type() {
      return "MeshRenderer";
    }

    @Override
    public Class<MeshRenderer> componentClass() {
      return MeshRenderer.class;
    }

    @Override
    public Map<String, Object> write(MeshRenderer r, SerializationContext ctx) {
      final var mesh = ctx.key(r.mesh);
      if (mesh == null) {
        return null; // generated without a key: cannot be found again
      }
      final Map<String, Object> out = new LinkedHashMap<>();
      out.put("mesh", mesh);
      out.put("material", ctx.materialId(r.material));
      out.put("castShadows", r.castShadows);
      out.put("staticGeometry", r.isStaticGeometry());
      if (r.skin != null) {
        final var skin = ctx.skinId(r.skin);
        if (skin != null) {
          out.put("skin", skin);
        }
      }
      return out;
    }

    @Override
    public MeshRenderer read(Map<String, Object> data, SerializationContext ctx) {
      final var mesh = ctx.mesh((String) data.get("mesh"));
      if (mesh == null) {
        throw new IllegalArgumentException("mesh " + data.get("mesh") + " not found");
      }
      final var r = new MeshRenderer(mesh, ctx.material(data.get("material")));
      r.castShadows = !Boolean.FALSE.equals(data.get("castShadows"));
      r.setStaticGeometry(Boolean.TRUE.equals(data.get("staticGeometry")));
      if (data.get("skin") != null) {
        ctx.afterRead(() -> r.skin = ctx.skin(data.get("skin"))); // the joints may come later in the file
      }
      return r;
    }
  }

  /** The triangles are written into the file, so the collider does not depend on a render mesh. */
  static final class MeshColliderSerializer implements ComponentSerializer<MeshCollider> {
    @Override
    public String type() {
      return "MeshCollider";
    }

    @Override
    public Class<MeshCollider> componentClass() {
      return MeshCollider.class;
    }

    @Override
    public Map<String, Object> write(MeshCollider c, SerializationContext ctx) {
      final Map<String, Object> out = new LinkedHashMap<>();
      Fields.write(c, out, ctx, Set.of());
      out.put("positions", c.positions());
      out.put("indices", c.indices());
      return out;
    }

    @Override
    public MeshCollider read(Map<String, Object> data, SerializationContext ctx) {
      final var c = new MeshCollider(Json.asFloats(data.get("positions")), Json.asInts(data.get("indices")));
      Fields.read(c, data, ctx);
      return c;
    }
  }

  /**
   * The settings, and whether it is playing or paused and where: a loaded source carries on from there ({@code
   * playOnStart} does not restart it).
   */
  static final class AudioSourceSerializer extends FieldSerializer<AudioSource> {
    AudioSourceSerializer() {
      super("AudioSource", AudioSource.class, ctx -> new AudioSource());
    }

    @Override
    protected void writeExtra(AudioSource s, Map<String, Object> out, SerializationContext ctx) {
      if (s.isPlaying() || s.isPaused()) {
        out.put(s.isPaused() ? "paused" : "playing", true);
        out.put("time", s.time());
      }
    }

    @Override
    protected void readExtra(AudioSource s, Map<String, Object> data, SerializationContext ctx) {
      final boolean paused = Boolean.TRUE.equals(data.get("paused"));
      if ((paused || Boolean.TRUE.equals(data.get("playing"))) && s.clip != null) {
        s.play(Json.asFloat(data.getOrDefault("time", 0.0)));
        if (paused) {
          s.pause();
        }
      }
    }
  }

  /**
   * The clip library and the layers playing, each with its clip, loop mode, speed, weight, clock and fade; clips are
   * written once per file and shared (see {@link SerializationContext#clipId}).
   */
  static final class AnimatorSerializer implements ComponentSerializer<Animator> {
    @Override
    public String type() {
      return "Animator";
    }

    @Override
    public Class<Animator> componentClass() {
      return Animator.class;
    }

    @Override
    public Map<String, Object> write(Animator a, SerializationContext ctx) {
      final Map<String, Object> out = new LinkedHashMap<>();
      out.put("speed", a.speed);
      final List<Object> library = new ArrayList<>();
      for (final var clip : a.clips().values()) {
        library.add(ctx.clipId(clip));
      }
      out.put("clips", library);
      final List<Object> layers = new ArrayList<>();
      for (final var l : a.layers()) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("clip", ctx.clipId(l.clip()));
        m.put("loop", l.loop().name());
        m.put("speed", l.speed);
        m.put("weight", l.weight);
        m.put("time", l.time());
        m.put("fade", l.fade());
        m.put("fadeSpeed", l.fadeSpeed());
        layers.add(m);
      }
      out.put("layers", layers);
      return out;
    }

    @Override
    public Animator read(Map<String, Object> data, SerializationContext ctx) {
      final var a = new Animator();
      a.speed = Json.asFloat(data.getOrDefault("speed", 1.0));
      for (final Object id : Json.asList(data.getOrDefault("clips", List.of()))) {
        a.addClip(ctx.clip(id));
      }
      for (final Object o : Json.asList(data.getOrDefault("layers", List.of()))) {
        final var m = Json.asObject(o);
        final var layer = a.blend(ctx.clip(m.get("clip")), Json.asFloat(m.getOrDefault("weight", 1.0)),
            AnimationLayer.Loop.valueOf((String) m.getOrDefault("loop", "LOOP")));
        layer.speed = Json.asFloat(m.getOrDefault("speed", 1.0));
        layer.setTime(Json.asFloat(m.getOrDefault("time", 0.0)));
        layer.setFade(Json.asFloat(m.getOrDefault("fade", 1.0)), Json.asFloat(m.getOrDefault("fadeSpeed", 0.0)));
      }
      return a;
    }
  }

  /** Needs the engine's input state, and keeps the view direction. */
  static final class FirstPersonSerializer extends FieldSerializer<FirstPersonController> {
    FirstPersonSerializer() {
      super("FirstPersonController", FirstPersonController.class, ctx -> {
        if (ctx.input() == null) {
          throw new IllegalArgumentException("a FirstPersonController needs the input state in the context");
        }
        return new FirstPersonController(ctx.input());
      });
    }

    @Override
    protected void writeExtra(FirstPersonController c, Map<String, Object> out, SerializationContext ctx) {
      out.put("yaw", c.yaw());
      out.put("pitch", c.pitch());
    }

    @Override
    protected void readExtra(FirstPersonController c, Map<String, Object> data, SerializationContext ctx) {
      c.setLook(Json.asFloat(data.getOrDefault("yaw", 0.0)), Json.asFloat(data.getOrDefault("pitch", 0.0)));
    }
  }
}
