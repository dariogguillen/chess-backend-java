package io.github.dariogguillen.chess.websocket;

import io.github.dariogguillen.chess.domain.Player;
import java.util.UUID;

/**
 * Variant of {@link RoomEvent} broadcast to {@code /topic/rooms/{roomId}} the moment a second
 * player joins a room and the chess game is created — i.e. immediately after the room transitions
 * from {@code WAITING_FOR_PLAYER} to {@code ACTIVE}.
 *
 * <p>Player A (the creator) is the canonical subscriber: subscribing right after {@code POST
 * /api/rooms} returns and waiting for this event is how A learns the {@code gameId} so it can
 * transition to {@code /topic/games/{gameId}}. The discriminator {@code type} field is the constant
 * {@code "ROOM_JOINED"} — set via the convenience constructor, never recomputed at read-time.
 *
 * <p>Subscribers that arrive <em>after</em> the join miss the event entirely (STOMP
 * fire-and-forget, no replay). The fallback in that case is {@code GET /api/rooms/{id}} — the REST
 * companion of this topic, which carries the same {@code gameId} (and the rest of the room state)
 * for reconcile.
 *
 * @param type the discriminator constant {@code "ROOM_JOINED"}; set by the convenience constructor.
 * @param roomId the room the join happened on; matches the topic's {@code {roomId}} segment.
 * @param gameId the id of the freshly created game.
 * @param blackPlayer the joiner — i.e. the second player, who became {@code BLACK}.
 */
public record RoomJoinedEvent(String type, String roomId, UUID gameId, Player blackPlayer)
    implements RoomEvent {

  /** Stable discriminator value emitted in JSON as {@code "type":"ROOM_JOINED"}. */
  public static final String TYPE = "ROOM_JOINED";

  /**
   * Convenience constructor — the only call site producing this event. The {@link #type} field is
   * always {@link #TYPE}; making it explicit in the record (rather than computing it via
   * {@code @JsonTypeInfo}) keeps the discriminator visible at the source.
   *
   * @param roomId the room id; matches the topic destination's {@code {roomId}} segment.
   * @param gameId the freshly created game's id.
   * @param blackPlayer the joining player (becomes BLACK).
   */
  public RoomJoinedEvent(String roomId, UUID gameId, Player blackPlayer) {
    this(TYPE, roomId, gameId, blackPlayer);
  }
}
