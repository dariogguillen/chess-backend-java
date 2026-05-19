package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoomCodeGenerator}.
 *
 * <p>The generator is non-trivial despite its size: it must respect a documented alphabet, a fixed
 * length, and produce output uniform enough that collisions in a small sample are vanishingly rare.
 * Each property below is exercised over many generated codes — the kind of large-input combination
 * that {@code docs/conventions.md} lists as a legitimate reason to add a unit test even when an IT
 * exists.
 */
class RoomCodeGeneratorTest {

  /** Number of generations per property. Large enough to catch a wrong-alphabet bug instantly. */
  private static final int SAMPLE_SIZE = 1000;

  private final RoomCodeGenerator generator = new RoomCodeGenerator();

  @Test
  void generate_alwaysReturnsCodeOfDocumentedLength() {
    IntStream.range(0, SAMPLE_SIZE)
        .forEach(
            i -> {
              String code = generator.generate();
              assertThat(code)
                  .as("code #%d must have length %d", i, RoomCodeGenerator.CODE_LENGTH)
                  .hasSize(RoomCodeGenerator.CODE_LENGTH);
            });
  }

  @Test
  void generate_onlyUsesCharactersFromDocumentedAlphabet() {
    Set<Character> allowed = new HashSet<>();
    for (char c : RoomCodeGenerator.ALPHABET.toCharArray()) {
      allowed.add(c);
    }

    for (int i = 0; i < SAMPLE_SIZE; i++) {
      String code = generator.generate();
      for (int idx = 0; idx < code.length(); idx++) {
        char c = code.charAt(idx);
        assertThat(allowed)
            .as("code '%s' (#%d) char at %d (%c) must be in the alphabet", code, i, idx, c)
            .contains(c);
      }
    }
  }

  @Test
  void generate_neverIncludesVisuallyAmbiguousCharacters() {
    // Belt-and-braces version of the alphabet check. If someone ever widens the alphabet to
    // include 0/1/I/L/O, this test fails loudly with the offending char in the message.
    Set<Character> forbidden = Set.of('0', '1', 'I', 'L', 'O');

    for (int i = 0; i < SAMPLE_SIZE; i++) {
      String code = generator.generate();
      for (int idx = 0; idx < code.length(); idx++) {
        char c = code.charAt(idx);
        assertThat(forbidden)
            .as("code '%s' (#%d) must not contain visually ambiguous char '%c'", code, i, c)
            .doesNotContain(c);
      }
    }
  }

  @Test
  void generate_distributesOverManySamplesWithoutObviousCollision() {
    // 1000 codes drawn from a 31^6 ≈ 8.87e8 keyspace: the expected number of collisions is
    // ~5e-4 (birthday-paradox approximation N^2 / 2K). Asserting "no duplicates in 1000 samples"
    // catches the catastrophic case where the generator returns a constant or stops varying part
    // of the code, while keeping a vanishingly small false-failure rate.
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < SAMPLE_SIZE; i++) {
      String code = generator.generate();
      assertThat(seen).as("duplicate code '%s' at iteration %d", code, i).doesNotContain(code);
      seen.add(code);
    }
    assertThat(seen).hasSize(SAMPLE_SIZE);
  }
}
