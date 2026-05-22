package io.github.dariogguillen.chess.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity for a single move in an archived game. Maps to the {@code moves} table created by
 * {@code V1__create_game_history.sql}.
 *
 * <p>Mutable class on purpose: JPA needs a public no-arg constructor and writable fields it can
 * populate via reflection. Records cannot satisfy that contract for entity-managed instances. The
 * mutability damage is contained by package-private setters — no code outside the {@code
 * persistence} package can observe the entity as anything other than an immutable read-back.
 *
 * <p>Composite key via {@link IdClass}({@link MoveEntityId}): {@code (game_id, move_idx)}. The
 * {@code game} field is both a {@code @ManyToOne} association and an {@code @Id}, which Hibernate
 * resolves by reading the associated entity's primary-key value at flush time. The {@code game_id}
 * column is a native Postgres {@code uuid} — Hibernate pulls {@link GameEntity#getId()} (a {@code
 * java.util.UUID}) and binds it as the FK value with no conversion in between.
 */
@Entity
@Table(name = "moves")
@IdClass(MoveEntityId.class)
public class MoveEntity {

  @Id
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "game_id", nullable = false)
  private GameEntity game;

  @Id
  @Column(name = "move_idx", nullable = false)
  private int moveIdx;

  @Column(name = "from_square", nullable = false, length = 2)
  private String fromSquare;

  @Column(name = "to_square", nullable = false, length = 2)
  private String toSquare;

  /**
   * Promotion piece name (e.g. {@code "QUEEN"}) or {@code null} for non-promotion moves. Plain
   * nullable column — the {@code Optional<Piece>} on the domain side is collapsed to either the
   * inner enum's {@code name()} or SQL {@code NULL}.
   */
  @Column(name = "promotion", length = 10)
  private String promotion;

  /** Required by JPA. Not part of the public API of this class. */
  protected MoveEntity() {}

  MoveEntity(GameEntity game, int moveIdx, String fromSquare, String toSquare, String promotion) {
    this.game = Objects.requireNonNull(game, "game");
    this.moveIdx = moveIdx;
    this.fromSquare = Objects.requireNonNull(fromSquare, "fromSquare");
    this.toSquare = Objects.requireNonNull(toSquare, "toSquare");
    this.promotion = promotion;
  }

  public GameEntity getGame() {
    return game;
  }

  public int getMoveIdx() {
    return moveIdx;
  }

  public String getFromSquare() {
    return fromSquare;
  }

  public String getToSquare() {
    return toSquare;
  }

  public String getPromotion() {
    return promotion;
  }

  void setGame(GameEntity game) {
    this.game = game;
  }

  void setMoveIdx(int moveIdx) {
    this.moveIdx = moveIdx;
  }

  void setFromSquare(String fromSquare) {
    this.fromSquare = fromSquare;
  }

  void setToSquare(String toSquare) {
    this.toSquare = toSquare;
  }

  void setPromotion(String promotion) {
    this.promotion = promotion;
  }
}
