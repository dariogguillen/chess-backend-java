package io.github.dariogguillen.chess.exception;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised translation from thrown exceptions into HTTP responses with a shared {@link
 * ErrorResponse} body. The controllers stay free of try/catch — they throw typed exceptions from
 * the {@code exception/} hierarchy and this advice classifies them at the boundary.
 *
 * <p>Two families of inputs are handled:
 *
 * <ul>
 *   <li><strong>Our hierarchy</strong> ({@link NotFoundException}, {@link ConflictException}) —
 *       mapped to the corresponding HTTP status and an {@code error} code derived from the simple
 *       class name (e.g. {@code RoomFullException} → {@code ROOM_FULL}).
 *   <li><strong>Spring's framework exceptions</strong> ({@link MethodArgumentNotValidException} for
 *       Bean Validation failures, {@link HttpMessageNotReadableException} for malformed JSON
 *       bodies) — mapped to HTTP 400 with the codes {@code VALIDATION_FAILED} and {@code
 *       MALFORMED_REQUEST} respectively, so clients see a single response shape regardless of which
 *       layer produced the rejection.
 * </ul>
 *
 * <p>The {@link Clock} is injected so that the {@code timestamp} in error bodies is testable and
 * deterministic when the test pins the clock.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final Clock clock;

  public GlobalExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
    log.warn("Resource not found: {}", ex.getMessage());
    return build(HttpStatus.NOT_FOUND, codeOf(ex), ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
    log.warn("Conflict: {}", ex.getMessage());
    return build(HttpStatus.CONFLICT, codeOf(ex), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .orElse("Request validation failed.");
    log.warn("Validation failed: {}", message);
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMalformed(HttpMessageNotReadableException ex) {
    log.warn("Malformed request body: {}", ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed.");
  }

  private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(error, message, Instant.now(clock)));
  }

  /**
   * Turns {@code RoomFullException} into {@code ROOM_FULL}, {@code RoomNotFoundException} into
   * {@code ROOM_NOT_FOUND}, and so on — strips the trailing {@code "Exception"} and converts
   * camelCase to UPPER_SNAKE_CASE. Keeping the mapping mechanical means new exceptions in the
   * hierarchy do not require a new branch here.
   */
  private static String codeOf(ChessException ex) {
    String simple = ex.getClass().getSimpleName();
    if (simple.endsWith("Exception")) {
      simple = simple.substring(0, simple.length() - "Exception".length());
    }
    StringBuilder out = new StringBuilder(simple.length() + 4);
    for (int i = 0; i < simple.length(); i++) {
      char c = simple.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) {
        out.append('_');
      }
      out.append(Character.toUpperCase(c));
    }
    return out.toString();
  }
}
