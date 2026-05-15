package io.github.dariogguillen.chess.domain;

import java.util.Objects;

/**
 * A player participating in a {@link Room} or {@link Game}.
 *
 * <p>{@code id} is the opaque identifier used everywhere else in the system. {@code displayName} is
 * the human-readable label shown in the UI. Authentication is out of scope at this stage, so the
 * display name has no uniqueness or content guarantee other than being non-null.
 *
 * @param id the player identifier; must not be null or blank.
 * @param displayName the human-readable name; must not be null, may be blank.
 */
public record Player(String id, String displayName) {

  public Player {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(displayName, "displayName");
    if (id.isBlank()) {
      throw new IllegalArgumentException("Player id must not be blank");
    }
  }
}
