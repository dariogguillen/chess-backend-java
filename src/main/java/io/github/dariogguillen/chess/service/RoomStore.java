package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Room;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Storage seam for {@link Room} aggregates. The in-memory implementation backs the feature today
 * and a Redis-backed implementation replaces it in feature 7 without touching the consumers.
 *
 * <p>The seam is intentionally small: the only compound operation we need to be atomic — the
 * read-modify-write performed by a second player joining a room — is exposed as {@link
 * #compute(String, BiFunction)}, mirroring {@code ConcurrentHashMap#compute}. Single-key reads and
 * writes go through {@link #findById(String)} and {@link #save(Room)}.
 *
 * <p>Implementations are responsible for the atomicity of {@code compute} on a given key. The
 * in-memory implementation gets this from {@code ConcurrentHashMap}; the Redis implementation will
 * use a watch/multi/exec or a Lua script.
 */
public interface RoomStore {

  /**
   * Looks up a room by id.
   *
   * @param id the room id.
   * @return the room, if present.
   */
  Optional<Room> findById(String id);

  /**
   * Persists the given room, overwriting any prior value at the same id.
   *
   * @param room the room to save.
   */
  void save(Room room);

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
  Room compute(String id, BiFunction<String, Room, Room> remappingFunction);
}
