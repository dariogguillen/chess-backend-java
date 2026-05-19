package io.github.dariogguillen.chess.service;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Constants;
import com.github.bhlangonijr.chesslib.PieceType;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Square;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Chess rule wrapper around the chesslib library.
 *
 * <p>This is the <strong>only</strong> place in the codebase that is allowed to import {@code
 * com.github.bhlangonijr.chesslib}. The rest of the application works with domain types ({@link
 * Move}, {@link Square}, {@link Piece}, {@link GameStatus}) and the service-level {@link
 * GameState}. This class is the anti-corruption layer that translates between them.
 *
 * <p>The service is <strong>stateless</strong>. Every call rebuilds a chesslib {@code Board} from
 * the supplied {@link GameState}: a fresh board is created, {@code
 * loadFromFen(state.startingFen())} is invoked, then every move in {@code state.history()} is
 * replayed. This is the only known way to preserve chesslib's internal position-hash history, which
 * {@code Board.isDraw()} consults to detect threefold repetition. The replay cost is on the order
 * of a fraction of a millisecond for normal game lengths and is invisible on any HTTP path.
 *
 * <p>{@link #initialState(String)} <strong>throws</strong> on an unparseable FEN — invalid FEN is a
 * programmer error (the caller fed garbage), distinct from a runtime illegal move. {@link
 * #applyMove(GameState, Move)} never throws on chess-level concerns; an illegal candidate move is
 * returned as a {@link MoveOutcome} whose {@code legal == false} and whose {@code state} is the
 * input state unchanged.
 */
@Service
public class ChessRules {

  /**
   * Convenience for callers that need the standard chess starting position without having to depend
   * on the chesslib package directly — strengthens the anti-corruption boundary by keeping the
   * {@code com.github.bhlangonijr.chesslib} import contained inside this service.
   *
   * @return the {@link GameState} for the standard chess starting position with no moves played.
   */
  public GameState standardInitialState() {
    return initialState(Constants.startStandardFENPosition);
  }

  /**
   * Builds a fresh {@link GameState} from a starting FEN, with no moves played.
   *
   * <p>The starting FEN is parsed once; the resulting position's canonical FEN (as returned by
   * chesslib's {@code Board.getFen()}) is stored as the state's {@code currentFen}. This may differ
   * from the supplied {@code startingFen} in whitespace or in normalized fields (en-passant target,
   * etc.) — we trust chesslib's canonical form.
   *
   * @param startingFen the FEN of the position the game starts from.
   * @return a {@link GameState} with empty history and the cached current FEN / status computed
   *     from {@code startingFen}.
   * @throws IllegalArgumentException if the FEN is unparseable. This is a <em>programmer</em> error
   *     (the caller fed garbage), not a domain event; distinct from an illegal move at runtime.
   */
  public GameState initialState(String startingFen) {
    Board board = new Board();
    try {
      board.loadFromFen(startingFen);
    } catch (RuntimeException e) {
      // chesslib does not declare what loadFromFen throws; in practice it can raise
      // IllegalArgumentException, StringIndexOutOfBoundsException, NumberFormatException, or
      // MoveGeneratorException depending on how the input is malformed. Rethrow as a single typed
      // IllegalArgumentException carrying the offending FEN so callers do not have to reason about
      // chesslib's loose exception surface.
      throw new IllegalArgumentException("Unparseable FEN: " + startingFen, e);
    }
    return new GameState(startingFen, List.of(), board.getFen(), mapStatus(board));
  }

  /**
   * Applies {@code move} to the position described by {@code state}.
   *
   * <p>The position is reconstructed by loading {@code state.startingFen()} and replaying every
   * move in {@code state.history()} on a fresh {@code Board}; only then is the candidate {@code
   * move} evaluated. This replay is what preserves the position history that chesslib uses for
   * threefold-repetition detection — a board loaded from a single FEN cannot detect it because a
   * FEN does not carry history.
   *
   * <p>The returned {@link MoveOutcome} always carries the current {@link GameState}:
   *
   * <ul>
   *   <li>When {@code legal == true}, the state is the new state after {@code move} was appended.
   *   <li>When {@code legal == false}, the state is the input {@code state} unchanged. Same player
   *       still has the turn.
   * </ul>
   *
   * @param state the current game state; not null.
   * @param move the candidate move; not null. The compact constructor of {@link Move} enforces
   *     structural invariants; this method enforces chess legality.
   * @return a {@link MoveOutcome} describing whether the move applied and the current state.
   */
  public MoveOutcome applyMove(GameState state, Move move) {
    Board board = new Board();
    board.loadFromFen(state.startingFen());
    for (Move historical : state.history()) {
      com.github.bhlangonijr.chesslib.move.Move replay =
          toChesslibMove(historical, board.getSideToMove());
      board.doMove(replay);
    }

    com.github.bhlangonijr.chesslib.move.Move chessMove =
        toChesslibMove(move, board.getSideToMove());

    // chesslib's doMove(Move, true) only performs structural validation (piece exists, right
    // color, etc.) and happily executes moves that are not actually legal — e.g. a pawn jumping
    // three squares. The authoritative "is this move legal in this position?" check is membership
    // in the generated legal-move set. We check it ourselves and let doMove do the mutation
    // without re-validating.
    if (!board.legalMoves().contains(chessMove)) {
      return new MoveOutcome(false, state);
    }
    board.doMove(chessMove);

    List<Move> nextHistory = new ArrayList<>(state.history().size() + 1);
    nextHistory.addAll(state.history());
    nextHistory.add(move);
    GameState nextState =
        new GameState(state.startingFen(), nextHistory, board.getFen(), mapStatus(board));
    return new MoveOutcome(true, nextState);
  }

  /** Maps a domain {@link Move} to a chesslib {@code Move}. */
  private static com.github.bhlangonijr.chesslib.move.Move toChesslibMove(
      Move move, com.github.bhlangonijr.chesslib.Side sideToMove) {
    com.github.bhlangonijr.chesslib.Square from = toChesslibSquare(move.from());
    com.github.bhlangonijr.chesslib.Square to = toChesslibSquare(move.to());
    com.github.bhlangonijr.chesslib.Piece promotion =
        move.promotion()
            .map(p -> toChesslibPromotion(p, sideToMove))
            .orElse(com.github.bhlangonijr.chesslib.Piece.NONE);
    return new com.github.bhlangonijr.chesslib.move.Move(from, to, promotion);
  }

  /** Maps a domain {@link Square} to a chesslib {@code Square}. */
  private static com.github.bhlangonijr.chesslib.Square toChesslibSquare(Square square) {
    // Domain Square stores lowercase algebraic notation ("e4"); chesslib's enum uses uppercase
    // ("E4"). Square.valueOf is the canonical lookup.
    return com.github.bhlangonijr.chesslib.Square.valueOf(square.value().toUpperCase());
  }

  /**
   * Combines a side-agnostic domain {@link Piece} with the side to move to pick the chesslib
   * promotion piece (e.g. {@code WHITE_QUEEN} vs {@code BLACK_QUEEN}).
   *
   * <p>The domain {@link Move} compact constructor already rejects {@code PAWN} and {@code KING},
   * so those cannot reach this method on a well-formed move; we map them defensively to the
   * corresponding {@code PieceType} and let chesslib reject the move at the board level if a caller
   * built a {@code Move} object directly.
   */
  private static com.github.bhlangonijr.chesslib.Piece toChesslibPromotion(
      Piece promotion, com.github.bhlangonijr.chesslib.Side sideToMove) {
    PieceType type =
        switch (promotion) {
          case KNIGHT -> PieceType.KNIGHT;
          case BISHOP -> PieceType.BISHOP;
          case ROOK -> PieceType.ROOK;
          case QUEEN -> PieceType.QUEEN;
          case PAWN -> PieceType.PAWN;
          case KING -> PieceType.KING;
        };
    return com.github.bhlangonijr.chesslib.Piece.make(sideToMove, type);
  }

  /**
   * Maps the chesslib board state to our {@link GameStatus}.
   *
   * <p>Order matters: {@code isMated()} implies {@code isKingAttacked()}, so checkmate must be
   * checked before plain check. {@code isStaleMate()} is a draw by no-legal-moves; {@code isDraw()}
   * covers the other draw rules (insufficient material, 50-move, threefold repetition — the last
   * being the reason this service replays history rather than loading a single FEN).
   *
   * <p>{@link GameStatus#ABANDONED} is intentionally not produced here; abandonment is an external
   * event.
   */
  private static GameStatus mapStatus(Board board) {
    if (board.isMated()) {
      return GameStatus.CHECKMATE;
    }
    if (board.isStaleMate()) {
      return GameStatus.STALEMATE;
    }
    if (board.isDraw()) {
      return GameStatus.DRAW;
    }
    if (board.isKingAttacked()) {
      return GameStatus.CHECK;
    }
    return GameStatus.ONGOING;
  }
}
