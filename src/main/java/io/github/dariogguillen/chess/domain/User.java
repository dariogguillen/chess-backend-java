package io.github.dariogguillen.chess.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The canonical authenticated-user record. Introduced by feature 16 (auth-core) as the foundation
 * of the auth bundle (16-20). A {@code User} represents an account: an email + display name +
 * either a BCrypt password hash (feature 17 — email/password) or a Google {@code sub} claim
 * (feature 18 — OAuth) — both fields are nullable independently because the two registration paths
 * produce different shapes.
 *
 * <p>This is a JPA entity, not a record. The JPA spec requires a no-args constructor and writable
 * fields on managed types; a record's all-args canonical constructor and final fields are
 * incompatible with both. The mutability is contained: setters are package-private so only this
 * package and the {@code persistence} package can mutate an instance, and the constructor below is
 * the only public path to a populated instance.
 *
 * <p>Mapped to the {@code users} table created by {@code V2__create_users_and_game_user_links.sql}.
 * The {@code email} column carries a database-level {@code UNIQUE} constraint; the application
 * normalises email to lowercase before write (feature 17) so case-insensitive uniqueness is
 * enforced at the application boundary without needing Postgres CITEXT. The {@code google_sub}
 * column has a partial unique index ({@code WHERE google_sub IS NOT NULL}) so the index ignores the
 * email-only-users.
 *
 * <p>Column-length caps match the V2 migration's standards-derived choices: {@code email} is {@code
 * VARCHAR(254)} (RFC 5321 path maximum), {@code password_hash} is {@code VARCHAR(60)} (BCrypt's
 * fixed-length output), {@code google_sub} is {@code VARCHAR(255)} (Google's documented {@code sub}
 * upper bound), {@code display_name} is {@code VARCHAR(100)} (same cap V1 uses on {@code
 * games.{white,black}_display_name}). Hibernate's {@code ddl-auto: validate} requires the
 * {@code @Column(length = ...)} on each field to match the column's declared length exactly, so any
 * change here must be mirrored in the migration.
 *
 * <p>This class is placed in {@code domain} (not {@code persistence}) on purpose, mirroring the
 * acceptance criterion. The other persistence-only mutable entity in the codebase ({@code
 * GameEntity}) lives in {@code persistence} because it carries a parallel domain record ({@code
 * Game}); {@code User} has no domain-record counterpart yet (the auth surface is JPA-backed
 * end-to-end), so the entity is its own domain type.
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "email", nullable = false, length = 254, unique = true)
  private String email;

  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  @Column(name = "password_hash", length = 60)
  private String passwordHash;

  @Column(name = "google_sub", length = 255)
  private String googleSub;

  /**
   * The user's shareable friend code (feature 23.8). NOT NULL + UNIQUE at the database level. The
   * value is generated once by {@code FriendCodeGenerator} at user-creation time and never changes;
   * the column length matches {@code V3__add_friend_code_and_friendships.sql}'s {@code VARCHAR(8)}
   * exactly so Hibernate's {@code ddl-auto: validate} accepts it.
   */
  @Column(name = "friend_code", nullable = false, length = 8, unique = true)
  private String friendCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Required by JPA. Not part of the public API of this class. */
  protected User() {}

  /**
   * Constructs a fully populated user. Callers are expected to assign a fresh {@link UUID} via
   * {@code UUID.randomUUID()}, a normalised (lowercase) email, and a unique friend code minted by
   * {@code FriendCodeGenerator}; this constructor does not normalise or validate beyond null-checks
   * delegated to the JPA persistence layer.
   *
   * @param id the user identifier; non-null.
   * @param email the canonical (lowercase) email; non-null, must be unique in the database.
   * @param displayName the human-readable name; non-null.
   * @param passwordHash the BCrypt-hashed password, or {@code null} for OAuth-only users.
   * @param googleSub the Google {@code sub} claim, or {@code null} for email/password-only users.
   * @param friendCode the unique shareable friend code; non-null (feature 23.8).
   * @param createdAt the audit timestamp; non-null.
   */
  public User(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      String googleSub,
      String friendCode,
      Instant createdAt) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.googleSub = googleSub;
    this.friendCode = friendCode;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getGoogleSub() {
    return googleSub;
  }

  public String getFriendCode() {
    return friendCode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  void setEmail(String email) {
    this.email = email;
  }

  void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  void setGoogleSub(String googleSub) {
    this.googleSub = googleSub;
  }
}
