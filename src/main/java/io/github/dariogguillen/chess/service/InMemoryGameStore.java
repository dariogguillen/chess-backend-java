package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.Game;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Thread-safe in-memory implementation of {@link GameStore} backed by a {@link ConcurrentHashMap}.
 * Saved-and-forgotten until a future read; no cross-key consistency is needed at this layer because
 * the only cross-store invariant ("a game exists iff its room is {@code ACTIVE}") is enforced
 * inside the {@code RoomStore#compute} block of {@code RoomService.joinRoom}.
 */
@Service
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
}
