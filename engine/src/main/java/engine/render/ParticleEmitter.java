package engine.render;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.SplittableRandom;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import engine.ecs.Component;

/**
 * Spawns, moves and draws particles: small camera-facing quads (billboards) for sparks, smoke, fire, dust or magic.
 * The particles are simulated at every fixed step (they stop while the game is paused) and live in one flat array per
 * emitter, not as entities, so thousands cost little; the renderer draws each emitter with one instanced call,
 * interpolated between fixed steps like everything else.
 *
 * <p>
 * Particles are born at the entity's position (within {@link #shapeRadius}), fly off along {@link #direction} within
 * a cone of {@link #spreadDegrees}, fall with {@link #acceleration}, slow down with {@link #drag} and change size and
 * colour over their life. While the emitter moves they are spread along its path, so trails stay continuous.
 *
 * <p>
 * Particles are unlit and do not cast shadows. They blend {@link Blend#ADDITIVE additively} (fire, sparks, glows: the
 * order does not matter) or {@link Blend#ALPHA over the scene} (smoke, dust: sorted back to front per emitter, and the
 * emitters among themselves). They fade where they meet opaque geometry ({@link #softness}) instead of showing a hard
 * edge, and are drawn after all other transparent surfaces. A {@link #brightness} above 1 makes them glow through
 * bloom.
 *
 * <p>
 * Colours are sRGB like {@link Material#color}; without a {@link #texture} each particle is a soft round dot. A texture
 * may be an atlas of {@link #atlasColumns} x {@link #atlasRows} frames, ordered left to right and top to bottom as
 * painted, played over each particle's life ({@link #animateFrames}) or picked at random per particle.
 */
public class ParticleEmitter extends Component {
  /** How particles combine with what is behind them. */
  public enum Blend {
    /** Adds their light (fire, sparks, magic); never darkens, needs no sorting. */
    ADDITIVE,
    /** Covers what is behind by their alpha (smoke, dust, leaves); sorted back to front. */
    ALPHA
  }

  /**
   * Floats per particle in {@link #writeInstances}: position and size, colour (linear, premultiplied by alpha, with
   * {@link #brightness}; alpha 0 for additive particles), rotation in radians and atlas frame.
   */
  public static final int FLOATS_PER_INSTANCE = 10;

  // per-particle simulation state, STRIDE floats each
  private static final int X = 0;
  private static final int Y = 1;
  private static final int Z = 2;
  private static final int PX = 3; // position at the previous step, for interpolation
  private static final int PY = 4;
  private static final int PZ = 5;
  private static final int VX = 6;
  private static final int VY = 7;
  private static final int VZ = 8;
  private static final int AGE = 9;
  private static final int LIFE = 10;
  private static final int SIZE = 11; // size multiplier (variation)
  private static final int ROT = 12;
  private static final int PROT = 13;
  private static final int SPIN = 14;
  private static final int FRAME = 15; // random atlas frame
  private static final int STRIDE = 16;

