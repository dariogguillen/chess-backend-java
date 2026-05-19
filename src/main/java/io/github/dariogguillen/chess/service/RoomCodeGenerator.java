package io.github.dariogguillen.chess.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates short, human-friendly room codes — six characters drawn uniformly from an alphabet that
 * omits visually ambiguous glyphs ({@code I}, {@code L}, {@code O}, {@code 0}, {@code 1}) so that a
 * code read aloud or transcribed by a human is unlikely to be misread. The alphabet has {@value
 * #ALPHABET_SIZE} characters, giving a keyspace of {@value #ALPHABET_SIZE}^6 = ~887M codes, which
 * is more than enough for any plausible concurrent room population in this project.
 *
 * <p>The generator is stateless apart from a single {@link SecureRandom} instance shared across
 * threads — {@code SecureRandom} is thread-safe. It does <strong>not</strong> consult any store;
 * collision handling lives in the caller ({@code RoomService.createRoom} retries up to a small
 * number of times if the generated code already exists).
 */
@Component
public class RoomCodeGenerator {

  /**
   * Alphabet for generated codes. Letters: the full uppercase Latin alphabet minus {@code I},
   * {@code L}, {@code O} (visually ambiguous with {@code 1} / {@code 0}). Digits: {@code 2-9} (no
   * {@code 0} / {@code 1}). Total: 23 letters + 8 digits = 31 characters.
   */
  static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  static final int ALPHABET_SIZE = ALPHABET.length();

  /** Length in characters of every code produced by {@link #generate()}. */
  public static final int CODE_LENGTH = 6;

  private final SecureRandom random = new SecureRandom();

  /**
   * Produces a fresh six-character code drawn uniformly from {@link #ALPHABET}. The method makes no
   * uniqueness guarantees; the caller is responsible for collision handling against whatever store
   * it persists rooms into.
   *
   * @return a six-character code over the documented alphabet.
   */
  public String generate() {
    char[] code = new char[CODE_LENGTH];
    for (int i = 0; i < CODE_LENGTH; i++) {
      code[i] = ALPHABET.charAt(random.nextInt(ALPHABET_SIZE));
    }
    return new String(code);
  }
}
