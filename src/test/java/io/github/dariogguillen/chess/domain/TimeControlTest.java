package io.github.dariogguillen.chess.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the {@link TimeControl} value object's compact-constructor validation (feature
 * 22, `time-control`). A pure-logic record with a non-trivial invariant — it earns its place per
 * the conventions (a domain edge the IT does not assert directly).
 */
class TimeControlTest {

  @Test
  void shouldConstruct_whenInitialPositiveAndIncrementZero() {
    TimeControl tc = new TimeControl(300_000L, 0L);

    assertThat(tc.initialMs()).isEqualTo(300_000L);
    assertThat(tc.incrementMs()).isZero();
  }

  @Test
  void shouldConstruct_whenInitialPositiveAndIncrementPositive() {
    TimeControl tc = new TimeControl(300_000L, 3_000L);

    assertThat(tc.initialMs()).isEqualTo(300_000L);
    assertThat(tc.incrementMs()).isEqualTo(3_000L);
  }

  @Test
  void shouldReject_whenInitialIsZero() {
    assertThatThrownBy(() -> new TimeControl(0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialMs");
  }

  @Test
  void shouldReject_whenInitialIsNegative() {
    assertThatThrownBy(() -> new TimeControl(-1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("initialMs");
  }

  @Test
  void shouldReject_whenIncrementIsNegative() {
    assertThatThrownBy(() -> new TimeControl(300_000L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("incrementMs");
  }
}
