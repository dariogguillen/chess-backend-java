package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Boundary mapper between the domain {@link Game} (an immutable record) and the JPA {@link
 * GameEntity} (a mutable entity-managed class).
 *
 * <p>This mapper is a {@link Component} rather than a static utility for two reasons: (1) it
 * carries an injected {@link Clock} so the archive timestamp can be pinned in tests; (2) keeping it
 * as a Spring bean makes the seam explicit and testable in isolation from the repository.
 *
 * <p>Since both the domain and the entity carry {@code java.util.UUID} for every id field, the
 * mapper's id-side work is identity — no {@code .toString()} on the way in, no {@code
 * UUID.fromString(...)} on the way out. The two non-identity conversions left are: (a) {@code
 * Optional<Piece>} on the domain side becomes a nullable {@code String} (the piece's enum {@code
 * name()}) on the entity side; (b) {@link Square} is unwrapped to its canonical lowercase string
 * value, e.g. {@code "e2"}.
 */
@Component
public class GameEntityMapper {

  private final Clock clock;

  public GameEntityMapper(Clock clock) {
    this.clock = clock;
  }

  /**
   * Converts a terminal-status domain {@link Game} into a fresh {@link GameEntity} ready to be
   * persisted. The {@code endedAt} timestamp is taken from the injected {@link Clock} at call time;
   * re-archiving the same game id will overwrite the timestamp, which is acceptable because the
   * archive path is idempotent on the same terminal state.
   *
   * @param game the domain game; expected to be in a terminal {@link GameStatus} (this mapper does
   *     not check — the service layer is the gate).
   * @return a fresh {@link GameEntity} with its {@link MoveEntity} children wired up.
   */
  public GameEntity toEntity(Game game) {
    GameEntity entity =
        new GameEntity(
            game.id(),
            game.roomId(),
            game.white().id(),
            game.white().displayName(),
            game.black().id(),
            game.black().displayName(),
            game.startingFen(),
            game.fen(),
            game.status(),
            Instant.now(clock));

    List<MoveEntity> moveEntities = new ArrayList<>(game.moves().size());
    int idx = 0;
    for (Move move : game.moves()) {
      String promotion = move.promotion().map(Enum::name).orElse(null);
      moveEntities.add(
          new MoveEntity(entity, idx, move.from().value(), move.to().value(), promotion));
      idx++;
    }
    entity.setMoves(moveEntities);
    return entity;
  }

  /**
   * Converts a persisted {@link GameEntity} (with its {@link MoveEntity} children eagerly fetched
   * by the caller, e.g. inside a {@code @Transactional} read path) back to a domain {@link Game}.
   * Used for tests and for any future read API that wants the full domain shape — the production
   * history endpoint uses {@link GameEntity} directly because it only needs summary data.
   *
   * @param entity the persisted entity; non-null.
   * @return the equivalent domain game.
   */
  public Game toDomain(GameEntity entity) {
    Player white = new Player(entity.getWhitePlayerId(), entity.getWhiteDisplayName());
    Player black = new Player(entity.getBlackPlayerId(), entity.getBlackDisplayName());
    List<Move> moves = new ArrayList<>(entity.getMoves().size());
    for (MoveEntity me : entity.getMoves()) {
      Optional<Piece> promotion =
          me.getPromotion() == null
              ? Optional.empty()
              : Optional.of(Piece.valueOf(me.getPromotion()));
      moves.add(new Move(new Square(me.getFromSquare()), new Square(me.getToSquare()), promotion));
    }
    return new Game(
        entity.getId(),
        entity.getRoomId(),
        white,
        black,
        entity.getStartingFen(),
        entity.getFinalFen(),
        entity.getStatus(),
        moves);
  }
}
