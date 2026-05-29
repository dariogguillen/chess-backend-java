package io.github.dariogguillen.chess.domain;

import java.time.Instant;
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
 * <p><strong>Clock fields (feature 22, {@code time-control}).</strong> The four trailing fields —
 * {@code whiteTimeRemainingMs}, {@code blackTimeRemainingMs}, {@code lastMoveAt}, {@code
 * incrementMs} — model an optional server-authoritative clock. The first three are the live clock
 * <em>state</em>; {@code incrementMs} is the per-move Fischer increment <em>configuration</em> the
 * room declared (lifted onto the game so {@code GameService.applyMove} can apply it without a
 * cross-aggregate lookup of the {@code Room}). Unlike {@code Room.creatorSide} (which defaults a
 * {@code null} to a concrete value), here {@code null} is a <em>legitimate domain state</em>
 * meaning "untimed", so the compact constructor does NOT default — it permits {@code null} and
 * enforces an <strong>all-or-nothing invariant</strong>: either all four are {@code null} (untimed
 * game) or all four are non-null (timed game). A half-initialised clock is rejected with {@link
 * IllegalArgumentException}. An 8-arg convenience constructor delegates with the four clock fields
 * {@code null}, so every pre-feature-22 {@code new Game(...)} call site and Jackson deserialisation
 * of pre-deploy Redis games stay green (the same trick as {@code Player.userId} and {@code
 * Room.creatorSide}).
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
 * @param whiteTimeRemainingMs white's remaining clock in milliseconds, or {@code null} when the
 *     game is untimed; all-or-nothing with the other clock fields.
 * @param blackTimeRemainingMs black's remaining clock in milliseconds, or {@code null} when the
 *     game is untimed; all-or-nothing with the other clock fields.
 * @param lastMoveAt the instant the current side-to-move's clock started counting (game creation
 *     for white's first move, then each subsequent move), or {@code null} when the game is untimed;
 *     all-or-nothing with the other clock fields.
 * @param incrementMs the per-move Fischer increment in milliseconds, or {@code null} when the game
 *     is untimed; all-or-nothing with the other clock fields. {@code 0} for plain sudden-death.
 */
public record Game(
    UUID id,
    String roomId,
    Player white,
    Player black,
    String startingFen,
    String fen,
    GameStatus status,
    List<Move> moves,
    Long whiteTimeRemainingMs,
    Long blackTimeRemainingMs,
    Instant lastMoveAt,
    Long incrementMs) {

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
    // All-or-nothing clock invariant: a timed game carries all four clock fields; an untimed game
    // carries none. A partial clock (e.g. white's time set but black's null) is a programming
    // error.
    boolean anyClockNull =
        whiteTimeRemainingMs == null
            || blackTimeRemainingMs == null
            || lastMoveAt == null
            || incrementMs == null;
    boolean allClockNull =
        whiteTimeRemainingMs == null
            && blackTimeRemainingMs == null
            && lastMoveAt == null
            && incrementMs == null;
    if (anyClockNull && !allClockNull) {
      throw new IllegalArgumentException(
          "Game clock fields are all-or-nothing: either all of whiteTimeRemainingMs, "
              + "blackTimeRemainingMs, lastMoveAt, incrementMs are non-null (timed) or all are null "
              + "(untimed)");
    }
    moves = List.copyOf(moves);
  }

  /**
   * Convenience constructor for the pre-feature-22 call shape — an untimed game with no clock.
   * Equivalent to {@code new Game(..., null, null, null, null)}. Keeps every existing {@code new
   * Game(...)} call site compiling unchanged and lets Jackson deserialise pre-deploy Redis games
   * (which lack the four clock components) by passing {@code null} for each missing component.
   *
   * @param id the game identifier; not null.
   * @param roomId the id of the room this game belongs to; not null, not blank.
   * @param white the player playing the white pieces; distinct from {@code black}.
   * @param black the player playing the black pieces; distinct from {@code white}.
   * @param startingFen the FEN of the position the game started from; not null, not blank.
   * @param fen the current position in Forsyth-Edwards Notation; not null, not blank.
   * @param status the current status of the game; not null.
   * @param moves the history of moves played so far; defensively copied.
   */
  public Game(
      UUID id,
      String roomId,
      Player white,
      Player black,
      String startingFen,
      String fen,
      GameStatus status,
      List<Move> moves) {
    this(id, roomId, white, black, startingFen, fen, status, moves, null, null, null, null);
  }

  /**
   * Whether this game carries a server-authoritative clock. {@code true} when the clock fields are
   * non-null (timed game), {@code false} when they are null (untimed game). The all-or-nothing
   * invariant in the compact constructor guarantees these are the only two shapes — testing one
   * field suffices.
   *
   * @return {@code true} if the game is timed.
   */
  public boolean isTimed() {
    return whiteTimeRemainingMs != null;
  }

  /**
   * Returns a copy of this game with {@code status} replaced. All other fields — including the
   * three clock fields — are carried through by-reference; the move list is re-validated and
   * defensively copied by the compact constructor.
   *
   * <p>Used by {@code GameAbandonService.abandon} and {@code GameTimeoutService.timeout} to flip a
   * non-terminal game to a terminal status inside the {@code GameStore.compute} block without
   * verbose field-by-field reconstruction. Other call sites still build a new {@link Game}
   * explicitly because they mutate more than just the status (e.g. {@code GameService.applyMove}
   * also rewrites {@code fen}, appends to {@code moves}, and advances the clock).
   *
   * @param status the new status; non-null.
   * @return a new {@link Game} with the same id / players / FEN / moves / clock and the given
   *     status.
   */
  public Game withStatus(GameStatus status) {
    return new Game(
        id,
        roomId,
        white,
        black,
        startingFen,
        fen,
        status,
        moves,
        whiteTimeRemainingMs,
        blackTimeRemainingMs,
        lastMoveAt,
        incrementMs);
  }

  /**
   * Returns a copy of this game with the two remaining-time fields and the clock anchor replaced;
   * {@code incrementMs} (configuration, not state) is carried through unchanged. All non-clock
   * fields are carried through by-reference. Keeps {@code GameService.applyMove}'s clock-advance
   * step readable. The all-or-nothing invariant still applies: on a timed game pass non-null values
   * (the existing {@code incrementMs} stays non-null); on an untimed game this helper is never
   * called.
   *
   * @param whiteTimeRemainingMs white's new remaining clock in milliseconds.
   * @param blackTimeRemainingMs black's new remaining clock in milliseconds.
   * @param lastMoveAt the new clock anchor instant.
   * @return a new {@link Game} with the given clock state and all other fields unchanged.
   */
  public Game withClock(Long whiteTimeRemainingMs, Long blackTimeRemainingMs, Instant lastMoveAt) {
    return new Game(
        id,
        roomId,
        white,
        black,
        startingFen,
        fen,
        status,
        moves,
        whiteTimeRemainingMs,
        blackTimeRemainingMs,
        lastMoveAt,
        incrementMs);
  }
}
