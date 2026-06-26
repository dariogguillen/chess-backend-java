package io.github.dariogguillen.chess.service;

import io.github.dariogguillen.chess.domain.User;
import io.github.dariogguillen.chess.exception.InvalidCredentialsException;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.web.auth.MeResponse;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-layer owner of the authenticated user's own-profile mutations (feature 23.91): renaming
 * the display name and changing the password. Kept separate from {@code AuthService} so that
 * service stays focused on register/login; the two profile operations share neither flow nor
 * failure semantics with authentication and read more clearly as their own use case.
 *
 * <p>Both methods re-load the {@link User} by id inside their transaction and mutate the
 * <em>managed</em> entity through {@code User}'s intention-revealing domain mutators ({@code
 * rename} / {@code changePasswordHash}). The change is flushed by Hibernate's dirty checking at
 * transaction commit — no explicit {@code save} is required (one would be harmless). This is
 * deliberate: the {@code @AuthenticationPrincipal User} the controller resolves is a
 * <em>detached</em> instance loaded by the JWT filter on a now-closed persistence context; mutating
 * it directly would not persist. Re-loading inside the transaction yields a managed instance bound
 * to the live context.
 *
 * <p>Plain-text passwords appear only on the local stack inside {@link #changePassword} as
 * parameters; they are never logged, never returned, and never propagated — only the BCrypt hash
 * produced by the {@link PasswordEncoder} leaves this class, written onto the entity.
 */
@Service
public class ProfileService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  public ProfileService(UserRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Renames the authenticated user's display name. Re-loads the managed entity, applies {@link
   * User#rename(String)}, and relies on dirty-checking flush. Leaves id, email, createdAt, and
   * friend code untouched.
   *
   * @param userId the authenticated user's id (from the JWT {@code sub} /
   *     {@code @AuthenticationPrincipal}).
   * @param newName the validated new display name; non-blank, capped at 100 chars by {@code
   *     UpdateProfileRequest}.
   * @return the updated {@link MeResponse} reflecting the new name.
   * @throws IllegalStateException if no user exists for the id — a defensive edge, since the JWT
   *     filter has already loaded this user for the request to be authenticated.
   */
  @Transactional
  public MeResponse updateDisplayName(UUID userId, String newName) {
    User user = loadManaged(userId);
    user.rename(newName);
    return MeResponse.of(user);
  }

  /**
   * Changes the authenticated user's password. Verifies {@code currentPassword} against the stored
   * BCrypt hash via {@link PasswordEncoder#matches}, then writes the new hash via {@link
   * User#changePasswordHash(String)} (dirty-checking flush). A wrong current password — or an
   * OAuth-only account whose {@code password_hash} is {@code null}, for which {@code matches}
   * returns {@code false} — fails uniformly with {@link InvalidCredentialsException} (401), with no
   * leak of whether the account is OAuth-only.
   *
   * @param userId the authenticated user's id.
   * @param currentPassword the user's existing plain-text password; verified, never stored.
   * @param newPassword the validated replacement (8-72 chars by {@code ChangePasswordRequest});
   *     BCrypt-hashed before persistence.
   * @throws InvalidCredentialsException if the current password does not match the stored hash
   *     (including the OAuth-only null-hash case).
   * @throws IllegalStateException if no user exists for the id (see {@link #updateDisplayName}).
   */
  @Transactional
  public void changePassword(UUID userId, String currentPassword, String newPassword) {
    User user = loadManaged(userId);
    if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    user.changePasswordHash(passwordEncoder.encode(newPassword));
  }

  private User loadManaged(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(
            () -> new IllegalStateException("Authenticated user not found by id: " + userId));
  }
}
