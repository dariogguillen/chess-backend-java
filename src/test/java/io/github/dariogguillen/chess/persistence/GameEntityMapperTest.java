package io.github.dariogguillen.chess.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GameEntityMapper}. The mapper is the single point that translates between
 * the immutable domain {@link Game} (which uses {@code Optional<Piece>} for promotions) and the
 * mutable {@link GameEntity} (which uses a nullable {@code String} column). The {@code
 * Optional<Piece>} ↔ nullable string conversion is the load-bearing case here — covered with one
 * promotion and one non-promotion move in the same game so a single round-trip exercises both
 * sides.
 *
 * <p>Player and game ids are typed {@link UUID} end-to-end; the mapper passes them through as the
 * native type with no string conversion in either direction.
 */
class GameEntityMapperTest {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String FINAL_FEN =
      "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";
  private static final Instant FIXED_NOW = Instant.parse("2026-05-21T10:00:00Z");
  private static final UUID GAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID WHITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID BLACK_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private final GameEntityMapper mapper =
      new GameEntityMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

  @Test
  void toEntity_nonPromotionMove_promotionColumnIsNull() {
    Move nonPromotion = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Game game = newGame(GameStatus.CHECKMATE, List.of(nonPromotion));

    GameEntity entity = mapper.toEntity(game);

    assertThat(entity.getMoves()).hasSize(1);
    MoveEntity move = entity.getMoves().get(0);
    assertThat(move.getFromSquare()).isEqualTo("e2");
    assertThat(move.getToSquare()).isEqualTo("e4");
    assertThat(move.getPromotion()).isNull();
    assertThat(move.getMoveIdx()).isZero();
    assertThat(move.getGame()).isSameAs(entity);
  }

  @Test
  void toEntity_promotionMove_promotionColumnIsEnumName() {
    Move promotion = new Move(new Square("a7"), new Square("a8"), Optional.of(Piece.QUEEN));
    Game game = newGame(GameStatus.CHECKMATE, List.of(promotion));

    GameEntity entity = mapper.toEntity(game);

    assertThat(entity.getMoves()).hasSize(1);
    MoveEntity move = entity.getMoves().get(0);
    assertThat(move.getFromSquare()).isEqualTo("a7");
    assertThat(move.getToSquare()).isEqualTo("a8");
    assertThat(move.getPromotion()).isEqualTo("QUEEN");
  }

  @Test
  void toEntity_copiesScalarFields_andSetsEndedAtFromClock() {
    Game game = newGame(GameStatus.STALEMATE, List.of());

    GameEntity entity = mapper.toEntity(game);

    assertThat(entity.getId()).isEqualTo(GAME_ID);
    assertThat(entity.getRoomId()).isEqualTo(game.roomId());
    assertThat(entity.getWhitePlayerId()).isEqualTo(WHITE_ID);
    assertThat(entity.getWhiteDisplayName()).isEqualTo("Alice");
    assertThat(entity.getBlackPlayerId()).isEqualTo(BLACK_ID);
    assertThat(entity.getBlackDisplayName()).isEqualTo("Bob");
    assertThat(entity.getStartingFen()).isEqualTo(STARTING_FEN);
    assertThat(entity.getFinalFen()).isEqualTo(FINAL_FEN);
    assertThat(entity.getStatus()).isEqualTo(GameStatus.STALEMATE);
    assertThat(entity.getEndedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void toEntity_preservesMoveOrder() {
    Move first = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Move second = new Move(new Square("a7"), new Square("a8"), Optional.of(Piece.KNIGHT));
    Move third = new Move(new Square("g1"), new Square("f3"), Optional.<Piece>empty());
    Game game = newGame(GameStatus.CHECKMATE, List.of(first, second, third));

    GameEntity entity = mapper.toEntity(game);

    assertThat(entity.getMoves()).hasSize(3);
    assertThat(entity.getMoves().get(0).getMoveIdx()).isZero();
    assertThat(entity.getMoves().get(1).getMoveIdx()).isEqualTo(1);
    assertThat(entity.getMoves().get(2).getMoveIdx()).isEqualTo(2);
    assertThat(entity.getMoves().get(1).getPromotion()).isEqualTo("KNIGHT");
  }

  @Test
  void toDomain_roundTrip_preservesOptionalPromotionShape() {
    Move nonPromotion = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    Move withPromotion = new Move(new Square("a7"), new Square("a8"), Optional.of(Piece.QUEEN));
    Game original = newGame(GameStatus.CHECKMATE, List.of(nonPromotion, withPromotion));

    GameEntity entity = mapper.toEntity(original);
    Game roundTripped = mapper.toDomain(entity);

    assertThat(roundTripped.id()).isEqualTo(original.id());
    assertThat(roundTripped.roomId()).isEqualTo(original.roomId());
    assertThat(roundTripped.white()).isEqualTo(original.white());
    assertThat(roundTripped.black()).isEqualTo(original.black());
    assertThat(roundTripped.startingFen()).isEqualTo(original.startingFen());
    assertThat(roundTripped.fen()).isEqualTo(original.fen());
    assertThat(roundTripped.status()).isEqualTo(original.status());
    assertThat(roundTripped.moves()).containsExactly(nonPromotion, withPromotion);
    // The promotion's Optional shape is the load-bearing concern: empty must stay empty,
    // present must stay present with the same inner value.
    assertThat(roundTripped.moves().get(0).promotion()).isEmpty();
    assertThat(roundTripped.moves().get(1).promotion()).contains(Piece.QUEEN);
  }

  @Test
  void toEntity_carriesResult_andToDomainMapsItBack() {
    Game original = newGame(GameStatus.CHECKMATE, List.of()).withResult(GameResult.WHITE_WIN);

    GameEntity entity = mapper.toEntity(original);
    assertThat(entity.getResult()).isEqualTo(GameResult.WHITE_WIN);

    Game roundTripped = mapper.toDomain(entity);
    assertThat(roundTripped.result()).isEqualTo(GameResult.WHITE_WIN);
  }

  @Test
  void toEntity_nullResult_mapsToNull() {
    // An 8-arg game leaves result null (a non-terminal or legacy archive shape).
    Game original = newGame(GameStatus.CHECKMATE, List.of());

    GameEntity entity = mapper.toEntity(original);
    assertThat(entity.getResult()).isNull();
    assertThat(mapper.toDomain(entity).result()).isNull();
  }

  private static Game newGame(GameStatus status, List<Move> moves) {
    Player white = new Player(WHITE_ID, "Alice");
    Player black = new Player(BLACK_ID, "Bob");
    return new Game(GAME_ID, "ROOM01", white, black, STARTING_FEN, FINAL_FEN, status, moves);
  }
}