  /** Most particles alive at once; spawning stops while it is reached. */
  public int maxParticles = 256;
  /** Whether {@link #rate} spawns particles; the living ones finish their life either way. */
  public boolean emitting = true;
  /** Particles spawned per second while {@link #emitting}. */
  public float rate = 20f;
  /** Seconds a particle lives, picked between these. */
  public float lifetimeMin = 1f;
  public float lifetimeMax = 2f;
  /** Starting speed in units per second, picked between these. */
  public float speedMin = 1f;
  public float speedMax = 2f;
  /** Main direction of flight, in the entity's local space (turns with it). */
  public final Vector3f direction = new Vector3f(0f, 1f, 0f);
  /** Half angle of the cone particles fly off in, degrees: 0 a straight jet, 180 every direction. */
  public float spreadDegrees = 20f;
  /** Particles are born anywhere inside a sphere of this radius around the entity; 0 for a point. */
  public float shapeRadius;
  /** Constant acceleration, world units per second squared, e.g. (0, -9.81, 0) for gravity or (0, 1, 0) to rise. */
  public final Vector3f acceleration = new Vector3f();
  /** Air resistance: the fraction of their speed particles lose per second (roughly; 0 for none). */
  public float drag;
  /** Edge length of a particle at birth and at the end of its life, world units. */
  public float sizeStart = 0.2f;
  public float sizeEnd = 0.2f;
  /** Random size spread per particle, 0..1 (0.25: each one 75% to 125% of the size). */
  public float sizeVariation;
  /** Colour (sRGB) and alpha at birth and at the end of life; blended in between. */
  public final Vector4f colorStart = new Vector4f(1f, 1f, 1f, 1f);
  public final Vector4f colorEnd = new Vector4f(1f, 1f, 1f, 0f);
  /** Part of the life (0..1) over which a particle fades in from transparent, so it does not pop up. */
  public float fadeIn;
  /** Multiplies the colour; above 1 particles are brighter than white and glow (bloom). */
  public float brightness = 1f;
  /** Start every particle at a random angle; otherwise upright. */
  public boolean randomRotation = true;
  /** Spin, degrees per second, picked between these (negative turns the other way). */
  public float spinMin;
  public float spinMax;
  public Blend blend = Blend.ALPHA;
  /** Particle image (sRGB, alpha = coverage), or null for a soft round dot. */
  public GpuTexture texture;
  /** The texture is a grid of frames, this many across and down. */
  public int atlasColumns = 1;
  public int atlasRows = 1;
  /** Play the frames over each particle's life; otherwise every particle shows one frame picked at random. */
  public boolean animateFrames = true;
  /**
   * Distance (world units) over which particles fade out in front of opaque geometry, so they do not show a hard
   * line where they cut into the ground or a wall; 0 turns it off. Around the particle size looks right.
   */
  public float softness = 0.25f;
  /**
   * Simulate in the entity's space: the particles move along with the entity (a jet attached to a ship). Off (the
   * default), particles stay where they were born in the world and the emitter leaves a trail. In local space
   * {@link #acceleration} is local too.
   */
  public boolean localSpace;

  private float[] data = new float[0];
  private int count;
  private int pendingBurst;
  private double spawnCarry;
  private float lastDt;
  private SplittableRandom random = new SplittableRandom();

  private final Vector3f origin = new Vector3f();
  private final Vector3f lastOrigin = new Vector3f();
  private boolean hasLastOrigin;
  private final Vector3f axis = new Vector3f();
  private final Vector3f tangent = new Vector3f();
  private final Vector3f bitangent = new Vector3f();
  private final Matrix4f world = new Matrix4f();
  private final Vector3f boundsMin = new Vector3f();
  private final Vector3f boundsMax = new Vector3f();
  private final Vector3f point = new Vector3f();
  private final Vector3f offset = new Vector3f();
  private final Vector4f linearStart = new Vector4f();
  private final Vector4f linearEnd = new Vector4f();
  private long[] order = new long[0];

  /** Fixes the random sequence, for effects that must look the same every time (and for tests). */
  public ParticleEmitter seed(long seed) {
    this.random = new SplittableRandom(seed);
    return this;
  }

  /** Spawns {@code n} particles at once at the next fixed step (an explosion, a puff), emitting or not. */
  public void burst(int n) {
    this.pendingBurst += Math.max(0, n);
  }

  /** Removes every living particle (and a pending burst). */
  public void clear() {
    this.count = 0;
    this.pendingBurst = 0;
    this.spawnCarry = 0;
  }

  /**
   * Call after moving the entity instantly (a teleport): otherwise the particles of the next step are spread along
   * the jump as if the emitter had flown there.
   */
  public void resetPath() {
    this.hasLastOrigin = false;
  }

  /** Number of living particles. */
  public int count() {
    return this.count;
  }

  /** True when the emitter is not emitting and every particle has died: a one-shot effect is over. */
  public boolean isFinished() {
    return !this.emitting && this.count == 0 && this.pendingBurst == 0;
  }

  @Override
  public void update(double dt) {
    final float step = (float) dt;
    this.lastDt = step;
    ensureCapacity();
    simulate(step);
    spawn(step);
    updateBounds();
  }

