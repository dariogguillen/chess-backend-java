package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Storage seam for {@link Game} aggregates. Paired with {@link RoomStore}: a game is created and
 * saved at the moment the second player joins a room (so the two writes are serialized inside the
 * room-level atomic block). The feature 7 swap to Redis replaces the in-memory implementation
 * without touching the consumers.
 *
 * <p>The {@link #compute(UUID, BiFunction)} seam mirrors {@code ConcurrentHashMap#compute} and
 * gives the move-application path a per-gameId atomic read-check-write block. Implementations are
 * responsible for that atomicity; the in-memory implementation inherits it from {@code
 * ConcurrentHashMap}, the future Redis implementation will use a watch/multi/exec or a Lua script.
 */
public interface GameStore {

  /**
   * Looks up a game by id.
   *
   * @param id the game id.
   * @return the game, if present.
   */
  Optional<Game> findById(UUID id);

  /**
   * Persists the given game, overwriting any prior value at the same id.
   *
   * @param game the game to save.
   */
  void save(Game game);

  /**
   * Atomically applies {@code remappingFunction} to the entry at {@code id}. The function receives
   * the current value (or {@code null} if the key is absent) and returns the new value; returning
   * {@code null} removes the entry. The whole call is serialized against other {@code compute}
   * calls on the same key.
   *
   * @param id the key to compute on.
   * @param remappingFunction the function producing the new value, or {@code null} to remove.
   * @return the new value associated with {@code id}, or {@code null} if removed.
   */
  Game compute(UUID id, BiFunction<UUID, Game, Game> remappingFunction);
}
