package io.github.dariogguillen.chess.domain;

/**
 * The kind of opponent the room creator wants to face, expressed at room-create time (feature 23,
 * {@code bot-opponent}).
 *
 * <ul>
 *   <li>{@link #FRIEND} — the historical flow: the creator gets a room in {@code
 *       WAITING_FOR_PLAYER}, shares the {@code joinToken}, and a second human joins to start the
 *       game. This is the default when {@code opponentKind} is {@code null} / omitted, so
 *       pre-feature-23 clients keep working unchanged.
 *   <li>{@link #BOT} — the creator plays the bundled Stockfish engine: the backend builds a
 *       complete two-side {@link Game} immediately (creator on one side, the bot on the other), so
 *       the create response already carries a non-null {@code gameId} and no human ever joins.
 * </ul>
 *
 * <p>{@code RANDOM} (online matchmaking) is intentionally <em>not</em> a value here — it arrives
 * with feature 24 ({@code random-matchmaking}) together with the queue behaviour. Shipping an enum
 * value with no backing behaviour would be a contract lie.
 */
public enum OpponentKind {
  FRIEND,
  BOT
}
