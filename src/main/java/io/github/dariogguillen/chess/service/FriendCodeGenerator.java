package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.persistence.UserRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates the stable, shareable 8-character friend code each {@link
 * io.github.dariogguillen.chess.domain.User} receives at account creation (feature 23.8,
 * friends-list). This is the SINGLE place a friend code is minted, invoked at both user-creation
 * sites ({@code AuthService.register} and the OAuth2 find-or-create handler), so the alphabet and
 * collision policy live in exactly one location.
 *
 * <p>The alphabet mirrors {@link RoomCodeGenerator}'s — the full uppercase Latin letters minus the
 * visually ambiguous {@code I}, {@code L}, {@code O} plus the digits {@code 2-9} (no {@code 0} /
 * {@code 1}) — so a code read aloud or transcribed by a human is unlikely to be misread. With 31
 * characters and length 8 the keyspace is ~852 billion codes, comfortably collision-free for any
 * plausible user population.
 *
 * <p>Unlike {@link RoomCodeGenerator} (which is stateless and leaves collision handling to its
 * caller), this generator OWNS its collision-retry: it queries {@link UserRepository} for an
 * existing owner of each candidate and retries until it finds a free one. The database UNIQUE index
 * on {@code users.friend_code} is the ultimate guarantee; this retry just makes the
 * duplicate-insert path practically unreachable. {@link SecureRandom} is thread-safe, so the single
 * shared instance is fine across concurrent registrations.
 */
@Component
public class FriendCodeGenerator {

  /**
   * Alphabet for generated codes. Identical to {@link RoomCodeGenerator}'s: 23 letters (no {@code
   * I} / {@code L} / {@code O}) + 8 digits ({@code 2-9}) = 31 characters.
   */
  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  static final int ALPHABET_SIZE = ALPHABET.length();

  /** Length in characters of every friend code. */
  public static final int CODE_LENGTH = 8;

  /**
   * Upper bound on collision-retries before giving up. With a ~852-billion keyspace this is never
   * reached in practice; the cap exists only to turn a pathological run (e.g. a misconfigured test)
   * into a fast, explicit failure rather than an infinite loop.
   */
  static final int MAX_ATTEMPTS = 10;

  private final UserRepository users;
  private final SecureRandom random = new SecureRandom();

  public FriendCodeGenerator(UserRepository users) {
    this.users = users;
  }

  /**
   * Produces a fresh 8-character friend code that no existing user owns. Draws candidates uniformly
   * from {@link #ALPHABET} and retries against {@link UserRepository#existsByFriendCode(String)}
   * until a free one is found.
   *
   * @return a unique 8-character code over the documented alphabet.
   * @throws IllegalStateException if {@value #MAX_ATTEMPTS} consecutive candidates all collided — a
   *     practically impossible event that signals a broken environment rather than exhaustion.
   */
  public String generateUnique() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      String candidate = randomCode();
      if (!users.existsByFriendCode(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Failed to generate a unique friend code after " + MAX_ATTEMPTS + " attempts.");
  }

  private String randomCode() {
    char[] code = new char[CODE_LENGTH];
    for (int i = 0; i < CODE_LENGTH; i++) {
      code[i] = ALPHABET.charAt(random.nextInt(ALPHABET_SIZE));
    }
    return new String(code);
  }
}
