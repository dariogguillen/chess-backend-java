package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link User}.
 *
 * <p>{@link JpaRepository} provides the {@code save / findById / delete / count} surface used by
 * the future auth issuance flow (feature 17). Two derived queries are added on top: {@link
 * #findByEmail(String)} powers the email/password login path, and {@link #findByGoogleSub(String)}
 * powers the Google-OAuth callback that looks up an existing account by the provider's {@code sub}
 * claim before creating a new {@code User}.
 *
 * <p>Both id and query-parameter types are exact matches for the column types — {@code UUID} for
 * the primary key and {@code String} for the two unique-indexed columns — so Spring Data binds them
 * directly to the native Postgres types with no converter.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Finds a user by their canonical (lowercase) email. Callers are responsible for normalising
   * input before calling this method; the column is case-sensitive on the database side and the
   * application boundary normalises consistently.
   *
   * @param email the canonical email to look up.
   * @return the matching user, or {@link Optional#empty()} if no row matches.
   */
  Optional<User> findByEmail(String email);

  /**
   * Finds a user by their Google {@code sub} claim. The column has a partial unique index, so this
   * query is index-backed for non-null inputs; passing a {@code null} returns empty (no row has a
   * NULL {@code googleSub} that would match an equality predicate).
   *
   * @param googleSub the Google subject identifier; non-null in practice.
   * @return the matching user, or {@link Optional#empty()} if no row matches.
   */
  Optional<User> findByGoogleSub(String googleSub);
}
