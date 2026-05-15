package io.github.dariogguillen.chess.domain;

/**
 * Lifecycle status of a {@link Room}.
 *
 * <ul>
 *   <li>{@link #WAITING_FOR_PLAYER} — room exists with zero or one player; waiting for the second.
 *   <li>{@link #ACTIVE} — both players have joined and a game is in progress.
 *   <li>{@link #CLOSED} — the room is no longer accepting activity (game ended, abandoned, etc.).
 * </ul>
 */
public enum RoomStatus {
  WAITING_FOR_PLAYER,
  ACTIVE,
  CLOSED
}
