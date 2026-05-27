package io.github.dariogguillen.chess.exception;

/**
 * Thrown by {@code AuthService.authenticate} when a login attempt fails for any reason — the email
 * does not match any account, the password does not match, or any other credentials-shaped failure.
 * The message is deliberately uniform ({@code "Invalid email or password"}) and the constructor
 * takes no arguments, by design: distinguishing "no such user" from "wrong password" in the
 * response would let an attacker enumerate registered email addresses, which is one bit of
 * information they should not get from a failed login attempt.
 *
 * <p>Mapped to HTTP 401 with code {@code INVALID_CREDENTIALS} by {@link GlobalExceptionHandler}.
 * Extends {@link ChessException} directly — no {@code UnauthorizedException} umbrella exists yet,
 * and introducing one for a single 401 would be premature; if a future feature adds a second 401
 * case, the umbrella can be promoted at that point without churning this class.
 */
public class InvalidCredentialsException extends ChessException {

  private static final String UNIFORM_MESSAGE = "Invalid email or password";

  /** Builds a new {@code InvalidCredentialsException} with the uniform message. */
  public InvalidCredentialsException() {
    super(UNIFORM_MESSAGE);
  }
}
