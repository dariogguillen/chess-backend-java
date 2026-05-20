package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import java.time.Instant;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} after every successful move
 * accepted by {@code GameService.applyMove}. Subscribers receive one event per move; ordering on
 * the wire matches order of move application thanks to the per-game serialization done by {@code
 * GameStore.compute}.
 *
 * <p>The {@code movedBy} and {@code side} fields together let a client decide whether to react to
 * the event — for example, the player that submitted the move via REST already has the new state
 * from the REST response and uses {@code movedBy} as a filter to avoid re-processing its own move.
 * Spectators have no {@code movedBy} of their own and react to every event.
 *
 * <p>{@code promotion} is {@code null} for non-promotion moves; otherwise it carries the uppercase
 * name of a {@link Piece} promotion target ({@code KNIGHT}, {@code BISHOP}, {@code ROOK}, {@code
 * QUEEN}). {@code playedAt} is sourced from the injected {@code Clock}, mirroring the timestamp
 * pattern in {@link ErrorResponse}.
 *
 * @param gameId the id of the game this move was applied to.
 * @param movedBy the id of the player that submitted the move (mirrors {@code X-Player-Id} on the
 *     REST side); lets receivers filter their own moves out client-side.
 * @param side the side that played; redundant with {@code movedBy} server-side but lets the client
 *     display "WHITE played e2-e4" without inferring from {@code moveNumber} parity.
 * @param from the origin square in algebraic notation, e.g. {@code "e2"}.
 * @param to the destination square in algebraic notation, e.g. {@code "e4"}.
 * @param promotion the promotion target enum name, or {@code null} for non-promotion moves.
 * @param fen the resulting position in FEN after the move was applied.
 * @param status the resulting {@link GameStatus} after the move.
 * @param turn the side whose turn it is now (the side opposite to {@code side}); convenience for
 *     receivers that would otherwise compute it from {@code moveNumber} parity.
 * @param moveNumber the 1-based move count after this move (i.e. the total number of half-moves
 *     played in the game).
 * @param playedAt the instant this broadcast was produced, sourced from the service's {@code
 *     Clock}, in UTC.
 */
public record MoveEvent(
    String gameId,
    String movedBy,
    Side side,
    String from,
    String to,
    String promotion,
    String fen,
    GameStatus status,
    Side turn,
    int moveNumber,
    Instant playedAt) {}
