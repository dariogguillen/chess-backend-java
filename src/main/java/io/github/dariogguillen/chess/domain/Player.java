package io.github.dariogguillen.chess.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A player participating in a {@link Room} or {@link Game}.
 *
 * <p>{@code id} is the opaque identifier used everywhere else in the system. {@code displayName} is
 * the human-readable label shown in the UI. Authentication is out of scope at this stage, so the
 * display name has no uniqueness or content guarantee other than being non-null.
 *
 * <p>The id is a {@link UUID}: every site that mints a {@link Player} today does so via {@code
 * UUID.randomUUID()}, the persistence layer maps it to a native Postgres {@code uuid} column, and
 * Jackson serialises it to a plain JSON string on the REST surface. Carrying the typed {@link UUID}
 * end-to-end keeps the boundary honest — bad input fails at parse time rather than as a downstream
 * SQL or runtime surprise.
 *
 * @param id the player identifier; must not be null.
 * @param displayName the human-readable name; must not be null, may be blank.
 */
public record Player(UUID id, String displayName) {

  public Player {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
  }
}
