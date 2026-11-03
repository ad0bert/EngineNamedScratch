/**
 * Networking for co-op games: a {@link engine.net.NetServer} (the host, often inside a player's own game) and
 * {@link engine.net.NetClient}s exchange messages over UDP. Each {@link engine.net.Connection} numbers its packets and
 * acknowledges the other side's, which gives reliable, ordered messages (split when large) next to unreliable ones,
 * and measures round-trip time and loss. Both ends run on the update thread as {@link engine.core.StepHook}s: they
 * receive before the systems and send after the step.
 *
 * <p>
 * {@link engine.net.LoopbackNetwork} replaces the real network in tests, with a clock the test drives and chosen
 * latency, jitter, loss and duplication.
 */
package engine.net;
