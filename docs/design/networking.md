# Networking: design notes

Status: proposal, nothing implemented yet. This note looks at how a network stack would fit the engine as it is
(0.9.0), what it would cost, and in which order to build it.

## What the engine already gives us

| Existing piece | Why it matters for networking |
|---|---|
| `FixedTimestep` + `Engine.step(dt)` | The simulation already runs in fixed ticks. A tick number is the natural clock for networking: inputs, snapshots and acks are all "for tick N". |
| `Transform.snapshot()` / `interpolatedWorldMatrix(alpha)` | Rendering already blends between two states. Remote entities need the same thing, only between two *received* states and a little in the past. |
| `InputState` with public feed methods and named actions | Inputs can be fed from anything ("a platform layer, a test, or a replay"). Actions (`jump`, `move_forward`, …) are exactly what a client should send, not raw keys. |
| `MessageService.post()` (thread-safe) + `dispatchPending()` at the start of each step | A network thread can hand received data to the game thread without new locking: post it, it is delivered at the next step. |
| `ISystem` + `addSystem` ordering | Networking is one more system, run first (apply received state) and one hook at the end of the step (send). |
| `ComponentRegistry` / `ComponentSerializer` | Every engine component already knows how to describe itself. Good enough for *spawning* an entity on a client; far too heavy (JSON maps, reflection) for per-tick state. |
| `Prefab` | Spawning by prefab name + a few overrides is much cheaper than sending a full entity. |

## What stands in the way

1. **No headless engine.** `Engine.run()` creates a `GLWindow` and an `Animator`; `step()` is called from the GL
   `display()` callback. A dedicated server (and server tests) needs the same step without a window, GL, audio or
   gamepads. This is the first refactoring and is useful on its own (headless simulation tests, tools).
2. **Entity ids are process-local.** `Entity.id` comes from a static `AtomicLong`, so ids differ between machines.
   Networked entities need their own id, assigned by the server.
3. **No tick counter exposed.** `FixedTimestep` has `elapsed` but no integer tick; floating seconds are a bad key for
   matching inputs to snapshots.
4. **The physics is not built for rollback.** `PhysicsSystem` keeps contact caches, sleeping state, joints and
   character state across steps. Saving and restoring all of it every tick (as rollback netcode needs) would be a big,
   fragile change. Java's floating point is strict since 17, so the *arithmetic* is reproducible, but the uses of
   `Math.*` (which may use intrinsics), iteration order and per-machine state make bit-exact lockstep a project of its
   own.
5. **Game code mutates the scene directly.** Components change fields freely in `update()`. Replication needs to know
   *what* is replicated and *who* is allowed to change it.

## Recommended model

**Server-authoritative, snapshot-based, with client-side prediction only for the local player.**

- The server (dedicated or a "listen server" inside a client) runs the full simulation, physics included.
- Clients send **input commands** per tick: the pressed actions as bits, look yaw/pitch, the tick number. Redundantly:
  each packet carries the last few commands so a lost packet costs nothing.
- The server sends **snapshots** at a configurable rate (e.g. every 2nd or 3rd tick): the replicated state of the
  entities relevant to that client, delta-compressed against the last snapshot the client acknowledged.
