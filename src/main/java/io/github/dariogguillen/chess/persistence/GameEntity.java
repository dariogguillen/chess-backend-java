package io.github.dariogguillen.chess.persistence;

import io.github.dariogguillen.chess.domain.GameStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity for an archived (terminal-status) game. Maps to the {@code games} table created by
 * {@code V1__create_game_history.sql}.
 *
 * <p>Mutable class on purpose, like {@link MoveEntity}: JPA requires a no-arg constructor and
 * writable fields. The mutability damage is contained by package-private setters and a {@link
 * #setMoves(List)} that defensively wraps the input — callers outside this package do not get a
 * handle that lets them mutate the entity's internal list.
 *
 * <p>{@link #id}, {@link #whitePlayerId}, and {@link #blackPlayerId} are {@link UUID}s mapped to
 * native Postgres {@code uuid} columns — Hibernate has a built-in {@code UUID ↔ uuid} binding so no
 * {@code @JdbcTypeCode} or converter is needed. {@link #roomId} stays a {@link String} because the
 * room id is the 6-char short code, not a UUID.
 *
 * <p>The {@link #status} field is mapped with {@link EnumType#STRING}: the column stores the enum
 * constant's name (e.g. {@code "CHECKMATE"}). The alternative, {@link EnumType#ORDINAL}, would
 * store the declaration order as an integer and silently corrupt data if a future {@link
 * GameStatus} value is inserted before the existing ones. STRING trades a few bytes per row for
 * forward compatibility.
 */
@Entity
@Table(name = "games")
public class GameEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "room_id", nullable = false, length = 6)
  private String roomId;

  @Column(name = "white_player_id", nullable = false)
  private UUID whitePlayerId;

  @Column(name = "white_display_name", nullable = false, length = 100)
  private String whiteDisplayName;

  @Column(name = "black_player_id", nullable = false)
  private UUID blackPlayerId;

  @Column(name = "black_display_name", nullable = false, length = 100)
  private String blackDisplayName;

  @Column(name = "starting_fen", nullable = false, length = 100)
  private String startingFen;

  @Column(name = "final_fen", nullable = false, length = 100)
  private String finalFen;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private GameStatus status;

  @Column(name = "ended_at", nullable = false)
  private Instant endedAt;

  /**
   * Move history, ordered by {@code move_idx} ascending. {@link CascadeType#ALL} plus {@code
   * orphanRemoval = true} means saving the game saves its moves, and removing a move from this list
   * deletes the corresponding row. {@code mappedBy = "game"} declares {@link MoveEntity} as the
   * owning side of the relationship.
   */
  @OneToMany(
      mappedBy = "game",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("moveIdx ASC")
  private List<MoveEntity> moves = new ArrayList<>();

  /** Required by JPA. Not part of the public API of this class. */
  protected GameEntity() {}

  GameEntity(
      UUID id,
      String roomId,
      UUID whitePlayerId,
      String whiteDisplayName,
      UUID blackPlayerId,
      String blackDisplayName,
      String startingFen,
      String finalFen,
      GameStatus status,
      Instant endedAt) {
    this.id = id;
    this.roomId = roomId;
    this.whitePlayerId = whitePlayerId;
    this.whiteDisplayName = whiteDisplayName;
    this.blackPlayerId = blackPlayerId;
    this.blackDisplayName = blackDisplayName;
    this.startingFen = startingFen;
    this.finalFen = finalFen;
    this.status = status;
    this.endedAt = endedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getRoomId() {
    return roomId;
  }

  public UUID getWhitePlayerId() {
    return whitePlayerId;
  }

  public String getWhiteDisplayName() {
    return whiteDisplayName;
  }

  public UUID getBlackPlayerId() {
    return blackPlayerId;
  }

  public String getBlackDisplayName() {
    return blackDisplayName;
  }

  public String getStartingFen() {
    return startingFen;
  }

  public String getFinalFen() {
    return finalFen;
  }

  public GameStatus getStatus() {
    return status;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public List<MoveEntity> getMoves() {
    return Collections.unmodifiableList(moves);
  }

  void setId(UUID id) {
    this.id = id;
  }

  void setRoomId(String roomId) {
    this.roomId = roomId;
  }

  void setWhitePlayerId(UUID whitePlayerId) {
    this.whitePlayerId = whitePlayerId;
  }

  void setWhiteDisplayName(String whiteDisplayName) {
    this.whiteDisplayName = whiteDisplayName;
  }

  void setBlackPlayerId(UUID blackPlayerId) {
    this.blackPlayerId = blackPlayerId;
  }

  void setBlackDisplayName(String blackDisplayName) {
    this.blackDisplayName = blackDisplayName;
  }

  void setStartingFen(String startingFen) {
    this.startingFen = startingFen;
  }

  void setFinalFen(String finalFen) {
    this.finalFen = finalFen;
  }

  void setStatus(GameStatus status) {
    this.status = status;
  }

  void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  /**
   * Replaces the move list with a fresh {@link ArrayList} copy of {@code moves}. Replacing the list
   * reference (rather than {@code clear() + addAll()}) on a {@code orphanRemoval = true} collection
   * is fine on a transient entity; for already-persistent entities Hibernate prefers {@code clear()
   * + addAll()} on the existing instance so it can track the diff, but the archive path always
   * saves a fresh entity, so the simpler shape is correct here.
   */
  void setMoves(List<MoveEntity> moves) {
    this.moves = new ArrayList<>(moves);
  }
}
