package io.github.dariogguillen.chess.service.auth;

import io.github.dariogguillen.chess.config.security.JwtIssuer;
import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.EmailAlreadyTakenException;
import io.github.dariogguillen.chess.exception.InvalidCredentialsException;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.web.auth.AuthResponse;
import io.github.dariogguillen.chess.web.auth.LoginRequest;
import io.github.dariogguillen.chess.web.auth.MeResponse;
import io.github.dariogguillen.chess.web.auth.RegisterRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-layer entry point for the two auth flows landed by feature 17: register and authenticate.
 * Wraps the {@link UserRepository} write/read with the {@link PasswordEncoder} (the {@link
 * org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder} bean exposed by feature 16) and
 * the {@link JwtIssuer} that mints the token returned to the caller. Controllers stay routing-only:
 * they translate HTTP to a service call and the resulting {@link AuthResponse} back to a body.
 *
 * <p>Two uniformity invariants govern the API surface:
 *
 * <ul>
 *   <li><strong>Email normalisation.</strong> Both flows lowercase + trim the input email before
 *       touching the repository. The {@code users.email} column is case-sensitive on the database
 *       side; normalising at the application boundary means a user who signed up as {@code
 *       Alice@Example.com} can sign in as {@code alice@example.com} without a CITEXT extension.
 *       Login normalises with the same rule so the lookup hits the same row.
 *   <li><strong>Uniform failure on authenticate.</strong> An unknown email and a wrong password
 *       both throw {@link InvalidCredentialsException} (401 / {@code INVALID_CREDENTIALS}). The
 *       implementation also calls {@link PasswordEncoder#matches} against a dummy hash on the
 *       unknown-email branch so the response time does not leak whether the email exists.
 * </ul>
 *
 * <p>Plain-text passwords appear only on the local stack inside {@link #register} and {@link
 * #authenticate} as parameters; they are never logged, never returned, and never propagated to
 * other classes — the only thing the rest of the system sees is the BCrypt hash on {@link
 * User#getPasswordHash()} or, for the response, the JWT {@link JwtIssuer} produces.
 */
@Service
public class AuthService {

  /**
   * BCrypt hash of a placeholder string. Used on the unknown-email branch of {@link #authenticate}
   * so the response time of "no such user" matches "wrong password". Pre-computed (cost factor 10)
   * so the call site does not re-run a hash on every unknown-email attempt.
   */
  private static final String DUMMY_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtIssuer jwtIssuer;
  private final Clock clock;

  public AuthService(
      UserRepository users, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer, Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.jwtIssuer = jwtIssuer;
    this.clock = clock;
  }

  /**
   * Registers a new user and returns an immediately usable JWT. The email-uniqueness check and the
   * insert run inside a single transaction. The database-level {@code UNIQUE} constraint on {@code
   * users.email} closes the race window between two concurrent registrations for the same email:
   * Hibernate throws {@link DataIntegrityViolationException} on the duplicate insert, which this
   * method translates into {@link EmailAlreadyTakenException} so the client sees the same
   * structured 409 regardless of which branch triggered.
   *
   * @param request the validated registration request; {@code email} / {@code password} / {@code
   *     displayName} are guaranteed non-blank by Jakarta Validation at the controller boundary.
   * @return the issued token plus the canonical {@link MeResponse} payload for the freshly created
   *     user.
   * @throws EmailAlreadyTakenException when the normalised email already exists.
   */
  @Transactional
  public AuthResponse register(RegisterRequest request) {
    String email = normaliseEmail(request.email());
    if (users.findByEmail(email).isPresent()) {
      throw new EmailAlreadyTakenException(email);
    }
    User user =
        new User(
            UUID.randomUUID(),
            email,
            request.displayName(),
            passwordEncoder.encode(request.password()),
            null,
            Instant.now(clock));
    User saved;
    try {
      saved = users.save(user);
    } catch (DataIntegrityViolationException ex) {
      // The race-condition safety net: two concurrent registrations for the same email both pass
      // the findByEmail check, but the DB UNIQUE constraint rejects the second insert. Surface
      // the same structured 409 the application-level branch produces.
      throw new EmailAlreadyTakenException(email);
    }
    String token = jwtIssuer.issue(saved);
    return new AuthResponse(token, toMeResponse(saved));
  }

  /**
   * Authenticates an email/password pair and returns a fresh JWT. The failure path is uniform: an
   * unknown email and a wrong password both throw {@link InvalidCredentialsException} with the same
   * generic message; the implementation also runs a BCrypt comparison against a dummy hash on the
   * unknown-email branch so the timing channel does not leak whether the email exists.
   *
   * @param request the validated login request; {@code email} and {@code password} are guaranteed
   *     non-blank by Jakarta Validation at the controller boundary.
   * @return the issued token plus the canonical {@link MeResponse} payload for the user.
   * @throws InvalidCredentialsException when the email is unknown or the password does not match.
   */
  public AuthResponse authenticate(LoginRequest request) {
    String email = normaliseEmail(request.email());
    Optional<User> lookup = users.findByEmail(email);
    User user = lookup.orElse(null);
    String storedHash = user != null ? user.getPasswordHash() : DUMMY_HASH;
    // BCryptPasswordEncoder.matches handles a null/blank stored hash by returning false, but we
    // also have OAuth-only users (no password_hash). Treat them the same as unknown emails to
    // avoid leaking the existence of an account that only authenticates via Google.
    boolean ok = passwordEncoder.matches(request.password(), storedHash);
    if (!ok || user == null || user.getPasswordHash() == null) {
      throw new InvalidCredentialsException();
    }
    String token = jwtIssuer.issue(user);
    return new AuthResponse(token, toMeResponse(user));
  }

  private static String normaliseEmail(String email) {
    return email.trim().toLowerCase();
  }

  private static MeResponse toMeResponse(User user) {
    return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName());
  }
}