- Clients render **remote entities interpolated** ~100 ms in the past between two received snapshots (reusing the
  idea of `Transform`'s prev/current pair, but with a small buffer of received states).
- The **local player is predicted**: the client runs its own `CharacterController` with its own inputs immediately,
  keeps the inputs it has not seen acknowledged, and on each snapshot resets to the server's state and replays them.
  Only the character controller has to be rewindable, not the whole `PhysicsSystem` — this keeps the physics untouched.
- Physics bodies the player pushes or throws are server-simulated and interpolated on the client (a slight delay on
  thrown crates is the accepted trade-off). Optional later: local "visual only" prediction of projectiles.

Why not the alternatives:

- **Deterministic lockstep** (send only inputs, everyone simulates): tiny bandwidth, ideal for RTS-like games, but
  needs bit-exact physics across machines, and every client waits for the slowest. See problem 4. Worth keeping
  possible for games that bring their own deterministic simulation (the transport layer below serves both).
- **Rollback (GGPO-style)**: excellent for 2-player action games, but needs cheap full-state save/restore of the
  physics. Not a fit for this physics system today.
- **Client-authoritative**: simple, but trivial to cheat and inconsistent under contention. Fine for co-op prototypes;
  can be had as a per-component "owner writes" flag in the replication layer.

## Layers

```
 game code            NetworkIdentity, @Replicated fields, RPCs, ownership
 ───────────────────────────────────────────────────────────────────────────
 engine.net.replication   snapshot building, delta compression, interest management,
                          interpolation buffers, prediction/reconciliation for CharacterController
 ───────────────────────────────────────────────────────────────────────────
 engine.net.session       NetServer / NetClient: connect, handshake, protocol version, timeouts,
                          clock sync (server tick estimate), per-connection channels
 ───────────────────────────────────────────────────────────────────────────
 engine.net.transport     Connection with channels over UDP: sequence numbers, ack bitfields,
                          unreliable / unreliable-sequenced / reliable-ordered, fragmentation, MTU ~1200 B,
                          RTT and loss estimates, bandwidth budget
 ───────────────────────────────────────────────────────────────────────────
 engine.net.io            Transport interface: UdpTransport (java.nio DatagramChannel, own thread)
                          and LoopbackTransport (in memory, with simulated latency/jitter/loss)
```

Each layer is usable without the ones above: a turn-based game might only use the session layer with reliable
messages; a lockstep game uses session + its own input exchange.

### Transport and I/O

- **UDP via `java.nio.channels.DatagramChannel`**, no new dependency (the engine is deliberately light on
  dependencies). TCP is the wrong default: one lost packet stalls everything behind it, which shows as hitches.
- Packet header: protocol id, connection salt, sequence (16 bit, wrapping), ack + 32-bit ack bitfield, then channel
  blocks. The ack bitfield gives reliability and RTT for free on top of unreliable packets.
- Channels: `UNRELIABLE` (snapshots, inputs — both are redundant anyway), `RELIABLE_ORDERED` (spawn/despawn, RPCs,
  chat, level changes), maybe `UNRELIABLE_SEQUENCED` (drop older). Reliable messages are re-sent until an ack covers
  the packet they were in.
- Fragmentation for the rare large reliable message (initial level state), with a cap.
- A `BitWriter`/`BitReader` with quantisation helpers: positions to fixed point within level bounds, quaternions as
  "smallest three" (~29 bits), velocities clamped, booleans as single bits. This is where most bandwidth is won.
- One I/O thread per socket does the blocking receive and the sends; the game thread never touches the socket. It
  receives through a lock-free queue drained at the start of the step and enqueues outgoing packets at its end.
- Security, first version: a connect handshake with a server-issued challenge (stops spoofed-address amplification),
  size limits on everything read, and a protocol version check. Encryption (the JDK has DTLS through `SSLEngine`)
  and authentication tokens are a later, optional step; they belong behind the same `Transport` interface.

### Session

- `NetServer` (listen on a port, accept up to N clients, kick on timeout) and `NetClient` (connect, retry, report
  state). Both are plain objects owned by the game, ticked by a `NetworkSystem`.
- Clock: the client keeps an estimate of the server tick from snapshot ticks and RTT, and runs its prediction a little
  *ahead* of it (RTT/2 + a jitter margin), so its input for tick N arrives before the server simulates N. The
  estimate is corrected by gently speeding up or slowing down the client's `FixedTimestep`, not by jumping.
- Events for the game: `onConnected`, `onDisconnected(reason)`, `onMessage` — delivered on the game thread through the
  existing message service (a new `MessageQueue.NETWORK`).

### Replication

- `NetworkIdentity` component: a server-assigned `int netId`, the prefab (or spawn type) it was created from, and the
  owning connection (if any).
- Which state is replicated is declared per component type, like `ComponentSerializer` but binary and per field:

  ```java
  registry.register(Replication.of(Rigidbody.class)
      .transform(Quantize.position(0.001f), Quantize.rotation())
      .field("velocity", Quantize.vector(0.01f, 50f)));
  ```

  Transforms are by far the most common case and get a dedicated fast path.
- Spawning on a client: `netId` + prefab name + initial replicated state, on the reliable channel. Falls back to the
  existing JSON `ComponentSerializer` for entities that are not prefab-based (sent once, so its cost is acceptable).
- Snapshots carry only entities and fields that changed relative to the client's last acked snapshot (the server
  keeps a short ring buffer of past snapshots per client).
- Interest management: a per-client relevance test (distance, using the existing `SpatialGrid`) plus priorities, so
  big levels fit a fixed bandwidth budget per client. First version: everything is relevant.
- RPCs: `server.call(connection, "openDoor", args)` / `client.call("useItem", args)` on the reliable channel, handled
  by registered handlers on the game thread. Server-side handlers must validate — the client is never trusted.
