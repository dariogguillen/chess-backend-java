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

  /**
   * The fixed sentinel {@code id} of the Stockfish bot player (feature 23, {@code bot-opponent}).
   *
   * <p>The sentinel lives on {@code id}, NOT on {@code userId}. {@code userId} is the FK to {@code
   * users(id)}; the bot is not a real {@code User} row, so an invented {@code userId} would break
   * the archive write (the {@code games.{white,black}_user_id} foreign key would reference a
   * non-existent user). The bot therefore archives exactly like a guest ({@code userId == null})
   * and is recognised everywhere else by this constant {@code id} via {@link #isBot()}.
   */
  public static final UUID BOT_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000005706f1");

  /** The human-readable label the bot player carries in the UI and in archived game records. */
  public static final String BOT_DISPLAY_NAME = "Stockfish";

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

  /**
   * Factory for the Stockfish bot player (feature 23, {@code bot-opponent}). Always the same {@link
   * #BOT_PLAYER_ID} / {@link #BOT_DISPLAY_NAME} with a {@code null} {@code userId} — see {@link
   * #BOT_PLAYER_ID} for why the sentinel is the id, not the userId.
   *
   * @return the canonical bot player.
   */
  public static Player bot() {
    return new Player(BOT_PLAYER_ID, BOT_DISPLAY_NAME, null);
  }

  /**
   * Whether this player is the Stockfish bot, identified by its sentinel {@link #BOT_PLAYER_ID}.
   * Used by {@code BotMoveService} to decide whether the side to move is the engine's, and by the
   * room-create path to trigger the bot's first move when it plays white.
   *
   * @return {@code true} when {@code id} equals {@link #BOT_PLAYER_ID}.
   */
  public boolean isBot() {
    return BOT_PLAYER_ID.equals(id);
  }
}