  private void ensureCapacity() {
    final int capacity = Math.max(0, this.maxParticles);
    if (this.data.length != capacity * STRIDE) {
      this.data = Arrays.copyOf(this.data, capacity * STRIDE);
      this.count = Math.min(this.count, capacity);
    }
  }

  private void simulate(float dt) {
    final float[] d = this.data;
    // implicit drag: stable for any drag and step, and reaches zero speed at most
    final float keep = 1f / (1f + Math.max(0f, this.drag) * dt);
    final var a = this.acceleration;
    int i = 0;
    while (i < this.count) {
      final int o = i * STRIDE;
      d[o + AGE] += dt;
      if (d[o + AGE] >= d[o + LIFE]) {
        this.count--;
        System.arraycopy(d, this.count * STRIDE, d, o, STRIDE); // order does not matter: they are sorted to draw
        continue;
      }
      d[o + PX] = d[o + X];
      d[o + PY] = d[o + Y];
      d[o + PZ] = d[o + Z];
      d[o + PROT] = d[o + ROT];
      d[o + VX] = (d[o + VX] + a.x * dt) * keep;
      d[o + VY] = (d[o + VY] + a.y * dt) * keep;
      d[o + VZ] = (d[o + VZ] + a.z * dt) * keep;
      d[o + X] += d[o + VX] * dt;
      d[o + Y] += d[o + VY] * dt;
      d[o + Z] += d[o + VZ] * dt;
      d[o + ROT] += d[o + SPIN] * dt;
      i++;
    }
  }

  private void spawn(float dt) {
    // where particles are born and the cone they fly in, in simulation space
    if (this.localSpace) {
      this.origin.zero();
      this.axis.set(this.direction);
      this.hasLastOrigin = false;
    } else {
      final var t = entity().transform();
      t.worldMatrix(this.world);
      this.world.getTranslation(this.origin);
      this.world.transformDirection(this.axis.set(this.direction));
    }
    if (this.axis.lengthSquared() < 1e-12f) {
      this.axis.set(0f, 1f, 0f);
    }
    this.axis.normalize();
    perpendicular(this.axis, this.tangent);
    this.axis.cross(this.tangent, this.bitangent);
    if (!this.hasLastOrigin) {
      this.lastOrigin.set(this.origin);
      this.hasLastOrigin = !this.localSpace;
    }

    int fromRate = 0;
    if (this.emitting && this.rate > 0f) {
      this.spawnCarry += this.rate * dt;
      fromRate = (int) this.spawnCarry;
      this.spawnCarry -= fromRate;
    }
    final int burst = this.pendingBurst;
    this.pendingBurst = 0;
    final int room = this.data.length / STRIDE - this.count;
    // particles from the rate are spread over the step (born early ones are older and have moved already)
    for (int k = 0; k < fromRate && k < room; k++) {
      final float f = (k + 0.5f) / fromRate;
      spawnOne(f, (1f - f) * dt);
    }
    for (int k = 0; k < burst && fromRate + k < room; k++) {
      spawnOne(1f, 0f);
    }
    this.lastOrigin.set(this.origin);
  }

