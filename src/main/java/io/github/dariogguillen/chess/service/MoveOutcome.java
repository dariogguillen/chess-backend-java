package io.github.dariogguillen.chess.service;

import java.util.Objects;

/**
 * The result of asking {@link ChessRules} to apply a move to a {@link GameState}.
 *
 * <p>The contract is <strong>uniform</strong>: {@link #state()} always describes the
 * <em>current</em> state of the board, regardless of whether the requested move was applied or not.
 * Callers never have to remember to fall back to an earlier {@code GameState} variable.
 *
 * <ul>
 *   <li>If {@link #legal()} is {@code true}, the move applied. {@link #state()} is the {@link
 *       GameState} <em>after</em> the move — its {@code history} contains the new move appended,
 *       and {@code currentFen} / {@code currentStatus} reflect the resulting position.
 *   <li>If {@link #legal()} is {@code false}, the move was rejected. {@link #state()} is the
 *       <em>input</em> state unchanged; the same player still has the turn.
 * </ul>
 *
 * <p>Read {@link GameState#currentFen()} and {@link GameState#currentStatus()} off {@link #state()}
 * to obtain what previous shapes of this record exposed directly.
 *
 * @param legal whether the move was applied to the board.
 * @param state the current {@link GameState} (post-move when {@code legal}, otherwise the input
 *     state unchanged); never null.
 */
public record MoveOutcome(boolean legal, GameState state) {

  public MoveOutcome {
    Objects.requireNonNull(state, "state");
  }
}
