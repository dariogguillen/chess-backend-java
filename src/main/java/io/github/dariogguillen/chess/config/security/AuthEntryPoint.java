package io.github.dariogguillen.chess.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Custom {@link AuthenticationEntryPoint} that writes a structured {@link ErrorResponse} body on
 * every 401 emitted by the security filter chain. Replaces the {@code HttpStatusEntryPoint(401)}
 * that feature 16 wired as a placeholder: feature 17 closes the spec/runtime gap by emitting the
 * same {@link ErrorResponse} envelope that the rest of the API uses, with a new error code {@code
 * AUTHENTICATION_REQUIRED}.
 *
 * <p>Reuses Spring's {@link ObjectMapper} and the application's {@link Clock} bean so the
 * serialisation and the timestamp match what {@code GlobalExceptionHandler.build} produces for the
 * other 4xx codes — clients see a single response shape regardless of which layer rejected the
 * request.
 *
 * <p>The message is intentionally generic ("Authentication is required to access this resource.")
 * to avoid leaking the specific failure mode — a missing header, an expired token, and a forged
 * token all surface identically at this layer, which is the same uniformity that keeps {@link
 * JwtAuthenticationFilter} from logging the JWT failure reason in the response body.
 */
@Component
public class AuthEntryPoint implements AuthenticationEntryPoint {

  static final String CODE = "AUTHENTICATION_REQUIRED";
  static final String MESSAGE = "Authentication is required to access this resource.";

  private final ObjectMapper objectMapper;
  private final Clock clock;

  public AuthEntryPoint(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ErrorResponse body = new ErrorResponse(CODE, MESSAGE, Instant.now(clock));
    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
