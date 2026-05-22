package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.exception.RoomFullException;
import io.github.dariogguillen.chess.exception.RoomNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use-case orchestration for the room lifecycle: create a room (one player), then join it (second
 * player, also creates the {@link Game}). The service is the entry point that both REST and (later)
 * WebSocket controllers will share; it holds no state of its own and delegates persistence to
 * {@link RoomStore} / {@link GameStore}.
 *
 * <p>The cross-store invariant — "a game exists if its room is {@link RoomStatus#ACTIVE}" — is
 * preserved by performing the room mutation and the game write inside the same {@link
 * RoomStore#compute} block, which is atomic per room id. See {@link #joinRoom(String, String)} for
 * the detail.
 */
@Service
public class RoomService {

  private static final Logger log = LoggerFactory.getLogger(RoomService.class);

  /** Maximum number of attempts to find a non-colliding room code before giving up. */
  private static final int MAX_CODE_ATTEMPTS = 5;

  private final RoomStore roomStore;
  private final GameStore gameStore;
  private final RoomCodeGenerator codeGenerator;
  private final ChessRules chessRules;

  public RoomService(
      RoomStore roomStore,
      GameStore gameStore,
      RoomCodeGenerator codeGenerator,
      ChessRules chessRules) {
    this.roomStore = roomStore;
    this.gameStore = gameStore;
    this.codeGenerator = codeGenerator;
    this.chessRules = chessRules;
  }

  /**
   * Creates a new room with the caller as its single player (who becomes White). The room id is a
   * fresh six-character short code; on the (extraordinarily unlikely) event of a collision against
   * an existing room, the service retries with a freshly generated code up to {@value
   * #MAX_CODE_ATTEMPTS} times.
   *
   * @param displayName the human-readable name of the creator; passed through to the {@link Player}
   *     record.
   * @return the freshly created room with one player and status {@link
   *     RoomStatus#WAITING_FOR_PLAYER}, plus the synthesised {@link Player} so the caller can
   *     surface the assigned player id.
   * @throws IllegalStateException if {@value #MAX_CODE_ATTEMPTS} consecutive generated codes all
   *     collide with existing rooms — a system-level failure indicating either a near-full keyspace
   *     or a broken {@link RoomCodeGenerator}.
   */
  public CreatedRoom createRoom(String displayName) {
    Player creator = new Player(UUID.randomUUID(), displayName);
    for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
      String candidate = codeGenerator.generate();
      Room created =
          roomStore.compute(
              candidate,
              (id, existing) -> {
                if (existing != null) {
                  return existing;
                }
                return new Room(id, List.of(creator), RoomStatus.WAITING_FOR_PLAYER);
              });
      // If compute returned the existing room, our candidate collided and `created`
      // is not the room we built. Retry with a fresh code.
      if (created.players().size() == 1 && created.players().get(0).id().equals(creator.id())) {
        log.info("Room created: roomId={}, creatorId={}", created.id(), creator.id());
        return new CreatedRoom(created, creator);
      }
      log.warn(
          "Room code collision on attempt {}/{}: candidate={}",
          attempt,
          MAX_CODE_ATTEMPTS,
          candidate);
    }
    throw new IllegalStateException(
        "Could not allocate a unique room code after " + MAX_CODE_ATTEMPTS + " attempts.");
  }

  /**
   * Joins {@code roomId} as the second player and creates the {@link Game} for that room in the
   * same atomic step. The caller becomes Black; the creator (already on the room) becomes White.
   *
   * <p>The read-check-write block runs inside {@link RoomStore#compute}, which serializes
   * concurrent calls for the same {@code roomId}. Inside the block we validate the room, build the
   * updated {@link Room} (two players, status {@link RoomStatus#ACTIVE}), and persist the newly
   * created {@link Game} via {@link GameStore#save(Game)} — all before returning the new room value
   * to the store. A concurrent joiner that loses the race observes the room as full and receives a
   * {@link RoomFullException}, never a half-state.
   *
   * @param roomId the id of the room to join.
   * @param displayName the human-readable name of the joiner.
   * @return a {@link JoinedRoom} carrying the post-join room, the synthesised joiner player, and
   *     the freshly created game so the caller can surface its id.
   * @throws RoomNotFoundException if no room exists for {@code roomId}.
   * @throws RoomFullException if the room already holds two players.
   */
  public JoinedRoom joinRoom(String roomId, String displayName) {
    Player joiner = new Player(UUID.randomUUID(), displayName);
    // The created game is captured from inside the atomic block so the outer code can return it.
    Game[] createdGameHolder = new Game[1];

    Room updated =
        roomStore.compute(
            roomId,
            (id, existing) -> {
              if (existing == null) {
                throw new RoomNotFoundException(id);
              }
              if (existing.players().size() >= 2) {
                throw new RoomFullException(id);
              }
              Player creator = existing.players().get(0);
              List<Player> bothPlayers = new ArrayList<>(2);
              bothPlayers.add(creator);
              bothPlayers.add(joiner);
              Room nextRoom = new Room(id, bothPlayers, RoomStatus.ACTIVE);
              String initialFen = chessRules.standardInitialState().currentFen();
              Game game =
                  new Game(
                      UUID.randomUUID(),
                      id,
                      creator,
                      joiner,
                      initialFen,
                      initialFen,
                      GameStatus.ONGOING,
                      List.of());
              gameStore.save(game);
              createdGameHolder[0] = game;
              return nextRoom;
            });

    log.info(
        "Room joined: roomId={}, joinerId={}, gameId={}",
        updated.id(),
        joiner.id(),
        createdGameHolder[0].id());
    return new JoinedRoom(updated, joiner, createdGameHolder[0]);
  }

  /**
   * Carrier returned from {@link #createRoom(String)}. The room exposes the assigned id and the
   * single-element player list; {@code creator} is the player just synthesised, kept separately so
   * the controller does not have to pick it out of the list.
   */
  public record CreatedRoom(Room room, Player creator) {}

  /**
   * Carrier returned from {@link #joinRoom(String, String)}. The room is post-join (two players,
   * {@link RoomStatus#ACTIVE}); the {@code joiner} is the player just synthesised; the {@code game}
   * is the freshly created in-progress game whose id the controller surfaces in the response.
   */
  public record JoinedRoom(Room room, Player joiner, Game game) {}
}
