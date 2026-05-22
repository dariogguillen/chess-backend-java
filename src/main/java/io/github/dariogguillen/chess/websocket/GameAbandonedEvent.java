package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.GameStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}} when a game transitions to
 * {@link GameStatus#ABANDONED} because one of the players lost their STOMP session and did not
 * resubscribe within the configured grace period.
 *
 * <p>The event is fired by {@code GameAbandonService.abandon} <em>after</em> the {@code
 * GameStore.compute} block flips the game status and {@code GameHistoryService.archive} writes the
 * archive row. The same topic also carries {@link MoveEvent} — until feature 11.5 ({@code
 * disconnect-notifications}) refactors the topic into a sealed-interface family with a {@code type}
 * discriminator, subscribers distinguish the two by <strong>shape</strong>: a {@code MoveEvent}
 * carries {@code from}/{@code to}/{@code moveNumber}; a {@code GameAbandonedEvent} carries {@code
 * abandonedBy}/{@code winnerId}. This duplication is a deliberate deferral, documented in {@code
 * notes/11-disconnect-handling.md}.
 *
 * @param gameId the id of the game that was abandoned; matches the {@code {gameId}} segment in the
 *     topic.
 * @param abandonedBy the id of the player whose session dropped and was not restored before the
 *     grace period elapsed; this is the loser.
 * @param winnerId the id of the opponent — derived server-side so the frontend does not need a
 *     second lookup.
 * @param finalFen the FEN at the moment of abandonment (the position frozen on the board); the game
 *     is terminal at this point and the FEN does not change again.
 * @param abandonedAt the instant the abandonment was finalized, sourced from the service's injected
 *     {@link java.time.Clock} so tests can pin it deterministically; in UTC.
 */
public record GameAbandonedEvent(
    UUID gameId, UUID abandonedBy, UUID winnerId, String finalFen, Instant abandonedAt) {}
