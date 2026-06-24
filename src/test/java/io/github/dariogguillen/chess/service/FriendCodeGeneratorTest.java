package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dariogguillen.chess.persistence.UserRepository;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FriendCodeGenerator}. Pure logic — no Spring context, no container. Earns
 * its place per {@code docs/conventions.md}: the collision-retry branch and the alphabet/length
 * invariants are non-trivial logic the {@code FriendshipIT} cannot deterministically exercise (the
 * IT cannot force a collision against a real {@link SecureRandom}).
 */
@ExtendWith(MockitoExtension.class)
class FriendCodeGeneratorTest {

  @Mock private UserRepository users;

  @Test
  void generateUnique_producesEightCharsOverTheUnambiguousAlphabet() {
    when(users.existsByFriendCode(anyString())).thenReturn(false);
    FriendCodeGenerator generator = new FriendCodeGenerator(users);

    // Generate a batch and assert every character is in the documented alphabet and length is 8.
    Set<Character> allowed = new HashSet<>();
    for (char c : FriendCodeGenerator.ALPHABET.toCharArray()) {
      allowed.add(c);
    }
    for (int i = 0; i < 200; i++) {
      String code = generator.generateUnique();
      assertThat(code).hasSize(FriendCodeGenerator.CODE_LENGTH);
      for (char c : code.toCharArray()) {
        assertThat(allowed).as("char '%s' must be in the unambiguous alphabet", c).contains(c);
      }
    }
  }

  @Test
  void generateUnique_retriesOnCollisionThenReturnsAFreeCode() {
    // The first two candidates collide (already taken), the third is free.
    when(users.existsByFriendCode(anyString())).thenReturn(true, true, false);
    FriendCodeGenerator generator = new FriendCodeGenerator(users);

    String code = generator.generateUnique();

    assertThat(code).hasSize(FriendCodeGenerator.CODE_LENGTH);
    // Three existence checks: two collisions + one free.
    verify(users, times(3)).existsByFriendCode(anyString());
  }

  @Test
  void generateUnique_givesUpAfterMaxAttempts() {
    // Every candidate collides forever — the generator must fail fast rather than loop.
    when(users.existsByFriendCode(anyString())).thenReturn(true);
    FriendCodeGenerator generator = new FriendCodeGenerator(users);

    assertThatThrownBy(generator::generateUnique).isInstanceOf(IllegalStateException.class);
    verify(users, times(FriendCodeGenerator.MAX_ATTEMPTS)).existsByFriendCode(anyString());
  }
}
