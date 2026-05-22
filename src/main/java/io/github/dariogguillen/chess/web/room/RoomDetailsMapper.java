package io.github.dariogguillen.chess.web.room;

import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
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
 * Role is computed here from the position of each player in {@link Room#players()}: index 0 is
 * always WHITE (the creator), index 1 (when present) is always BLACK (the joiner). This mirrors the
 * invariant maintained by {@code RoomService} — {@code createRoom} produces a 1-element list with
 * the creator at position 0; {@code joinRoom} appends the joiner at position 1. Any future feature
 * that rearranges {@code Room.players} would break the role-derivation contract; the deliberate
 * trade-off is keeping the domain minimal in exchange for a position-sensitive mapper.
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
    List<PlayerInRoom> mapped = new ArrayList<>(sourcePlayers.size());
    for (int i = 0; i < sourcePlayers.size(); i++) {
      Player player = sourcePlayers.get(i);
      String role = (i == 0) ? ROLE_WHITE : ROLE_BLACK;
      mapped.add(new PlayerInRoom(player.id(), player.displayName(), role));
    }
    return new RoomDetailsResponse(
        room.id(), List.copyOf(mapped), gameId.orElse(null), room.status());
  }
}
