package io.github.dariogguillen.chess.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A chess game between two distinct players, anchored to the room it was started in.
 *
 * <p>The compact constructor enforces structural invariants:
 *
 * <ul>
 *   <li>{@code id} must not be null. It is a {@link UUID} — minted with {@code UUID.randomUUID()}
 *       at game creation, persisted as a native Postgres {@code uuid}, exposed as a string on the
 *       JSON wire.
 *   <li>{@code roomId} must not be null or blank. It is the 6-char room short code (alphabet {@code
 *       ABCDEFGHJKMNPQRSTUVWXYZ23456789}), <em>not</em> a UUID — stays a {@link String}.
 *   <li>{@code white} and {@code black} must not be null and must have different ids.
 *   <li>{@code startingFen} must not be null or blank. It is the FEN the game began from, fixed at
 *       construction. The chess service uses it together with {@code moves} to reconstruct the
 *       chesslib position whenever a rule decision is required (preserves the internal hash history
 *       chesslib uses for threefold-repetition detection).
 *   <li>{@code fen} must not be null or blank. It is the <em>current</em> position after replaying
 *       {@code moves} from {@code startingFen}; it changes with every successful move.
 *   <li>{@code status} must not be null.
 *   <li>{@code moves} must not be null and is defensively copied; the accessor returns an
 *       unmodifiable view.
 * </ul>
 *
 * <p>At game creation time, {@code startingFen} and {@code fen} hold the same value. They diverge
 * after the first successful move.
 *
 * @param id the game identifier; not null.
 * @param roomId the id of the room this game belongs to; not null, not blank.
 * @param white the player playing the white pieces; distinct from {@code black}.
 * @param black the player playing the black pieces; distinct from {@code white}.
 * @param startingFen the FEN of the position the game started from; immutable, not null, not blank.
 * @param fen the current position in Forsyth-Edwards Notation; not null, not blank; opaque at the
 *     domain layer.
 * @param status the current status of the game; not null.
 * @param moves the history of moves played so far; defensively copied.
 */
public record Game(
    UUID id,
    String roomId,
    Player white,
    Player black,
    String startingFen,
    String fen,
    GameStatus status,
    List<Move> moves) {

  public Game {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(roomId, "roomId");
    Objects.requireNonNull(white, "white");
    Objects.requireNonNull(black, "black");
    Objects.requireNonNull(startingFen, "startingFen");
    Objects.requireNonNull(fen, "fen");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(moves, "moves");
    if (roomId.isBlank()) {
      throw new IllegalArgumentException("Game roomId must not be blank");
    }
    if (startingFen.isBlank()) {
      throw new IllegalArgumentException("Game startingFen must not be blank");
    }
    if (white.id().equals(black.id())) {
      throw new IllegalArgumentException(
          "Game white and black must be distinct players, got id: " + white.id());
    }
    moves = List.copyOf(moves);
  }
}
