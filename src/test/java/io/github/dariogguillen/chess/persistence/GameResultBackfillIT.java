package io.github.dariogguillen.chess.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dariogguillen.chess.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises the {@code V4__add_game_result.sql} backfill derivation against the real Testcontainers
 * Postgres. The migration already ran at context start (Flyway), so we cannot replay it against
 * pre-existing rows; instead we re-seed rows in the <em>pre-result shape</em> ({@code result} left
 * NULL) and run the same backfill {@code UPDATE} the migration ships, then assert the derived value
 * row by row.
 *
 * <p>This is the FEN-active-color derivation in SQL: for a {@code CHECKMATE} / {@code TIMEOUT}, the
 * side to move in the final FEN (its second space-separated field) is the loser, so 'w' ->
 * BLACK_WIN and 'b' -> WHITE_WIN. {@code STALEMATE} / {@code DRAW} -> DRAW. {@code ABANDONED} stays
 * NULL because the abandoner is not encoded in the FEN.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GameResultBackfillIT {

  private static final String BACKFILL_UPDATE =
      """
      UPDATE games
      SET result = CASE
          WHEN status IN ('STALEMATE', 'DRAW') THEN 'DRAW'
          WHEN status IN ('CHECKMATE', 'TIMEOUT') AND split_part(final_fen, ' ', 2) = 'w'
              THEN 'BLACK_WIN'
          WHEN status IN ('CHECKMATE', 'TIMEOUT') AND split_part(final_fen, ' ', 2) = 'b'
              THEN 'WHITE_WIN'
          ELSE NULL
      END
      WHERE id = ?
      """;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void backfill_derivesResultFromStatusAndFinalFen() {
    // Final FENs differing in the active-color field (2nd space-separated token): 'w' / 'b'.
    String whiteToMoveFen = "8/8/8/8/8/8/8/k1K1Q3 w - - 0 1";
    String blackToMoveFen = "8/8/8/8/8/8/8/K1k1q3 b - - 0 1";

    UUID checkmateWhiteLost = seed("CHECKMATE", whiteToMoveFen);
    UUID checkmateBlackLost = seed("CHECKMATE", blackToMoveFen);
    UUID timeoutWhiteLost = seed("TIMEOUT", whiteToMoveFen);
    UUID timeoutBlackLost = seed("TIMEOUT", blackToMoveFen);
    UUID stalemate = seed("STALEMATE", whiteToMoveFen);
    UUID draw = seed("DRAW", blackToMoveFen);
    UUID abandoned = seed("ABANDONED", whiteToMoveFen);

    // CHECKMATE / TIMEOUT: white to move = white lost = BLACK_WIN; black to move = WHITE_WIN.
    assertThat(backfilledResult(checkmateWhiteLost)).isEqualTo("BLACK_WIN");
    assertThat(backfilledResult(checkmateBlackLost)).isEqualTo("WHITE_WIN");
    assertThat(backfilledResult(timeoutWhiteLost)).isEqualTo("BLACK_WIN");
    assertThat(backfilledResult(timeoutBlackLost)).isEqualTo("WHITE_WIN");
    // STALEMATE / DRAW -> DRAW.
    assertThat(backfilledResult(stalemate)).isEqualTo("DRAW");
    assertThat(backfilledResult(draw)).isEqualTo("DRAW");
    // ABANDONED is unrecoverable -> stays NULL.
    assertThat(backfilledResult(abandoned)).isNull();
  }

  private String backfilledResult(UUID id) {
    jdbc.update(BACKFILL_UPDATE, id);
    return jdbc.queryForObject("SELECT result FROM games WHERE id = ?", String.class, id);
  }

  private UUID seed(String status, String finalFen) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO games (id, room_id, white_player_id, white_display_name, black_player_id, "
            + "black_display_name, starting_fen, final_fen, status, result, ended_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
        id,
        "ROOM01",
        UUID.randomUUID(),
        "Alice",
        UUID.randomUUID(),
        "Bob",
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        finalFen,
        status,
        Timestamp.from(Instant.parse("2026-05-21T10:00:00Z")));
    return id;
  }
}