  /** @param f where in the step it is born (0 start, 1 end), along the emitter's path; {@code age} so far */
  private void spawnOne(float f, float age) {
    final var r = this.random;
    final float[] d = this.data;
    final int o = this.count * STRIDE;
    this.count++;
    // position: on the path between the last and the current origin, anywhere inside the shape sphere
    this.lastOrigin.lerp(this.origin, f, this.point);
    if (this.shapeRadius > 0f) {
      final float radius = this.shapeRadius * (float) Math.cbrt(r.nextDouble());
      randomUnit(r, this.offset);
      this.point.fma(radius, this.offset);
    }
    // direction: uniform over the spherical cap of the cone
    final float spread = (float) Math.toRadians(Math.max(0f, Math.min(180f, this.spreadDegrees)));
    final float cos = 1f - (float) r.nextDouble() * (1f - (float) Math.cos(spread));
    final float sin = (float) Math.sqrt(Math.max(0f, 1f - cos * cos));
    final double phi = r.nextDouble() * Math.PI * 2.0;
    final float tx = sin * (float) Math.cos(phi);
    final float ty = sin * (float) Math.sin(phi);
    final float speed = between(r, this.speedMin, this.speedMax);
    final float vx = (this.axis.x * cos + this.tangent.x * tx + this.bitangent.x * ty) * speed;
    final float vy = (this.axis.y * cos + this.tangent.y * tx + this.bitangent.y * ty) * speed;
    final float vz = (this.axis.z * cos + this.tangent.z * tx + this.bitangent.z * ty) * speed;

    d[o + PX] = this.point.x;
    d[o + PY] = this.point.y;
    d[o + PZ] = this.point.z;
    d[o + X] = this.point.x + vx * age;
    d[o + Y] = this.point.y + vy * age;
    d[o + Z] = this.point.z + vz * age;
    d[o + VX] = vx;
    d[o + VY] = vy;
    d[o + VZ] = vz;
    d[o + AGE] = age;
    d[o + LIFE] = Math.max(1e-3f, between(r, this.lifetimeMin, this.lifetimeMax));
    final float variation = Math.max(0f, Math.min(1f, this.sizeVariation));
    d[o + SIZE] = 1f + variation * (float) (r.nextDouble() * 2.0 - 1.0);
    d[o + ROT] = this.randomRotation ? (float) (r.nextDouble() * Math.PI * 2.0) : 0f;
    d[o + SPIN] = (float) Math.toRadians(between(r, this.spinMin, this.spinMax));
    d[o + ROT] += d[o + SPIN] * age;
    d[o + PROT] = d[o + ROT];
    d[o + FRAME] = r.nextInt(Math.max(1, this.atlasColumns * this.atlasRows));
  }

  private static float between(SplittableRandom r, float a, float b) {
    return a + (b - a) * (float) r.nextDouble();
  }

  private static void randomUnit(SplittableRandom r, Vector3f dest) {
    final float z = (float) (r.nextDouble() * 2.0 - 1.0);
    final double phi = r.nextDouble() * Math.PI * 2.0;
    final float s = (float) Math.sqrt(Math.max(0f, 1f - z * z));
    dest.set(s * (float) Math.cos(phi), s * (float) Math.sin(phi), z);
  }

  private static void perpendicular(Vector3f unit, Vector3f dest) {
    if (Math.abs(unit.x) < 0.9f) {
      dest.set(1f, 0f, 0f);
    } else {
      dest.set(0f, 1f, 0f);
    }
    unit.cross(dest, dest).normalize();
  }

  /** Box around the particles now and at the last step (in simulation space), for culling. */
  private void updateBounds() {
    final float[] d = this.data;
    this.boundsMin.set(Float.MAX_VALUE);
    this.boundsMax.set(-Float.MAX_VALUE);
    for (int i = 0; i < this.count; i++) {
      final int o = i * STRIDE;
      this.boundsMin.min(this.point.set(d[o + X], d[o + Y], d[o + Z]));
      this.boundsMax.max(this.point);
      this.boundsMin.min(this.point.set(d[o + PX], d[o + PY], d[o + PZ]));
      this.boundsMax.max(this.point);
    }
  }

  /**
   * Bounding sphere of the living particles (with their size) in world space, for culling; {@code emitterWorld} is
   * the entity's (interpolated) world matrix, used in {@link #localSpace}. Returns the radius, or -1 without particles.
   */
  public float bounds(Matrix4f emitterWorld, Vector3f centerOut) {
    if (this.count == 0) {
      return -1f;
    }
    this.boundsMin.add(this.boundsMax, centerOut).mul(0.5f);
    final float half = 0.5f * Math.max(this.sizeStart, this.sizeEnd) * (1f + Math.max(0f, this.sizeVariation));
    // a quad's corner reaches sqrt(2) times its half size from the centre
    float radius = this.boundsMax.distance(this.boundsMin) * 0.5f + half * 1.4143f;
    if (this.localSpace) {
      emitterWorld.transformPosition(centerOut);
      radius *= emitterWorld.getScale(this.point).get(this.point.maxComponent());
    }
    return radius;
  }

