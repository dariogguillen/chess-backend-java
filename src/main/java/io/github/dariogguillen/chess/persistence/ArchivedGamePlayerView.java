package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * JPQL constructor-projection view of an archived game enriched with its move count, used by {@link
 * GameHistoryRepository#findByPlayerId(UUID, org.springframework.data.domain.Limit)}.
 *
 * <p>The view is a plain immutable record — JPQL's {@code SELECT new <fqn>(...)} clause invokes the
 * canonical constructor at result-set extraction time. Carrying the move count as part of the
 * projection keeps the controller free of any further JPA access, which matters because the
 * application runs with {@code spring.jpa.open-in-view: false}: outside the service's transaction
 * the entity manager is closed and lazy associations cannot be navigated.
 *
 * @param id the archived game's identifier.
 * @param roomId the id of the room the game was played in.
 * @param whitePlayerId the white player's id.
 * @param whiteDisplayName the white player's display name.
 * @param blackPlayerId the black player's id.
 * @param blackDisplayName the black player's display name.
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
    GameStatus status,
    Instant endedAt,
    int moveCount) {}
