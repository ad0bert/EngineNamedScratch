/**
 * Shared entities for co-op games. The host's {@link engine.net.replication.ServerReplication} spawns entities by type
 * and sends what happens to them; each client's {@link engine.net.replication.ClientReplication} builds copies with
 * the same {@link engine.net.replication.Spawner}s and shows them moving smoothly a little in the past. Components
 * implementing {@link engine.net.replication.NetState} add their own state; RPCs carry calls both ways.
 */
package engine.net.replication;