  /**
   * Writes the living particles at the interpolated state {@code alpha} into {@code out} from float index
   * {@code offset} on, {@link #FLOATS_PER_INSTANCE} floats each (absolute puts, the buffer's position is not changed),
   * back to front along {@code cameraForward} for {@link Blend#ALPHA}. For render backends; {@code emitterWorld} is
   * the entity's interpolated world matrix (used in {@link #localSpace}). Returns the number written.
   */
  public int writeInstances(float alpha, Matrix4f emitterWorld, Vector3f cameraPos, Vector3f cameraForward,
      FloatBuffer out, int offset) {
    final int n = this.count;
    if (n == 0) {
      return 0;
    }
    final float[] d = this.data;
    final boolean sorted = this.blend == Blend.ALPHA;
    final boolean local = this.localSpace;
    if (sorted) {
      if (this.order.length < n) {
        this.order = new long[Math.max(n, this.order.length * 2)];
      }
      for (int i = 0; i < n; i++) {
        final int o = i * STRIDE;
        lerp(d, o, alpha, this.point);
        if (local) {
          emitterWorld.transformPosition(this.point);
        }
        final float depth = Math.max(0f, this.point.sub(cameraPos).dot(cameraForward));
        // farthest first: the bits of a non-negative float order like the float; invert them to sort descending
        this.order[i] = ((long) (Integer.MAX_VALUE - Float.floatToIntBits(depth)) << 32) | i;
      }
      Arrays.sort(this.order, 0, n);
    }
    final float b = Math.max(0f, this.brightness);
    srgbToLinear(this.colorStart, this.linearStart);
    srgbToLinear(this.colorEnd, this.linearEnd);
    final int frames = Math.max(1, this.atlasColumns) * Math.max(1, this.atlasRows);
    final float lag = (1f - alpha) * this.lastDt;
    final float fadeIn = Math.max(0f, Math.min(1f, this.fadeIn));
    for (int k = 0; k < n; k++) {
      final int i = sorted ? (int) this.order[k] : k;
      final int o = i * STRIDE;
      final float t = Math.max(0f, Math.min(1f, Math.max(0f, d[o + AGE] - lag) / d[o + LIFE]));
      lerp(d, o, alpha, this.point);
      if (local) {
        emitterWorld.transformPosition(this.point);
      }
      final float size = (this.sizeStart + (this.sizeEnd - this.sizeStart) * t) * d[o + SIZE];
      float a = this.linearStart.w + (this.linearEnd.w - this.linearStart.w) * t;
      if (t < fadeIn) {
        a *= t / fadeIn;
      }
      a = Math.max(0f, Math.min(1f, a));
      final float scale = a * b;
      final int w = offset + k * FLOATS_PER_INSTANCE;
      out.put(w, this.point.x);
      out.put(w + 1, this.point.y);
      out.put(w + 2, this.point.z);
      out.put(w + 3, Math.max(0f, size));
      out.put(w + 4, (this.linearStart.x + (this.linearEnd.x - this.linearStart.x) * t) * scale);
      out.put(w + 5, (this.linearStart.y + (this.linearEnd.y - this.linearStart.y) * t) * scale);
      out.put(w + 6, (this.linearStart.z + (this.linearEnd.z - this.linearStart.z) * t) * scale);
      out.put(w + 7, sorted ? a : 0f);
      out.put(w + 8, d[o + PROT] + (d[o + ROT] - d[o + PROT]) * alpha);
      out.put(w + 9, this.animateFrames ? Math.min(frames - 1, (int) (t * frames)) : d[o + FRAME] % frames);
    }
    return n;
  }

  private static void lerp(float[] d, int o, float alpha, Vector3f dest) {
    dest.set(d[o + PX] + (d[o + X] - d[o + PX]) * alpha, d[o + PY] + (d[o + Y] - d[o + PY]) * alpha,
        d[o + PZ] + (d[o + Z] - d[o + PZ]) * alpha);
  }

  /** sRGB to linear per colour channel (the renderer's curve), alpha unchanged. */
  private static void srgbToLinear(Vector4f c, Vector4f dest) {
    dest.set(srgbToLinear(c.x), srgbToLinear(c.y), srgbToLinear(c.z), c.w);
  }

  private static float srgbToLinear(float c) {
    return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
  }
}
