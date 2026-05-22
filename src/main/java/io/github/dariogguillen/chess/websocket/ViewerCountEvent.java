package io.github.dariogguillen.chess.websocket;

import java.util.UUID;

/**
 * Wire shape broadcast over STOMP to {@code /topic/games/{gameId}/viewers} every time the viewer
 * count for a game changes — i.e. on every {@code SUBSCRIBE}, {@code UNSUBSCRIBE}, or session
 * {@code DISCONNECT} that affects the set of sessions watching the game.
 *
 * <p>The viewer count counts <strong>spectators</strong> only — sessions that subscribed to {@code
 * /topic/games/{gameId}} without declaring a {@code playerId} STOMP header matching one of the two
 * players of the game. Players self-exclude by sending their {@code playerId} as a native header on
 * the {@code SUBSCRIBE} frame; the server compares against {@code white.id()} and {@code
 * black.id()}. The exclusion is trust-on-self-declaration: the server takes the header at face
 * value because the project has no authentication layer yet. A future auth feature would replace
 * "trust" with "verify".
 *
 * <p>{@code gameId} identifies which game the count is for; clients may subscribe to several {@code
 * /viewers} topics in parallel and use this field to demultiplex. {@code count} is the current
 * spectator count after the event that triggered the broadcast.
 *
 * @param gameId the id of the game whose viewer count changed.
 * @param count the current number of subscribers to {@code /topic/games/{gameId}} that are not one
 *     of the two players (i.e. did not declare a matching {@code playerId} STOMP header).
 */
public record ViewerCountEvent(UUID gameId, int count) {}
