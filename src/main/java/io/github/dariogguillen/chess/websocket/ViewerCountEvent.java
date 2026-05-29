package io.github.dariogguillen.chess.websocket;

/**
 * Wire shape broadcast over STOMP to {@code /topic/rooms/{roomId}/viewers} every time the viewer
 * count for a room changes — i.e. on every {@code SUBSCRIBE}, {@code UNSUBSCRIBE}, or session
 * {@code DISCONNECT} that affects the set of sessions watching the room.
 *
 * <p>The viewer count counts <strong>spectators</strong> only — sessions that subscribed to {@code
 * /topic/rooms/{roomId}} without declaring a {@code playerId} STOMP header matching one of the
 * players of the room. Players self-exclude by sending their {@code playerId} as a native header on
 * the {@code SUBSCRIBE} frame; the server compares against the ids of {@code room.players()}. The
 * exclusion is trust-on-self-declaration: the server takes the header at face value because the
 * project has no authentication layer wired into this path yet. A future auth feature would replace
 * "trust" with "verify".
 *
 * <p>The count is keyed on the <strong>room</strong>, not the game (feature 22.5, {@code
 * spectators-in-room}). The room exists from {@code WAITING_FOR_PLAYER} onward, so spectators can
 * be counted before an opponent joins and the count stays stable across the {@code
 * WAITING_FOR_PLAYER → ACTIVE} transition. The game-keyed {@code /topic/games/{gameId}/viewers}
 * topic from feature 6.5 is retired.
 *
 * <p>{@code roomId} identifies which room the count is for; clients may subscribe to several {@code
 * /viewers} topics in parallel and use this field to demultiplex. {@code count} is the current
 * spectator count after the event that triggered the broadcast.
 *
 * @param roomId the 6-char short code of the room whose viewer count changed (not a UUID).
 * @param count the current number of subscribers to {@code /topic/rooms/{roomId}} that are not
 *     players of the room (i.e. did not declare a matching {@code playerId} STOMP header).
 */
public record ViewerCountEvent(String roomId, int count) {}
