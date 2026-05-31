package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.config.BotProperties;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.OpponentKind;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.SidePreference;
import io.github.dariogguillen.chess.domain.TimeControl;
import io.github.dariogguillen.chess.exception.InvalidJoinTokenException;
import io.github.dariogguillen.chess.exception.RoomFullException;
import io.github.dariogguillen.chess.exception.RoomNotFoundException;
import io.github.dariogguillen.chess.websocket.RoomJoinedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Use-case orchestration for the room lifecycle: create a room (one player), then join it (second
 * player, also creates the {@link Game}). The service is the entry point that both REST and (later)
 * WebSocket controllers will share; it holds no state of its own and delegates persistence to
 * {@link RoomStore} / {@link GameStore}.
 *
 * <p>The cross-store invariant — "a game exists if its room is {@link RoomStatus#ACTIVE}" — is
 * preserved by performing the room mutation and the game write inside the same {@link
 * RoomStore#compute} block, which is atomic per room id. See {@link #joinRoom(String, String, UUID,
 * String)} for the detail.
 *
 * <p>On a successful join the service also broadcasts a {@link RoomJoinedEvent} on {@code
 * /topic/rooms/{roomId}} so the creator (Player A) can learn the {@code gameId} the same instant
 * Player B does. The broadcast is best-effort — see {@link #joinRoom(String, String, UUID,
 * String)}.
 *
 * <p>Each successful broadcast emits a single INFO log line carrying the destination and the key
 * payload identifiers ({@code gameId}, {@code joinerId}); the failure path stays on WARN.
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
  private final SimpMessagingTemplate messagingTemplate;
  private final RandomSideChooser randomSideChooser;
  private final Clock clock;
  private final ClockTimerManager clockTimerManager;
  private final BotProperties botProperties;

  public RoomService(
      RoomStore roomStore,
      GameStore gameStore,
      RoomCodeGenerator codeGenerator,
      ChessRules chessRules,
      SimpMessagingTemplate messagingTemplate,
      RandomSideChooser randomSideChooser,
      Clock clock,
      ClockTimerManager clockTimerManager,
      BotProperties botProperties) {
    this.roomStore = roomStore;
    this.gameStore = gameStore;
    this.codeGenerator = codeGenerator;
    this.chessRules = chessRules;
    this.messagingTemplate = messagingTemplate;
    this.randomSideChooser = randomSideChooser;
    this.clock = clock;
    this.clockTimerManager = clockTimerManager;
    this.botProperties = botProperties;
  }

  /**
   * Creates a new room with the caller as its single player. The creator's side is the resolution
   * of {@code preferredSide}: {@link SidePreference#WHITE} / {@link SidePreference#BLACK} pass
   * through to the matching {@link Side}; {@link SidePreference#RANDOM} is resolved by a
   * server-side coin flip via {@link RandomSideChooser} (the client cannot bias it); a {@code null}
   * preference defaults to {@link Side#WHITE} for backwards compatibility with clients that do not
   * send the field. The resolved concrete {@link Side} is stored on the {@link Room} so the joiner
   * can take the opposite and every role decision derives from it rather than from list position.
   *
   * <p>The room id is a fresh six-character short code; on the (extraordinarily unlikely) event of
   * a collision against an existing room, the service retries with a freshly generated code up to
   * {@value #MAX_CODE_ATTEMPTS} times.
   *
   * @param displayName the human-readable name of the creator; passed through to the {@link Player}
   *     record.
   * @param preferredSide the creator's requested side; {@code null} defaults to {@link Side#WHITE}.
   * @param currentUserId the authenticated user's id when the request carried a valid Bearer JWT,
   *     {@code null} for anonymous guest creation. Threaded into the new {@link Player}'s {@code
   *     userId} field so the archive path can populate {@code games.white_user_id} when the game
   *     terminates (feature 19, `auth-my-games`).
   * @param timeControl the optional declared clock (feature 22, `time-control`); {@code null} means
   *     an untimed room whose game carries no clock. Stored on the {@link Room} and read at join
   *     time to initialise the game clock.
   * @param opponentKind the kind of opponent (feature 23, `bot-opponent`); {@code null} → {@link
   *     OpponentKind#FRIEND}. {@link OpponentKind#FRIEND} keeps the historical flow (one player,
   *     {@code WAITING_FOR_PLAYER}, a {@code joinToken}, a second human joins later). {@link
   *     OpponentKind#BOT} builds the complete two-side {@link Game} immediately — the human on
   *     {@code creatorSide}, the {@link Player#bot()} on the opposite side, status {@link
   *     RoomStatus#ACTIVE} — so {@link CreatedRoom#gameId()} is non-null on the response and no
   *     {@code joinToken} is minted (the room is already full).
   * @param botElo the requested bot strength (feature 23.5, `bot-difficulty`), relevant only for a
   *     {@link OpponentKind#BOT} room. {@code null} (omitted) falls back to {@code
   *     BotProperties.defaultElo()}. The resolved value is stored on the bot {@link Game} so {@code
   *     BotMoveService} applies it on every bot move. Ignored for {@link OpponentKind#FRIEND} (the
   *     game has no bot, so the field stays {@code null} on the {@link Game}). The controller's
   *     Bean Validation has already bounded it to the engine's supported range.
   * @return the freshly created room plus the synthesised {@link Player}. For FRIEND the room is
   *     {@link RoomStatus#WAITING_FOR_PLAYER} with a non-null {@code joinToken} and a {@code null}
   *     {@code gameId}; for BOT the room is {@link RoomStatus#ACTIVE} with a non-null {@code
   *     gameId} and a {@code null} {@code joinToken}.
   * @throws IllegalStateException if {@value #MAX_CODE_ATTEMPTS} consecutive generated codes all
   *     collide with existing rooms — a system-level failure indicating either a near-full keyspace
   *     or a broken {@link RoomCodeGenerator}.
   */
  public CreatedRoom createRoom(
      String displayName,
      SidePreference preferredSide,
      UUID currentUserId,
      TimeControl timeControl,
      OpponentKind opponentKind,
      Integer botElo) {
    Player creator = new Player(UUID.randomUUID(), displayName, currentUserId);
    Side creatorSide = resolveCreatorSide(preferredSide);
    boolean vsBot = opponentKind == OpponentKind.BOT;
    // Resolve the bot strength once for the BOT path: the requested Elo, or the configured default
    // when the client omitted it. Irrelevant for FRIEND (no bot game is built).
    int resolvedBotElo = botElo != null ? botElo : botProperties.defaultElo();
    // FRIEND rooms carry a secret join token (feature 22.7); BOT rooms are already full, so no
    // token is minted and a stray join attempt hits RoomFull.
    String joinToken = vsBot ? null : UUID.randomUUID().toString();
    // Captured from inside the atomic block so the outer code can return the bot game's id.
    Game[] createdGameHolder = new Game[1];
    for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
      String candidate = codeGenerator.generate();
      Room created =
          roomStore.compute(
              candidate,
              (id, existing) -> {
                if (existing != null) {
                  return existing;
                }
                if (vsBot) {
                  return buildBotRoom(
                      id, creator, creatorSide, timeControl, resolvedBotElo, createdGameHolder);
                }
                return new Room(
                    id,
                    List.of(creator),
                    RoomStatus.WAITING_FOR_PLAYER,
                    creatorSide,
                    timeControl,
                    joinToken);
              });
      // If compute returned the existing room, our candidate collided and `created`
      // is not the room we built. Retry with a fresh code.
      boolean ours = created.players().get(0).id().equals(creator.id());
      if (ours) {
        Game botGame = createdGameHolder[0];
        log.info(
            "Room created: roomId={}, creatorId={}, opponentKind={}, gameId={}",
            created.id(),
            creator.id(),
            vsBot ? OpponentKind.BOT : OpponentKind.FRIEND,
            botGame == null ? null : botGame.id());
        // For a timed vs-bot game, arm white's flag timer outside the atomic block (the game write
        // is durable), the same as joinRoom does for a friend game. The bot answers in well under a
        // second so it never flags, but the human side still needs the canonical clock running.
        if (botGame != null && botGame.isTimed()) {
          Instant whiteDeadline = botGame.lastMoveAt().plusMillis(botGame.whiteTimeRemainingMs());
          clockTimerManager.scheduleFlag(botGame.id(), whiteDeadline);
        }
        return new CreatedRoom(created, creator, botGame);
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
   * Builds a complete vs-bot room (feature 23, `bot-opponent`) and persists its game inside the
   * room {@code compute} block, mirroring the cross-store atomicity {@link #joinRoom(String,
   * String, UUID, String)} uses: the {@link Room} (two players — human + bot — and {@link
   * RoomStatus#ACTIVE}) and the {@link Game} are written together so the "a game exists iff its
   * room is ACTIVE" invariant holds for bot rooms too. The created game is surfaced to the caller
   * via the one-element holder.
   */
  private Room buildBotRoom(
      String roomId,
      Player creator,
      Side creatorSide,
      TimeControl timeControl,
      int botElo,
      Game[] createdGameHolder) {
    Player bot = Player.bot();
    Room room =
        new Room(roomId, List.of(creator, bot), RoomStatus.ACTIVE, creatorSide, timeControl, null);
    Player white = creatorSide == Side.WHITE ? creator : bot;
    Player black = creatorSide == Side.WHITE ? bot : creator;
    String initialFen = chessRules.standardInitialState().currentFen();
    Long whiteMs = timeControl == null ? null : timeControl.initialMs();
    Long blackMs = timeControl == null ? null : timeControl.initialMs();
    Instant lastMoveAt = timeControl == null ? null : Instant.now(clock);
    Long incrementMs = timeControl == null ? null : timeControl.incrementMs();
    // botElo is set on the bot game (and only the bot game) so BotMoveService applies the requested
    // strength on every bot move. The 13-arg canonical constructor carries it as the trailing
    // field.
    Game game =
        new Game(
            UUID.randomUUID(),
            roomId,
            white,
            black,
            initialFen,
            initialFen,
            GameStatus.ONGOING,
            List.of(),
            whiteMs,
            blackMs,
            lastMoveAt,
            incrementMs,
            botElo);
    gameStore.save(game);
    createdGameHolder[0] = game;
    return room;
  }

  /**
   * Resolves a request-level {@link SidePreference} into the concrete {@link Side} the creator will
   * play. {@code WHITE} / {@code BLACK} map directly; {@code RANDOM} delegates to the
   * server-authoritative {@link RandomSideChooser}; {@code null} (omitted field) defaults to {@link
   * Side#WHITE} so pre-feature-21 clients keep the historical "creator is white" behaviour.
   */
  private Side resolveCreatorSide(SidePreference preferredSide) {
    if (preferredSide == null) {
      return Side.WHITE;
    }
    return switch (preferredSide) {
      case WHITE -> Side.WHITE;
      case BLACK -> Side.BLACK;
      case RANDOM -> randomSideChooser.choose();
    };
  }

  /**
   * Looks up the current state of a room by id.
   *
   * @param roomId the room id.
   * @return the room, if present.
   */
  public Optional<Room> findById(String roomId) {
    return roomStore.findById(roomId);
  }

  /**
   * Resolves the active {@link Game}'s id for a room that is {@link RoomStatus#ACTIVE}.
   *
   * <p>The {@link Room} record does not remember the id of the game it spawned (no cross-aggregate
   * reference), so the lookup goes through {@link GameStore#findByRoomId(String)} — a scan-and-
   * filter on the {@code game:*} keyspace. Returns {@link Optional#empty()} when the room is still
   * {@link RoomStatus#WAITING_FOR_PLAYER}, when its game has already been archived to Postgres (and
   * removed from Redis), or when the room itself does not exist.
   *
   * @param roomId the room id.
   * @return the in-progress game's id, or empty.
   */
  public Optional<UUID> findGameIdByRoomId(String roomId) {
    return gameStore.findByRoomId(roomId).map(Game::id);
  }

  /**
   * Joins {@code roomId} as the second player and creates the {@link Game} for that room in the
   * same atomic step. The caller takes the side opposite to the creator's stored {@link
   * Room#creatorSide()}; the {@link Game}'s {@code white} / {@code black} players are assigned from
   * whichever of the two holds {@link Side#WHITE} (no longer hardcoded creator → white).
   *
   * <p>The read-check-write block runs inside {@link RoomStore#compute}, which serializes
   * concurrent calls for the same {@code roomId}. Inside the block we validate the room, build the
   * updated {@link Room} (two players, status {@link RoomStatus#ACTIVE}), and persist the newly
   * created {@link Game} via {@link GameStore#save(Game)} — all before returning the new room value
   * to the store. A concurrent joiner that loses the race observes the room as full and receives a
   * {@link RoomFullException}, never a half-state.
   *
   * <p><strong>Side effect on success:</strong> after the atomic block returns, the service
   * broadcasts a {@link RoomJoinedEvent} to {@code /topic/rooms/{roomId}} via {@link
   * SimpMessagingTemplate}. The broadcast happens <em>outside</em> the atomic block (the room and
   * game writes are already durable) and is wrapped in a {@code try/catch} that logs at {@code
   * WARN} without rethrowing — mirroring the {@code GameService.broadcastMoveEvent} pattern. If the
   * broadcast fails, the creator's fallback is {@code GET /api/rooms/{id}} via REST.
   *
   * @param roomId the id of the room to join.
   * @param displayName the human-readable name of the joiner.
   * @param currentUserId the authenticated user's id when the request carried a valid Bearer JWT,
   *     {@code null} for anonymous guest joins. Threaded into the new {@link Player}'s {@code
   *     userId} field so the archive path can populate {@code games.black_user_id} when the game
   *     terminates (feature 19, `auth-my-games`).
   * @param providedToken the join token supplied by the caller (feature 22.7). It must equal the
   *     room's stored {@link Room#joinToken()} when that token is non-{@code null}; for a legacy /
   *     unprotected room (stored token {@code null}) the check is skipped and a token-less join is
   *     accepted. Validated <em>after</em> the room-exists check and <em>before</em> the room-full
   *     check, so a caller without the token cannot probe whether a room is full.
   * @return a {@link JoinedRoom} carrying the post-join room, the synthesised joiner player, and
   *     the freshly created game so the caller can surface its id.
   * @throws RoomNotFoundException if no room exists for {@code roomId}.
   * @throws InvalidJoinTokenException if the room is token-protected and {@code providedToken} is
   *     missing or does not match.
   * @throws RoomFullException if the room already holds two players.
   */
  public JoinedRoom joinRoom(
      String roomId, String displayName, UUID currentUserId, String providedToken) {
    Player joiner = new Player(UUID.randomUUID(), displayName, currentUserId);
    // The created game is captured from inside the atomic block so the outer code can return it.
    Game[] createdGameHolder = new Game[1];

    Room updated =
        roomStore.compute(
            roomId,
            (id, existing) -> {
              if (existing == null) {
                throw new RoomNotFoundException(id);
              }
              // Token check (feature 22.7) sits between existence and capacity: a caller without
              // the token gets 403 even on a full room, so we never leak room state to someone who
              // cannot join. A null stored token marks a legacy / unprotected room and short-
              // circuits the check, accepting a token-less join (deploy-safety for in-flight
              // rooms).
              if (existing.joinToken() != null && !existing.joinToken().equals(providedToken)) {
                throw new InvalidJoinTokenException(id);
              }
              if (existing.players().size() >= 2) {
                throw new RoomFullException(id);
              }
              Player creator = existing.players().get(0);
              List<Player> bothPlayers = new ArrayList<>(2);
              bothPlayers.add(creator);
              bothPlayers.add(joiner);
              Side creatorSide = existing.creatorSide();
              TimeControl timeControl = existing.timeControl();
              Room nextRoom =
                  new Room(
                      id,
                      bothPlayers,
                      RoomStatus.ACTIVE,
                      creatorSide,
                      timeControl,
                      existing.joinToken());
              // Assign white/black from the stored creator side rather than from join order.
              Player white = creatorSide == Side.WHITE ? creator : joiner;
              Player black = creatorSide == Side.WHITE ? joiner : creator;
              String initialFen = chessRules.standardInitialState().currentFen();
              // Timed game: both sides start at initialMs and the clock anchor (lastMoveAt) is the
              // game-creation instant, so the elapsed time before white's first move counts against
              // white (per the acceptance). Untimed game: all three clock fields stay null.
              Long whiteMs = timeControl == null ? null : timeControl.initialMs();
              Long blackMs = timeControl == null ? null : timeControl.initialMs();
              Instant lastMoveAt = timeControl == null ? null : Instant.now(clock);
              Long incrementMs = timeControl == null ? null : timeControl.incrementMs();
              Game game =
                  new Game(
                      UUID.randomUUID(),
                      id,
                      white,
                      black,
                      initialFen,
                      initialFen,
                      GameStatus.ONGOING,
                      List.of(),
                      whiteMs,
                      blackMs,
                      lastMoveAt,
                      incrementMs);
              gameStore.save(game);
              createdGameHolder[0] = game;
              return nextRoom;
            });

    Game createdGame = createdGameHolder[0];
    log.info(
        "Room joined: roomId={}, joinerId={}, gameId={}",
        updated.id(),
        joiner.id(),
        createdGame.id());
    // Arm the first flag timer for white outside the atomic block (the game write is durable). For
    // an untimed game this is a no-op. White's deadline is the clock anchor + white's remaining
    // time; if white never moves, the flag fires and GameTimeoutService flags the game on time.
    if (createdGame.isTimed()) {
      Instant whiteDeadline =
          createdGame.lastMoveAt().plusMillis(createdGame.whiteTimeRemainingMs());
      clockTimerManager.scheduleFlag(createdGame.id(), whiteDeadline);
    }
    broadcastRoomJoinedEvent(updated.id(), createdGame.id(), joiner);
    return new JoinedRoom(updated, joiner, createdGame);
  }

  /**
   * Best-effort broadcast of a {@link RoomJoinedEvent} on {@code /topic/rooms/{roomId}}. Runs
   * outside the atomic block so a broker-side failure cannot look like a failed mutation; the
   * mutation has already been persisted. Any {@link RuntimeException} thrown by the broker is
   * logged at {@code WARN} and not rethrown — the {@code GET /api/rooms/{id}} fallback covers
   * subscribers that miss the event.
   */
  private void broadcastRoomJoinedEvent(String roomId, UUID gameId, Player joiner) {
    RoomJoinedEvent event =
        new RoomJoinedEvent(roomId, gameId, RoomJoinedEvent.PlayerView.of(joiner));
    try {
      messagingTemplate.convertAndSend("/topic/rooms/" + roomId, event);
      log.info(
          "Broadcasted RoomJoinedEvent to {}: gameId={}, joinerId={}",
          "/topic/rooms/" + roomId,
          gameId,
          joiner.id());
    } catch (RuntimeException ex) {
      log.warn("Failed to broadcast RoomJoinedEvent for room {}: {}", roomId, ex.getMessage());
    }
  }

  /**
   * Carrier returned from {@link #createRoom(String, SidePreference, UUID, TimeControl,
   * OpponentKind, Integer)}. {@code creator} is the player just synthesised, kept separately so the
   * controller does not have to pick it out of the list. {@code game} is the freshly created vs-bot
   * game ({@link OpponentKind#BOT}) whose id the create response surfaces, or {@code null} for a
   * {@link OpponentKind#FRIEND} room (no game exists until a second human joins).
   */
  public record CreatedRoom(Room room, Player creator, Game game) {}

  /**
   * Carrier returned from {@link #joinRoom(String, String, UUID, String)}. The room is post-join
   * (two players, {@link RoomStatus#ACTIVE}); the {@code joiner} is the player just synthesised;
   * the {@code game} is the freshly created in-progress game whose id the controller surfaces in
   * the response.
   */
  public record JoinedRoom(Room room, Player joiner, Game game) {}
}
