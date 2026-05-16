package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import java.util.List;
import java.util.Objects;

/**
 * The complete input a chess-rule decision needs: where the game started, every move played since,
 * and a cached view of the current position.
 *
 * <p>The shape exists because chesslib's {@code Board} keeps an internal hash of every position it
 * has seen, and that history is what {@code Board.isDraw()} consults to detect threefold
 * repetition. A FEN string does not carry history (only the 50-move halfmove counter), so a board
 * reconstructed from a single FEN can never report threefold repetition. To preserve the history,
 * {@link ChessRules} replays {@link #history()} on every call against a fresh board loaded from
 * {@link #startingFen()}. The {@link #currentFen()} and {@link #currentStatus()} fields cache the
 * derived view so callers (the HTTP layer, the WebSocket layer, persistence) can read the current
 * position without re-running chesslib.
 *
 * <h2>Construction contract</h2>
 *
 * <p>{@code GameState} should be constructed via {@link ChessRules#initialState(String)} and
 * subsequent states should be obtained from {@link MoveOutcome#state()}. The canonical record
 * constructor is technically public — Java does not let us hide it cleanly — but the cached {@code
 * currentFen} / {@code currentStatus} are <strong>trusted</strong> by the compact constructor: we
 * do not re-derive them from {@code startingFen + history} because that would defeat the caching.
 * The factory methods on {@link ChessRules} are the contract; constructing this record directly is
 * a footgun reserved for serialization frameworks.
 *
 * <h2>Scala / Typelevel parallel</h2>
 *
 * <p>This is the "value handed in, value handed out" shape — equivalent to a {@code case class
 * GameState} in Typelevel with a smart constructor on a companion. The service stays pure (no
 * mutable state, no {@code Ref[F, S]}); the caller is responsible for threading the current state
 * through, the same way you would thread an {@code S} through a {@code State[F, S, A]} or hold it
 * in a {@code Ref[F, S]} that you manage at the edge.
 *
 * @param startingFen the FEN of the position the game started from; not null.
 * @param history every move played since {@code startingFen}, in order; defensively copied so the
 *     stored list is independent of the argument.
 * @param currentFen the FEN of the position after replaying {@code history} from {@code
 *     startingFen}; trusted, not re-derived.
 * @param currentStatus the {@link GameStatus} of the position described by {@code currentFen};
 *     trusted, not re-derived.
 */
public record GameState(
    String startingFen, List<Move> history, String currentFen, GameStatus currentStatus) {

  public GameState {
    Objects.requireNonNull(startingFen, "startingFen");
    Objects.requireNonNull(history, "history");
    history = List.copyOf(history);
    Objects.requireNonNull(currentFen, "currentFen");
    Objects.requireNonNull(currentStatus, "currentStatus");
  }
}
