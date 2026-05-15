package io.github.dariogguillen.chess.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * @param id the room identifier; not null, not blank.
 * @param players the players in the room; defensively copied; 0..2 entries; ids unique.
 * @param status the lifecycle status; not null.
 */
public record Room(String id, List<Player> players, RoomStatus status) {

  private static final int MAX_PLAYERS = 2;

  public Room {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(players, "players");
    Objects.requireNonNull(status, "status");
    if (id.isBlank()) {
      throw new IllegalArgumentException("Room id must not be blank");
    }
    if (players.size() > MAX_PLAYERS) {
      throw new IllegalArgumentException(
          "Room must hold at most " + MAX_PLAYERS + " players, got: " + players.size());
    }
    Set<String> ids = new HashSet<>();
    for (Player player : players) {
      Objects.requireNonNull(player, "players must not contain null entries");
      if (!ids.add(player.id())) {
        throw new IllegalArgumentException(
            "Room must not contain duplicate players, duplicate id: " + player.id());
      }
    }
    players = List.copyOf(players);
  }
}
