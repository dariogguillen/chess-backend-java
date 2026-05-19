package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.service.GameStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory implementation of {@link GameStore} backed by a {@link ConcurrentHashMap}.
 * Saved-and-forgotten until a future read; no cross-key consistency is needed at this layer because
 * the only cross-store invariant ("a game exists iff its room is {@code ACTIVE}") is enforced
 * inside the {@code RoomStore#compute} block of {@code RoomService.joinRoom}.
 *
 * <p>{@link #compute(String, BiFunction)} delegates straight to {@link
 * ConcurrentHashMap#compute(Object, BiFunction)}, which serializes calls on the same key — exactly
 * what the move-application path in {@code GameService} relies on for read-check-write atomicity.
 */
@Component
public class InMemoryGameStore implements GameStore {

  private final ConcurrentMap<String, Game> games = new ConcurrentHashMap<>();

  @Override
  public Optional<Game> findById(String id) {
    return Optional.ofNullable(games.get(id));
  }

  @Override
  public void save(Game game) {
    games.put(game.id(), game);
  }

  @Override
  public Game compute(String id, BiFunction<String, Game, Game> remappingFunction) {
    return games.compute(id, remappingFunction);
  }
}
