package io.github.dariogguillen.chess.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A player participating in a {@link Room} or {@link Game}.
 *
 * <p>{@code id} is the opaque per-session identifier used everywhere else in the system: minted via
 * {@code UUID.randomUUID()} at the room-create / room-join boundary by {@code RoomService},
 * persisted as a native Postgres {@code uuid} in {@code games.{white,black}_player_id}, and
 * serialised to a plain JSON string on the REST surface. {@code displayName} is the human-readable
 * label shown in the UI; no uniqueness or content guarantee other than being non-null.
 *
 * <p>{@code userId} (added by feature 19, `auth-my-games`) is the optional FK to {@code users(id)}.
 * It is {@code null} for guest players (the live anonymous-play surface stays open) and non-null
 * for players who created or joined a room while authenticated via a Bearer JWT. The compact
 * constructor accepts null on this field by design — the existing checks on {@code id} and {@code
 * displayName} stay, but {@code userId} is the auth-optional signal. Two consequences:
 *
 * <ul>
 *   <li><strong>Wire-format isolation.</strong> {@code Player.userId} must NOT leak to any JSON
 *       response (REST or STOMP). The DTOs that expose player info ({@code GameStateResponse},
 *       {@code RoomJoinedEvent}) project to a dedicated {@code PlayerView(id, displayName)} record
 *       at the boundary so even if a future feature adds a field to {@link Player}, it cannot
 *       silently appear on the wire.
 *   <li><strong>Jackson record deserialization backwards-compatibility.</strong> Active games in
 *       Redis were serialised with the pre-feature-19 {@link Player} shape (no {@code userId}). On
 *       deserialise after the deploy, Jackson invokes the canonical constructor with {@code null}
 *       for the missing field — which this compact constructor allows. In-flight games keep working
 *       with {@code userId = null} on both sides.
 * </ul>
 *
 * @param id the per-session player identifier; must not be null.
 * @param displayName the human-readable name; must not be null, may be blank.
 * @param userId the FK to {@code users(id)} when the player created or joined while authenticated;
 *     {@code null} for guest players. Optional by construction.
 */
public record Player(UUID id, String displayName, UUID userId) {

  public Player {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
    // userId is intentionally NOT requireNonNull-checked — null means "guest".
  }

  /**
   * Convenience constructor for guest players. Equivalent to {@code new Player(id, displayName,
   * null)}. Used by every existing call site that mints a player without auth context.
   *
   * @param id the per-session player identifier; must not be null.
   * @param displayName the human-readable name; must not be null.
   */
  public Player(UUID id, String displayName) {
    this(id, displayName, null);
  }
}
