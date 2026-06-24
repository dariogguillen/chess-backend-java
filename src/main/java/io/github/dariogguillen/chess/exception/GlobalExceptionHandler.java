package io.github.dariogguillen.chess.exception;

import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralised translation from thrown exceptions into HTTP responses with a shared {@link
 * ErrorResponse} body. The controllers stay free of try/catch — they throw typed exceptions from
 * the {@code exception/} hierarchy and this advice classifies them at the boundary.
 *
 * <p>Two families of inputs are handled:
 *
 * <ul>
 *   <li><strong>Our hierarchy</strong> ({@link NotFoundException}, {@link ConflictException},
 *       {@link UnprocessableException}) — mapped to the corresponding HTTP status and an {@code
 *       error} code derived from the simple class name (e.g. {@code RoomFullException} → {@code
 *       ROOM_FULL}).
 *   <li><strong>Spring's framework exceptions</strong> ({@link MethodArgumentNotValidException} for
 *       Bean Validation failures, {@link HttpMessageNotReadableException} for malformed JSON
 *       bodies, {@link MissingRequestHeaderException} for missing required headers, {@link
 *       MethodArgumentTypeMismatchException} for path or header values that fail Spring's type
 *       converter — e.g. a malformed UUID in {@code /api/games/{id}}) — mapped to HTTP 400 with the
 *       codes {@code VALIDATION_FAILED}, {@code MALFORMED_REQUEST}, {@code MISSING_HEADER}, and
 *       {@code MALFORMED_REQUEST} respectively, so clients see a single response shape regardless
 *       of which layer produced the rejection.
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

  @ExceptionHandler(UnprocessableException.class)
  public ResponseEntity<ErrorResponse> handleUnprocessable(UnprocessableException ex) {
    log.warn("Unprocessable: {}", ex.getMessage());
    return build(HttpStatus.UNPROCESSABLE_ENTITY, codeOf(ex), ex.getMessage());
  }

  /**
   * Maps {@link InvalidCredentialsException} to HTTP 401 / {@code INVALID_CREDENTIALS}. Unlike
   * {@link NotFoundException}, {@link ConflictException}, and {@link UnprocessableException}, this
   * exception has no umbrella superclass in our hierarchy — the single-401 case in the codebase
   * does not yet justify one. The handler is therefore narrow, targeting the concrete class.
   */
  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
    // Deliberately not logging the supplied email or password — both are inputs we never want in
    // the logs. The uniform "Invalid credentials" message is the only trace this path leaves.
    log.warn("Invalid credentials login attempt");
    return build(HttpStatus.UNAUTHORIZED, codeOf(ex), ex.getMessage());
  }

  /**
   * Maps {@link InvalidJoinTokenException} to HTTP 403 / {@code INVALID_JOIN_TOKEN}. Like {@link
   * InvalidCredentialsException}, this exception has no umbrella superclass in our hierarchy — the
   * single-403 case in the codebase does not yet justify one — so the handler is narrow, targeting
   * the concrete class. The code is derived mechanically by {@link #codeOf(ChessException)} ({@code
   * InvalidJoinTokenException} → {@code INVALID_JOIN_TOKEN}).
   */
  @ExceptionHandler(InvalidJoinTokenException.class)
  public ResponseEntity<ErrorResponse> handleInvalidJoinToken(InvalidJoinTokenException ex) {
    log.warn("Invalid join token: {}", ex.getMessage());
    return build(HttpStatus.FORBIDDEN, codeOf(ex), ex.getMessage());
  }

  /**
   * Maps {@link NotRoomMemberException} to HTTP 403 / {@code NOT_ROOM_MEMBER} (feature 23.9, {@code
   * direct-invitations}). Wired identically to {@link InvalidJoinTokenException}: a narrow
   * concrete-class branch, since there is still no {@code ForbiddenException} umbrella in the
   * hierarchy and the two 403 cases do not yet justify one. The code is derived mechanically by
   * {@link #codeOf(ChessException)} ({@code NotRoomMemberException} → {@code NOT_ROOM_MEMBER}).
   */
  @ExceptionHandler(NotRoomMemberException.class)
  public ResponseEntity<ErrorResponse> handleNotRoomMember(NotRoomMemberException ex) {
    log.warn("Not a room member: {}", ex.getMessage());
    return build(HttpStatus.FORBIDDEN, codeOf(ex), ex.getMessage());
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

  /**
   * Maps Spring 6.1+ {@link HandlerMethodValidationException} to HTTP 400 / {@code
   * VALIDATION_FAILED}. Triggered by {@code @Validated} on a controller class plus JSR-380
   * constraints ({@code @Min}, {@code @Max}, etc.) on individual {@code @RequestParam} /
   * {@code @PathVariable} / {@code @RequestHeader} parameters. The pre-6.1 alternative was {@code
   * ConstraintViolationException} (still handled below for legacy paths); the new exception is more
   * structured and reports each violation as a {@code ParameterValidationResult}.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
      HandlerMethodValidationException ex) {
    String message =
        ex.getValueResults().stream()
            .flatMap(pvr -> pvr.getResolvableErrors().stream())
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("Request parameter validation failed.");
    log.warn("Method validation failed: {}", message);
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  /**
   * Maps Jakarta {@link ConstraintViolationException} to HTTP 400 / {@code VALIDATION_FAILED}.
   * Retained as a safety-net for any code path that still surfaces the legacy exception (e.g.
   * service-layer validation triggered from a controller). The Spring 6.1+ canonical path for
   * controller-parameter constraints is {@link HandlerMethodValidationException} above.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
    String message =
        ex.getConstraintViolations().stream()
            .findFirst()
            .map(cv -> cv.getMessage())
            .orElse("Request parameter validation failed.");
    log.warn("Constraint violation: {}", message);
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMalformed(HttpMessageNotReadableException ex) {
    log.warn("Malformed request body: {}", ex.getMessage());
    return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body could not be parsed.");
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    String message = "Required header '" + ex.getHeaderName() + "' is missing.";
    log.warn("Missing request header: {}", ex.getHeaderName());
    return build(HttpStatus.BAD_REQUEST, "MISSING_HEADER", message);
  }

  /**
   * Maps Spring's {@link MethodArgumentTypeMismatchException} to HTTP 400 / {@code
   * MALFORMED_REQUEST}. Triggered when a {@code @PathVariable} or {@code @RequestHeader} value
   * cannot be coerced to the declared parameter type by Spring's default {@code ConversionService}
   * — most commonly a non-UUID string where a {@code java.util.UUID} parameter expects one. Sharing
   * the {@code MALFORMED_REQUEST} code with {@link HttpMessageNotReadableException} keeps the
   * 4xx-vocabulary minimal (see feature 6.6's nine-code allowlist) — both errors are "the bytes you
   * sent could not be turned into a structured input the controller could read".
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleArgumentTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    String message = "Parameter '" + ex.getName() + "' has an invalid value.";
    log.warn("Argument type mismatch: name={}, value={}", ex.getName(), ex.getValue());
    return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", message);
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
