package io.github.dariogguillen.chess.exception;

/**
 * Root of the application's custom exception hierarchy.
 *
 * <p>All exceptions thrown by service code that the {@link GlobalExceptionHandler} maps to a
 * structured HTTP response extend this class. Subclasses split the hierarchy by HTTP semantic:
 * {@link NotFoundException} for 404, {@link ConflictException} for 409, and so on. This base type
 * has no behavior of its own; it exists so that the handler can target the family in one place and
 * so that callers can {@code catch (ChessException)} when they truly need the broad net.
 *
 * <p>This is a {@link RuntimeException} — the project does not declare checked exceptions for
 * domain errors. The shape mirrors Spring's own conventions and matches what the controller advice
 * expects.
 */
public abstract class ChessException extends RuntimeException {

  /**
   * Builds a new chess exception with the given message.
   *
   * @param message the human-readable explanation of what went wrong; non-null by convention.
   */
  protected ChessException(String message) {
    super(message);
  }
}
