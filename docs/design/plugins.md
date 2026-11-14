# Plugins and kits: keeping the core general

Status: done. This note records why the engine has plugins and a kits module, what belongs where, and how to
contribute functionality without growing the core.

## The problem

The engine is meant to make no assumptions about the kind of game. Over time it had picked up some that a game could
not opt out of:

- `Engine` always bound first-person keys (WASD, Space, Shift, the pad's Y), Esc to free the mouse and F3 to the
  debug text, and always played collision sounds. A game with other controls could only rebind on top.
- Networking (phase 7) put a spaceship model (`FlightMotor`), an arcade topology (`Wraparound`) and flight fields
  (`moveY`, `aim`) of a fixed command record into `engine.net.replication`, each wired into several core classes.
  Every new genre would have widened the command again.
- `ClientReplication` knew which engine components to switch off on copies; a game's own could not take part.
- `ActionCommandSource` read hard-coded action names.

## What belongs where

| Where | What | Test |
|---|---|---|
| Core (`engine`) | Mechanisms every game uses or can ignore at no cost: scene, ECS, rendering, physics, sound, input plumbing, resources, serialization, networking and replication, game states | Would a puzzle game, a racing game and a shooter all accept it as is? |
| Extension points (`engine`) | Interfaces where games differ: `EnginePlugin`, `ISystem`, `StepHook`, `Overlay`, `ComponentSerializer`, `NetState`, `InputCommand` + `CommandCodec`, `PredictedMotor`, `WorldSpace`, `CopyHook`, `RelevanceFilter`, `CommandSource` | Is there a sensible default that costs nothing when unused? |
| Default plugins (`engine`) | Conveniences most games want, installed unless the game says otherwise: `MouseReleasePlugin`, `FirstPersonInputPlugin`, `ImpactSoundPlugin`, `DebugOverlayPlugin` | Can a game leave it out without losing anything else? |
| Kits (`kits`) | Implementations for a kind of game: `engine.kits.flight` (`FlightCommand`, `FlightMotor`), `engine.kits.wraparound` (`Wraparound`) | Does it only make sense for some genres? It goes here. |
| Game | Rules, content, its own components and plugins | |

The kits module depends only on the engine's public API, so the build checks that the extension points are enough.
If a kit needs something the engine does not offer, the fix is a general extension point in the core, not a special
case.

## `EnginePlugin`

```java
public interface EnginePlugin {
  default String id() { return getClass().getName(); }
  default String version() { return ""; }
  default List<String> requires() { return List.of(); }   // ids installed before this one
  default boolean networked() { return false; }           // part of NetConfig.forGame(engine)'s protocol id
  void install(Engine engine);
  default void uninstall(Engine engine) {}
  default void registerComponents(ComponentRegistry registry) {}
}
```

- **The game owns the list** (`Game.plugins()`, default `EnginePlugins.defaults()`). There is no classpath discovery:
  install order decides the order of systems, and host and clients must run the same set. Helpers:
  `EnginePlugins.defaultsWithout(...)`, `defaultsAnd(...)`.
- **Lifecycle:** installed in order after the core systems (component updates, animation, physics, audio) and before
  `Game.init`; uninstalled in reverse after `Game.shutdown`. `EnginePlugins.check` rejects duplicate ids and
  requirements that are not installed earlier, before anything is installed.
- **Lookup:** `engine.plugin(Class)`, `engine.hasPlugin(id)`, `engine.plugins()`.
- **Serialization:** `ComponentRegistry.core()` holds the core components; `engine.components()` adds those of the
  installed plugins; `ComponentRegistry.defaults()` (used by `new SceneSerializer()`) is the core plus the default
  plugins', so saved scenes keep loading.
- **Networking:** `engine.pluginSignature()` lists the networked plugins with their versions;
  `NetConfig.forGame(engine)` mixes it into the protocol id, so a client with another rules plugin is refused at the
  handshake instead of desynchronising.

## Changes for existing games

- `Engine.setDebugOverlayVisible(b)` is now
  `engine.plugin(DebugOverlayPlugin.class).ifPresent(d -> d.setVisible(b))`.
- `AudioSystem` no longer receives collision messages itself; `ImpactSoundPlugin` does (or
  `ImpactSoundPlugin.receiver(audio)` for code that drives an `AudioSystem` by hand).
- Networking: `InputCommand` is an interface, the walking record is `StandardCommand` (`button(0..7)`, no `JUMP`
  constant; `CharacterMotor.jumpButton` is button 0). `setWraparound` is `setWorldSpace`. `FlightMotor`,
  `Wraparound` and the flight command are in the kits module (`FlightMotor.space` instead of `wraparound`, and
  `replication.setCommandCodec(FlightCommand.CODEC)` on both sides).

## Contributing

A contribution that only some games want goes into `kits/` (one package per kind of game, `engine.kits.<name>`),
with tests on the engine's public API; the engine's test jar provides `ReplicationBed` (a host and guests on a
loopback network) for networked pieces. A plugin there is welcome when the piece needs installing (systems, hooks,
bindings); plain components and motors need none.
