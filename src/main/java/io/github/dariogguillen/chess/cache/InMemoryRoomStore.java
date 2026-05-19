package io.github.dariogguillen.chess.cache;

import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.service.RoomStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory implementation of {@link RoomStore} backed by a {@link ConcurrentHashMap}.
 * Lives for the lifetime of the JVM; all state is lost on restart. This is the day-one
 * implementation; feature 7 introduces a Redis-backed variant that replaces this bean without any
 * change to {@code RoomService}.
 *
 * <p>{@code ConcurrentHashMap.compute} runs the remapping function under a per-bin lock, so two
 * concurrent {@code compute} calls on the same room id serialize. {@code RoomService} relies on
 * that to make the join sequence ({@code read room → check invariants → build updated room → write
 * game}) atomic against another concurrent join.
 */
@Component
public class InMemoryRoomStore implements RoomStore {

  private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();

  @Override
  public Optional<Room> findById(String id) {
    return Optional.ofNullable(rooms.get(id));
  }

  @Override
  public void save(Room room) {
    rooms.put(room.id(), room);
  }

  @Override
  public Room compute(String id, BiFunction<String, Room, Room> remappingFunction) {
    return rooms.compute(id, remappingFunction);
  }
}
