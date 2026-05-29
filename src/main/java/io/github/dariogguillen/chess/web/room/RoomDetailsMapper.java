package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Boundary mapper that turns a domain {@link Room} (plus an optional game id) into the wire shape
 * {@link RoomDetailsResponse}. Lives outside the controller for two reasons: it isolates the
 * role-derivation rule in one place (unit-testable in isolation) and keeps {@code RoomController}
 * thin.
 *
 * <p><strong>Role derivation.</strong> The domain {@code Player} record has no {@code role} field.
 * Since feature 21 (`color-selection`) role is computed from {@link Room#creatorSide()}, not from
 * list position alone: the player at index 0 (always the creator) holds {@code creatorSide}, and
 * the player at index 1 (when present, always the joiner) holds the opposite. This mirrors the
 * invariant maintained by {@code RoomService} — {@code createRoom} produces a 1-element list with
 * the creator at position 0 and stores their resolved side; {@code joinRoom} appends the joiner at
 * position 1 and gives them the opposite. Position still identifies <em>who</em> a player is
 * (creator vs joiner); {@code creatorSide} decides <em>which colour</em> each one plays.
 */
@Component
public class RoomDetailsMapper {

  static final String ROLE_WHITE = "WHITE";
  static final String ROLE_BLACK = "BLACK";

  /**
   * Maps a domain {@link Room} (plus an optional game id surfaced by callers that hold it) to the
   * REST response shape.
   *
   * @param room the room to project; non-null.
   * @param gameId the associated game id when one exists, or {@link Optional#empty()} while the
   *     room is still {@code WAITING_FOR_PLAYER}.
   * @return the response carrying the room id, the players with derived roles, the game id (or
   *     {@code null}), and the native room status.
   */
  public RoomDetailsResponse toResponse(Room room, Optional<UUID> gameId) {
    List<Player> sourcePlayers = room.players();
    String creatorRole = (room.creatorSide() == Side.WHITE) ? ROLE_WHITE : ROLE_BLACK;
    String joinerRole = (room.creatorSide() == Side.WHITE) ? ROLE_BLACK : ROLE_WHITE;
    List<PlayerInRoom> mapped = new ArrayList<>(sourcePlayers.size());
    for (int i = 0; i < sourcePlayers.size(); i++) {
      Player player = sourcePlayers.get(i);
      String role = (i == 0) ? creatorRole : joinerRole;
      mapped.add(new PlayerInRoom(player.id(), player.displayName(), role));
    }
    return new RoomDetailsResponse(
        room.id(), List.copyOf(mapped), gameId.orElse(null), room.status());
  }
}
