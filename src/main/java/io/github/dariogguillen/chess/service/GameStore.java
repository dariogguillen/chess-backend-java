package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import java.util.Optional;

/**
 * Storage seam for {@link Game} aggregates. Paired with {@link RoomStore}: a game is created and
 * saved at the moment the second player joins a room (so the two writes are serialized inside the
 * room-level atomic block). The feature 7 swap to Redis replaces the in-memory implementation
 * without touching the consumers.
 */
public interface GameStore {

  /**
   * Looks up a game by id.
   *
   * @param id the game id.
   * @return the game, if present.
   */
  Optional<Game> findById(String id);

  /**
   * Persists the given game, overwriting any prior value at the same id.
   *
   * @param game the game to save.
   */
  void save(Game game);
}
