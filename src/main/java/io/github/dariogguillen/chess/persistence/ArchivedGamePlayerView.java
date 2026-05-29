package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * JPQL constructor-projection view of an archived game enriched with its move count, used by {@link
 * GameHistoryRepository#findByPlayerId(UUID, org.springframework.data.domain.Limit)} and {@link
 * GameHistoryRepository#findByUserId(UUID, org.springframework.data.domain.Pageable)}.
 *
 * <p>The view is a plain immutable record — JPQL's {@code SELECT new <fqn>(...)} clause invokes the
 * canonical constructor at result-set extraction time. Carrying the move count as part of the
 * projection keeps the controller free of any further JPA access, which matters because the
 * application runs with {@code spring.jpa.open-in-view: false}: outside the service's transaction
 * the entity manager is closed and lazy associations cannot be navigated.
 *
 * <p>Feature 19 (`auth-my-games`) extended the projection with {@code whiteUserId} and {@code
 * blackUserId} so the {@code MyGamesController} can determine which side of the archived game the
 * authenticated user sat on by id comparison — same shape as {@code PlayerGamesController} uses on
 * {@code whitePlayerId}, but driven by the user FK instead of the per-session player UUID. The new
 * fields are populated by the JPQL projection clause, never touched by the {@code findByPlayerId}
 * query (which doesn't need them); the constructor sees them as {@code null} when the side was
 * anonymous at game time.
 *
 * @param id the archived game's identifier.
 * @param roomId the id of the room the game was played in.
 * @param whitePlayerId the white player's per-session id.
 * @param whiteDisplayName the white player's display name.
 * @param blackPlayerId the black player's per-session id.
 * @param blackDisplayName the black player's display name.
 * @param whiteUserId the FK to {@code users(id)} for the white side; {@code null} for guest white.
 * @param blackUserId the FK to {@code users(id)} for the black side; {@code null} for guest black.
 * @param status the terminal status of the game.
 * @param endedAt the archive timestamp.
 * @param moveCount the number of move rows associated with the game ({@code SIZE(g.moves)} in the
 *     JPQL projection).
 */
public record ArchivedGamePlayerView(
    UUID id,
    String roomId,
    UUID whitePlayerId,
    String whiteDisplayName,
    UUID blackPlayerId,
    String blackDisplayName,
    UUID whiteUserId,
    UUID blackUserId,
    GameStatus status,
    Instant endedAt,
    int moveCount) {}
