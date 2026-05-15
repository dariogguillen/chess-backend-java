package io.github.dariogguillen.chess.domain;

import java.util.List;
import java.util.Objects;

/**
 * A chess game between two distinct players, anchored to the room it was started in.
 *
 * <p>The compact constructor enforces structural invariants:
 *
 * <ul>
 *   <li>{@code id} and {@code roomId} must not be null or blank.
 *   <li>{@code white} and {@code black} must not be null and must have different ids.
 *   <li>{@code fen} must not be null. It is treated as an opaque string here; chesslib validates
 *       its semantic correctness in the {@code ChessRules} service.
 *   <li>{@code status} must not be null.
 *   <li>{@code moves} must not be null and is defensively copied; the accessor returns an
 *       unmodifiable view.
 * </ul>
 *
 * @param id the game identifier; not null, not blank.
 * @param roomId the id of the room this game belongs to; not null, not blank.
 * @param white the player playing the white pieces; distinct from {@code black}.
 * @param black the player playing the black pieces; distinct from {@code white}.
 * @param fen the position in Forsyth-Edwards Notation; not null; opaque at the domain layer.
 * @param status the current status of the game; not null.
 * @param moves the history of moves played so far; defensively copied.
 */
public record Game(
    String id,
    String roomId,
    Player white,
    Player black,
    String fen,
    GameStatus status,
    List<Move> moves) {

  public Game {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(roomId, "roomId");
    Objects.requireNonNull(white, "white");
    Objects.requireNonNull(black, "black");
    Objects.requireNonNull(fen, "fen");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(moves, "moves");
    if (id.isBlank()) {
      throw new IllegalArgumentException("Game id must not be blank");
    }
    if (roomId.isBlank()) {
      throw new IllegalArgumentException("Game roomId must not be blank");
    }
    if (white.id().equals(black.id())) {
      throw new IllegalArgumentException(
          "Game white and black must be distinct players, got id: " + white.id());
    }
    moves = List.copyOf(moves);
  }
}
