package io.github.dariogguillen.chess.websocket;

import java.util.UUID;

/**
 * Discriminated union for STOMP events broadcast on {@code /topic/games/{gameId}}. Each variant
 * carries an explicit {@code type} field — serialized as a plain JSON property — so subscribers can
 * branch on the variant without polymorphic Jackson machinery ({@code @JsonTypeInfo}).
 *
 * <p>The sealed-interface + explicit-discriminator pattern was first established on {@code
 * /topic/rooms/{roomId}} by feature 9.5 ({@link RoomEvent}); feature 11.5 lifts the rule to a
 * <strong>codebase-wide design</strong>: polymorphic topics get the discriminator, single-event
 * topics (today: {@code ViewerCountEvent} on {@code /topic/rooms/{roomId}/viewers}) stay flat.
 *
 * <p>Adding a new variant requires extending the {@code permits} clause and providing the same
 * {@code type} + {@code gameId} fields.
 *
 * <p>Today the variants are:
 *
 * <ul>
 *   <li>{@link MoveEvent} — {@code type = "MOVE"} — published per accepted move.
 *   <li>{@link GameAbandonedEvent} — {@code type = "GAME_ABANDONED"} — terminal-by-timeout.
 *   <li>{@link PlayerDisconnectedEvent} — {@code type = "PLAYER_DISCONNECTED"} — emitted on STOMP
 *       disconnect for a non-terminal game; opens the mid-grace UX window.
 *   <li>{@link PlayerReconnectedEvent} — {@code type = "PLAYER_RECONNECTED"} — emitted when a
 *       reconnect cancels a pending grace timer; closes the mid-grace UX window.
 *   <li>{@link GameTimedOutEvent} — {@code type = "GAME_TIMED_OUT"} — terminal-by-clock-flag on a
 *       timed game (feature 22, {@code time-control}).
 * </ul>
 */
public sealed interface GameStateEvent
    permits MoveEvent,
        GameAbandonedEvent,
        PlayerDisconnectedEvent,
        PlayerReconnectedEvent,
        GameTimedOutEvent {

  /**
   * The discriminator string. Each variant returns its own stable, upper-snake-case constant (e.g.
   * {@code "MOVE"}, {@code "GAME_ABANDONED"}, {@code "PLAYER_DISCONNECTED"}, {@code
   * "PLAYER_RECONNECTED"}).
   */
  String type();

  /**
   * The game id every variant carries. Matches the {@code {gameId}} segment in the topic
   * destination.
   */
  UUID gameId();
}
