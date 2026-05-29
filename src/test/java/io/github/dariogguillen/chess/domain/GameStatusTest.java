package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link GameStatus#isTerminal()} (feature 22, `time-control`, added {@link
 * GameStatus#TIMEOUT}). The terminal/non-terminal partition is pure branching logic the move IT
 * exercises only for the statuses a chesslib position can reach (CHECKMATE / STALEMATE / DRAW); the
 * server-imposed terminal statuses (ABANDONED, TIMEOUT) get their partition asserted here.
 */
class GameStatusTest {

  @Test
  void timeoutIsTerminal() {
    assertThat(GameStatus.TIMEOUT.isTerminal()).isTrue();
  }

  @Test
  void abandonedIsTerminal() {
    assertThat(GameStatus.ABANDONED.isTerminal()).isTrue();
  }

  @Test
  void checkmateStalemateDrawAreTerminal() {
    assertThat(GameStatus.CHECKMATE.isTerminal()).isTrue();
    assertThat(GameStatus.STALEMATE.isTerminal()).isTrue();
    assertThat(GameStatus.DRAW.isTerminal()).isTrue();
  }

  @Test
  void ongoingAndCheckAreNotTerminal() {
    assertThat(GameStatus.ONGOING.isTerminal()).isFalse();
    assertThat(GameStatus.CHECK.isTerminal()).isFalse();
  }
}
