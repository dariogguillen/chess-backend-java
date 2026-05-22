package io.github.dariogguillen.chess.persistence;

import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.util.UUID;

/**
 * Composite identifier for {@link MoveEntity}, used by JPA via {@link IdClass}.
 *
 * <p>JPA's {@code @IdClass} contract demands a class with:
 *
 * <ul>
 *   <li>A no-arg constructor (records satisfy this implicitly — the canonical constructor with
 *       default values is reached via reflection by Hibernate, which understands records since
 *       6.x).
 *   <li>Public field or property accessors whose names and types match the {@code @Id} fields on
 *       the owning entity ({@code game} and {@code moveIdx} here).
 *   <li>{@code equals} / {@code hashCode} based on those components — records get both for free.
 *   <li>{@code java.io.Serializable} — the JPA spec requires it because providers may serialise ids
 *       for caching or remoting; modern Hibernate does not strictly need it in-process but the
 *       declaration costs nothing and keeps us spec-compliant.
 * </ul>
 *
 * <p>The field {@code game} is the owning entity's id type ({@link UUID}, the parent game id),
 * <em>not</em> the entity reference itself — JPA matches by the {@code @Id} field's resolved type,
 * and the corresponding {@code @Id} on {@link MoveEntity#getGame()} is a {@code @ManyToOne} whose
 * target's id is a {@link UUID}.
 *
 * @param game the id of the parent game; matches {@code MoveEntity#game} resolved to the parent's
 *     primary-key value.
 * @param moveIdx the zero-based index of the move within the game; matches {@code
 *     MoveEntity#moveIdx}.
 */
public record MoveEntityId(UUID game, int moveIdx) implements Serializable {}
