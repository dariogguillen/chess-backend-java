package io.github.dariogguillen.chess.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A room that holds up to two players and tracks its own lifecycle.
 *
 * <p>The compact constructor enforces structural invariants:
 *
 * <ul>
 *   <li>{@code id} must not be null or blank.
 *   <li>{@code players} must not be null and is defensively copied; the accessor returns an
 *       unmodifiable view.
 *   <li>{@code players} must hold at most two entries (a chess game has exactly two players).
 *   <li>{@code players} must not contain two entries with the same {@link Player#id()}.
 *   <li>{@code status} must not be null.
 * </ul>
 *
 * <p>The structural invariant "at most two players" is enforced here. Business-level rules like
 * "you cannot join a room that is already {@link RoomStatus#CLOSED}" belong to the service layer
 * and will throw business exceptions from the {@code exception/} hierarchy, not {@link
 * IllegalArgumentException}.
 *
 * <p>{@code creatorSide} (added by feature 21, `color-selection`) is the concrete {@link Side} the
 * creator plays — the resolution of their {@link SidePreference} at create time (a {@code RANDOM}
 * preference is coin-flipped to {@link Side#WHITE} or {@link Side#BLACK} before it ever reaches
 * this record). Side is no longer implied by list position: the joiner always takes the opposite of
 * {@code creatorSide}, and the web boundary derives every role from this field instead of from the
 * index of a player in {@link #players()}.
 *
 * <p>The record is evolved with the same backwards-compatible pattern as {@code Player.userId}
 * (feature 19): a {@code null}-tolerant compact constructor plus a convenience constructor. Two
 * consequences:
 *
 * <ul>
 *   <li><strong>Source compatibility.</strong> The 3-arg convenience constructor {@code Room(id,
 *       players, status)} defaults {@code creatorSide} to {@link Side#WHITE}, so every
 *       pre-feature-21 {@code new Room(...)} call site (which always meant "creator is white")
 *       compiles and behaves unchanged.
 *   <li><strong>Jackson record deserialization backwards-compatibility.</strong> Rooms serialised
 *       into Redis before this deploy have no {@code creatorSide} field. On deserialise Jackson
 *       invokes the canonical 4-arg constructor with {@code null} for the missing component; the
 *       compact constructor maps that {@code null} to {@link Side#WHITE}, the implicit side those
 *       in-flight rooms were created with.
 * </ul>
 *
 * <p>{@code timeControl} (added by feature 22, `time-control`) is the optional server-authoritative
 * clock the room declared at create time. {@code null} means an untimed room — the historical
 * behaviour — so the {@link Game} the room spawns carries no clock. A non-null value initialises
 * the game's clock at join time. It is threaded Request → Room → Game exactly as {@code
 * creatorSide} was in feature 21, and {@code null} is a legitimate value (untimed) rather than a
 * defaulted one.
 *
 * @param id the room identifier; not null, not blank.
 * @param players the players in the room; defensively copied; 0..2 entries; ids unique.
 * @param status the lifecycle status; not null.
 * @param creatorSide the concrete side the creator plays; a {@code null} (legacy / omitted) value
 *     defaults to {@link Side#WHITE}.
 * @param timeControl the optional declared clock; {@code null} means an untimed room.
 */
public record Room(
    String id, List<Player> players, RoomStatus status, Side creatorSide, TimeControl timeControl) {

  private static final int MAX_PLAYERS = 2;

  public Room {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(players, "players");
    Objects.requireNonNull(status, "status");
    if (creatorSide == null) {
      creatorSide = Side.WHITE;
    }
    if (id.isBlank()) {
      throw new IllegalArgumentException("Room id must not be blank");
    }
    if (players.size() > MAX_PLAYERS) {
      throw new IllegalArgumentException(
          "Room must hold at most " + MAX_PLAYERS + " players, got: " + players.size());
    }
    Set<UUID> ids = new HashSet<>();
    for (Player player : players) {
      Objects.requireNonNull(player, "players must not contain null entries");
      if (!ids.add(player.id())) {
        throw new IllegalArgumentException(
            "Room must not contain duplicate players, duplicate id: " + player.id());
      }
    }
    players = List.copyOf(players);
  }

  /**
   * Convenience constructor for the pre-feature-21 call shape, where the creator was always white
   * and rooms were untimed. Equivalent to {@code new Room(id, players, status, Side.WHITE, null)}.
   *
   * @param id the room identifier; not null, not blank.
   * @param players the players in the room; defensively copied; 0..2 entries; ids unique.
   * @param status the lifecycle status; not null.
   */
  public Room(String id, List<Player> players, RoomStatus status) {
    this(id, players, status, Side.WHITE, null);
  }

  /**
   * Convenience constructor for the pre-feature-22 call shape (feature 21's 4-arg form), where the
   * room carried a creator side but no declared clock. Equivalent to {@code new Room(id, players,
   * status, creatorSide, null)}. Keeps every feature-21-era {@code new Room(...)} call site and the
   * Jackson deserialisation of rooms serialised before feature 22 (which lack the {@code
   * timeControl} component) compiling and behaving as untimed.
   *
   * @param id the room identifier; not null, not blank.
   * @param players the players in the room; defensively copied; 0..2 entries; ids unique.
   * @param status the lifecycle status; not null.
   * @param creatorSide the concrete side the creator plays; {@code null} defaults to {@link
   *     Side#WHITE}.
   */
  public Room(String id, List<Player> players, RoomStatus status, Side creatorSide) {
    this(id, players, status, creatorSide, null);
  }
}
