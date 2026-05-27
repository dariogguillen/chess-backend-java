package io.github.dariogguillen.chess.exception;

/**
 * Thrown by {@code AuthService.register} when a registration attempt collides with an existing
 * account's email. Mapped to HTTP 409 with code {@code EMAIL_ALREADY_TAKEN} by {@link
 * GlobalExceptionHandler} (via the {@link ConflictException} branch — the code is derived
 * mechanically from the class's simple name by {@code codeOf}).
 *
 * <p>The application-level uniqueness check ({@code UserRepository.findByEmail}) is the primary
 * trigger; the {@code @Transactional} boundary on {@code register} keeps it atomic with the
 * subsequent insert. The database-level {@code UNIQUE} constraint on {@code users.email} is the
 * safety net for the race between two concurrent registrations that both pass the lookup —
 * Hibernate translates the duplicate insert into a {@code DataIntegrityViolationException}, which
 * {@code AuthService.register} catches and rethrows as this exception so the client sees the same
 * structured 409 regardless of which branch triggered.
 */
public class EmailAlreadyTakenException extends ConflictException {

  /**
   * Builds a new {@code EmailAlreadyTakenException} for the given email.
   *
   * @param email the email that collided with an existing account; surfaced in the response message
   *     so the client can show a helpful error.
   */
  public EmailAlreadyTakenException(String email) {
    super("Email already taken: " + email);
  }
}