- Ownership: the server owns everything by default. A component may be marked "owner-authoritative" for co-op
  shortcuts (a cosmetic, or a player's camera), which the server then accepts within limits.

### Prediction for the local player

- `CharacterController` gets a small `State` value (position, velocity, grounded flag, …) with `save()` / `restore()`,
  and its step must depend only on that state, the input and the static world. This is the one piece of the physics
  that needs to be rewindable.
- Client: per tick, store `(tick, input, predicted state)`. On a snapshot for tick S: compare the server's state with
  the stored prediction for S; if they differ beyond a threshold, restore the server's state, replay inputs S+1..now,
  and smooth the visual correction over a few frames (offset decay) so the camera does not snap.
- Server: apply each client's input for the tick it was meant for; if it arrives late, reuse the last one.
- Lag compensation for hitscan (later): the server keeps a short history of collider positions and raycasts against
  the state the shooter saw.

## Changes to the existing engine

In the order they would be needed:

1. **Headless engine**: extract the body of `step()` and the system setup from `Engine.Loop` into a part that runs
   without GL (`Engine.runHeadless()` or a separate `Simulation` class driven by a plain thread with `FixedTimestep`).
   Audio, renderer, gamepads and `ResourceManager`'s GPU uploads become optional. Meshes still have to load on a
   server (mesh colliders), so resource loading needs a CPU-only path.
2. **Tick counter**: `long tick` in `FixedTimestep`/`Engine`, incremented per step; plus a way to stretch the step
   slightly for clock sync.
3. **Step hooks**: a "before systems" and an "after systems" point (or ordering for systems) so `NetworkSystem` can
   apply received state first and send at the end of the same tick.
4. **`MessageQueue.NETWORK`**.
5. **Rewindable `CharacterController`** state, as above.
6. **Input from data**: an `InputCommand` (action bits + look) that can be captured from `InputState` and applied to
   it, so the server feeds each player's controller from received commands instead of the local keyboard.
   `FirstPersonController` reads `InputState` today; it would read an input source per player instead.
7. **Scene changes from the network** must go through `Scene.add/remove` (already deferred to `flush()`), which they
   would.

None of this changes the public API in breaking ways; all of it is additive.

## Testing

The engine's tests run without a GPU, and the network stack should too:

- `LoopbackTransport` connects a server and clients in one process with configurable latency, jitter, loss,
  duplication and reordering, driven by a fake clock — fully deterministic tests of reliability, acks, fragmentation,
  clock sync and reconciliation, with no sockets and no sleeps.
- Property tests for the bit packing and quantisation round-trips and for sequence-number wrap-around.
- A headless server + headless client test over loopback: the client moves its character with scripted inputs at 150
  ms RTT and 5 % loss; assert positions converge and corrections stay small.
- One real-socket smoke test on `127.0.0.1`.
- Demo: a `-Ddemo.host=7777` / `-Ddemo.connect=host:7777` mode so CI can run two demo processes under `xvfb-run`.
- Debug overlay (`F3`): RTT, loss, bytes in/out per second, snapshot rate, prediction corrections per second.

## Phases

| Phase | Content | Usable for |
|---|---|---|
| 1 | Headless engine, tick counter, step hooks | Dedicated simulation, server tests |
| 2 | `engine.net.io` + `transport` (UDP, loopback, channels, acks, fragmentation) + session (connect, timeout, version) | Turn-based games, chat, lobbies, lockstep games with their own simulation |
| 3 | Replication: `NetworkIdentity`, spawn/despawn, transform snapshots, interpolation of remote entities, RPCs | Server-authoritative games where a little input delay is fine |
| 4 | Input commands, client prediction and reconciliation of `CharacterController`, clock sync | First/third-person action games |
| 5 | Delta compression, quantisation tuning, interest management, bandwidth budgets | Larger levels, more players |
| 6 | Lag compensation, encryption/auth tokens, listen-server host migration (maybe) | Competitive play, public servers |

Phases 1 and 2 are a self-contained first release; each later phase is usable on its own.

## Questions to decide before starting

- **What kind of games is this for?** Co-op for 2–8 players over the internet, competitive shooters, or RTS-like? This
  decides whether lockstep should get first-class support and how much effort goes into lag compensation.
- **Dedicated servers, listen servers, or both?** Listen servers are much easier to ship to players; dedicated
  servers need the headless engine sooner.
- **NAT traversal / relays**: out of scope for the engine (players forward a port, or the game uses a platform
  service), or wanted? A relay can be added behind the `Transport` interface later.
- **Dependency policy**: the plan above needs no new library. Using an existing one (e.g. a Netty-based or a
  KryoNet-style library) would save the transport work but bring a large dependency and its own threading model; the
  transport layer is small enough (a few thousand lines) that writing it fits the rest of the engine better.
