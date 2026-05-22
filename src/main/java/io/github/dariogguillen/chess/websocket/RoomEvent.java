package io.github.dariogguillen.chess.websocket;

/**
 * Discriminated union for STOMP events broadcast on {@code /topic/rooms/{roomId}}. Each variant
 * carries an explicit {@code type} field — serialized as a plain JSON property — so subscribers can
 * branch on the variant without polymorphic Jackson machinery ({@code @JsonTypeInfo}).
 *
 * <p>The sealed-interface + explicit-discriminator pattern is the design rule for STOMP topics that
 * carry more than one event variant; single-event topics (today: {@code MoveEvent} on {@code
 * /topic/games/{gameId}}, {@code ViewerCountEvent} on {@code /topic/games/{gameId}/viewers}) stay
 * flat without a discriminator. Adding a new variant requires extending the {@code permits} clause
 * and providing the same {@code type} + {@code roomId} fields.
 *
 * <p>Today the only variant is {@link RoomJoinedEvent}; {@code RoomClosedEvent} and {@code
 * PlayerLeftEvent} are future features that this seal anticipates.
 */
public sealed interface RoomEvent permits RoomJoinedEvent {

  /**
   * The discriminator string. Each variant returns its own stable, upper-snake-case constant (e.g.
   * {@code "ROOM_JOINED"}).
   */
  String type();

  /**
   * The room id every variant carries. Matches the {@code {roomId}} segment in the topic
   * destination.
   */
  String roomId();
}
