package io.github.dariogguillen.chess.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Room;
import io.github.dariogguillen.chess.domain.RoomStatus;
import io.github.dariogguillen.chess.service.RoomStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration tests for {@link RedisRoomStore} against a real Redis (Testcontainers). Drives the
 * store via the {@link RoomStore} interface so the test exercises exactly the contract the service
 * layer depends on. {@code save → findById} round-trips a full {@link Room} record (id, players,
 * status) intact, and {@code compute} provides atomic read-modify-write under contention on the
 * same key.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisRoomStoreIT {

  @Autowired private RoomStore roomStore;

  @Test
  void save_thenFindById_returnsEqualRoom() {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");
    Player bob = new Player(UUID.randomUUID().toString(), "Bob");
    Room room = new Room(id, List.of(alice, bob), RoomStatus.ACTIVE);

    roomStore.save(room);

    Optional<Room> found = roomStore.findById(id);
    assertThat(found).isPresent();
    Room loaded = found.orElseThrow();
    assertThat(loaded.id()).isEqualTo(id);
    assertThat(loaded.status()).isEqualTo(RoomStatus.ACTIVE);
    assertThat(loaded.players()).containsExactly(alice, bob);
  }

  @Test
  void findById_unknownId_returnsEmpty() {
    assertThat(roomStore.findById(uniqueRoomId())).isEmpty();
  }

  @Test
  void compute_onMissingKey_passesNullToFunctionAndPersistsResult() {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");

    Room created =
        roomStore.compute(
            id,
            (key, existing) -> {
              assertThat(existing).isNull();
              return new Room(key, List.of(alice), RoomStatus.WAITING_FOR_PLAYER);
            });

    assertThat(created.id()).isEqualTo(id);
    assertThat(roomStore.findById(id)).contains(created);
  }

  @Test
  void compute_returningNull_removesKey() {
    String id = uniqueRoomId();
    Player alice = new Player(UUID.randomUUID().toString(), "Alice");
    roomStore.save(new Room(id, List.of(alice), RoomStatus.WAITING_FOR_PLAYER));

    Room result = roomStore.compute(id, (key, existing) -> null);

    assertThat(result).isNull();
    assertThat(roomStore.findById(id)).isEmpty();
  }

  @Test
  void compute_serializesConcurrentCallsOnSameKey() throws Exception {
    // The "join a room" path inside the service is the canonical use of compute: read room,
    // verify capacity, write updated room. We simulate it here by running two threads that each
    // try to append a player. With true atomicity, exactly one of them sees size==1 and wins; the
    // other sees size==2 and rejects. Without atomicity, both would read size==1 and produce a
    // room with three players (rejected by Room's compact constructor with an
    // IllegalArgumentException).
    String id = uniqueRoomId();
    Player creator = new Player(UUID.randomUUID().toString(), "Creator");
    roomStore.save(new Room(id, List.of(creator), RoomStatus.WAITING_FOR_PLAYER));

    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger rejections = new AtomicInteger();

    try {
      for (int i = 0; i < threads; i++) {
        Player joiner = new Player(UUID.randomUUID().toString(), "Joiner-" + i);
        executor.submit(
            () -> {
              try {
                start.await();
                try {
                  roomStore.compute(
                      id,
                      (key, existing) -> {
                        if (existing == null || existing.players().size() >= 2) {
                          throw new IllegalStateException("full");
                        }
                        return new Room(
                            key, List.of(existing.players().get(0), joiner), RoomStatus.ACTIVE);
                      });
                  successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                  rejections.incrementAndGet();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(rejections.get()).isEqualTo(threads - 1);

    Room finalRoom = roomStore.findById(id).orElseThrow();
    assertThat(finalRoom.players()).hasSize(2);
    assertThat(finalRoom.status()).isEqualTo(RoomStatus.ACTIVE);
  }

  @Test
  void compute_propagatesExceptionsFromFunction() {
    String id = uniqueRoomId();

    assertThatThrownBy(
            () ->
                roomStore.compute(
                    id,
                    (key, existing) -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThat(roomStore.findById(id)).isEmpty();
  }

  private static String uniqueRoomId() {
    // Avoid collisions with other ITs that share the same Testcontainers Redis.
    return "ITROOM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
