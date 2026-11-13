package engine.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.LongSupplier;

import lombok.Getter;
import lombok.Setter;

/**
 * A network in memory, for tests and for trying a game's networking under bad conditions without a second machine.
 * Transports opened on it reach each other by address. Packets arrive after {@link Conditions#latency} plus up to
 * {@link Conditions#jitter} (so they can overtake each other), and can be lost or duplicated. Time comes from the
 * given clock (nanoseconds), so a test can drive it step by step; the randomness comes from a seed, so a run can be
 * repeated exactly. Single-threaded, like the transports.
 */
public final class LoopbackNetwork {
  /**
   * How the network treats every packet. Times in seconds, rates from 0 to 1.
   *
   * @param latency   one-way delay
   * @param jitter    extra delay, evenly spread from 0 to this
   * @param loss      fraction of packets dropped
   * @param duplicate fraction of packets delivered twice
   */
  public record Conditions(double latency, double jitter, double loss, double duplicate) {
    public static final Conditions PERFECT = new Conditions(0, 0, 0, 0);

    public Conditions {
      if (latency < 0 || jitter < 0 || loss < 0 || loss > 1 || duplicate < 0 || duplicate > 1) {
        throw new IllegalArgumentException("bad conditions: " + latency + ", " + jitter + ", " + loss + ", "
            + duplicate);
      }
    }
  }

  private record InFlight(long deliverAt, long order, SocketAddress from, byte[] data) {
  }

  /** Sees every packet on its way: returns it (changed or not) or null to drop it. For tests of what is on the wire. */
  @FunctionalInterface
  public interface Interceptor {
    byte[] intercept(SocketAddress from, SocketAddress to, byte[] packet);
  }

  private final LongSupplier clock;
  private final Random random;
  private final Map<SocketAddress, Endpoint> endpoints = new HashMap<>();
  @Getter @Setter private Conditions conditions = Conditions.PERFECT;
  /** Null, or something that looks at (and may change or drop) every packet. */
  @Getter @Setter private Interceptor interceptor;
  private int nextPort = 40000;
  private long order;
  /** Packets the network dropped (loss), for tests. */
  @Getter private long dropped;

  public LoopbackNetwork(LongSupplier clock, long seed) {
    this.clock = clock;
    this.random = new Random(seed);
  }

  /** A transport at 127.0.0.1:{@code port}. */
  public Transport open(int port) {
    final var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    if (this.endpoints.containsKey(address)) {
      throw new IllegalStateException(address + " is in use");
    }
    final var endpoint = new Endpoint(address);
    this.endpoints.put(address, endpoint);
    return endpoint;
  }

  /** A transport at a free port. */
  public Transport open() {
    while (this.endpoints.containsKey(new InetSocketAddress(InetAddress.getLoopbackAddress(), this.nextPort))) {
      this.nextPort++;
    }
    return open(this.nextPort++);
  }

  private void deliver(SocketAddress from, SocketAddress to, ByteBuffer packet) {
    byte[] data = new byte[packet.remaining()];
    packet.get(data);
    if (this.interceptor != null) {
      data = this.interceptor.intercept(from, to, data);
      if (data == null) {
        return;
      }
    }
    final var target = this.endpoints.get(to);
    if (target == null) {
      return;
    }
    final var c = this.conditions;
    if (this.random.nextDouble() < c.loss()) {
      this.dropped++;
      return;
    }
    final int copies = this.random.nextDouble() < c.duplicate() ? 2 : 1;
    for (int i = 0; i < copies; i++) {
      final double delay = c.latency() + this.random.nextDouble() * c.jitter();
      target.inbox.add(new InFlight(this.clock.getAsLong() + (long) (delay * 1e9), this.order++, from, data));
    }
  }

  private final class Endpoint implements Transport {
    private final SocketAddress address;
    private final PriorityQueue<InFlight> inbox = new PriorityQueue<>((a, b) -> a.deliverAt != b.deliverAt
        ? Long.compare(a.deliverAt, b.deliverAt)
        : Long.compare(a.order, b.order));

    Endpoint(SocketAddress address) {
      this.address = address;
    }

    @Override
    public void send(SocketAddress to, ByteBuffer packet) {
      if (LoopbackNetwork.this.endpoints.get(this.address) == this) {
        deliver(this.address, to, packet);
      }
    }

    @Override
    public SocketAddress receive(ByteBuffer into) {
      final var next = this.inbox.peek();
      if (next == null || next.deliverAt > LoopbackNetwork.this.clock.getAsLong()) {
        return null;
      }
      this.inbox.poll();
      into.put(next.data, 0, Math.min(next.data.length, into.remaining()));
      return next.from;
    }

    @Override
    public SocketAddress localAddress() {
      return this.address;
    }

    @Override
    public void close() {
      LoopbackNetwork.this.endpoints.remove(this.address, this);
      this.inbox.clear();
    }
  }
}
