package io.github.dariogguillen.chess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.bhlangonijr.chesslib.Board;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Square;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link ChessRules}.
 *
 * <p>Each test names the starting FEN explicitly with a short comment describing why that position
 * is interesting. Where positions are constructed (rather than reached by play), the FEN was chosen
 * to be the smallest setup that exhibits the rule under test — empty boards with two kings and just
 * the pieces needed for the rule.
 */
class ChessRulesTest {

  // Standard starting position. Same value as chesslib's
  // Constants.startStandardFENPosition, but
  // we do not import chesslib here for that constant — the test stays on the
  // domain side of the
  // anti-corruption boundary, just like a production caller would.
  private static final String START_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  private final ChessRules rules = new ChessRules();

  @Test
  void legalOpeningMove_returnsOngoingStatus() {
    // 1. e2-e4 from the standard starting position: legal, no check, no game-ending
    // status.
    GameState state = rules.initialState(START_FEN);
    MoveOutcome outcome = rules.applyMove(state, move("e2", "e4"));

    assertThat(outcome.legal()).isTrue();
    assertThat(outcome.state().currentStatus()).isEqualTo(GameStatus.ONGOING);
    assertThat(outcome.state().currentFen()).isNotEqualTo(state.currentFen());
    // Sanity: the new FEN has Black to move and an en-passant target on e3.
    assertThat(outcome.state().currentFen())
        .startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3");
    // History grew by exactly the move we played.
    assertThat(outcome.state().history()).containsExactly(move("e2", "e4"));
  }

  @Test
  void illegalMove_returnsInputStateUnchanged() {
    // e2-e5 from the starting position: pawns cannot jump three squares. The state
    // must come back
    // unchanged (same currentFen, same history, ONGOING).
    GameState state = rules.initialState(START_FEN);
    MoveOutcome outcome = rules.applyMove(state, move("e2", "e5"));

    assertThat(outcome.legal()).isFalse();
    assertThat(outcome.state()).isSameAs(state);
    assertThat(outcome.state().currentFen()).isEqualTo(state.currentFen());
    assertThat(outcome.state().currentStatus()).isEqualTo(GameStatus.ONGOING);
    assertThat(outcome.state().history()).isEmpty();
  }

  @Test
  void legalMoveThatGivesCheck_returnsCheckStatus() {
    // Constructed position: White king e1, White rook h1, Black king e8, otherwise
    // empty board.
    // White plays Rh1-h8: the rook reaches rank 8, attacks the Black king on e8
    // along the rank.
    // The Black king has escape squares (d7, e7, f7), so it is check but not
    // checkmate.
    String fen = "4k3/8/8/8/8/8/8/4K2R w K - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("h1", "h8"));

    assertThat(outcome.legal()).isTrue();
    assertThat(outcome.state().currentStatus()).isEqualTo(GameStatus.CHECK);
  }

  @Test
  void foolsMate_returnsCheckmateStatus() {
    // Fool's mate, the shortest possible checkmate. After 1. f3 e5 2. g4 the FEN
    // below is what
    // Black is about to move from. Black plays Qd8-h4#: the queen reaches h4 and
    // attacks the
    // White king on e1 along the diagonal h4-g3-f2-e1. White's pawns on f3 and g4
    // vacated f2 and
    // blocked every interposition; the king has no legal escape and the queen
    // cannot be
    // captured.
    String fen = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("d8", "h4"));

    assertThat(outcome.legal()).isTrue();
    assertThat(outcome.state().currentStatus()).isEqualTo(GameStatus.CHECKMATE);
  }

  @Test
  void stalemate_returnsStalemateStatus() {
    // Constructed stalemate: Black king on a8 is the only Black piece; White queen
    // on b6, White
    // king on h1. White plays Qb6-c7. The queen on c7 attacks a7, b7, and b8 (one
    // diagonal step
    // NW from c7). The Black king on a8 has only those three squares available, all
    // are now
    // attacked, and a8 itself is not on rank 7, file c, or any diagonal from c7. So
    // the Black
    // king is not in check and has no legal move: stalemate.
    String fen = "k7/8/1Q6/8/8/8/8/7K w - - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("b6", "c7"));

    assertThat(outcome.legal()).isTrue();
    assertThat(outcome.state().currentStatus()).isEqualTo(GameStatus.STALEMATE);
  }

  @Test
  void castlingKingside_isLegalAndReflectedInFen() {
    // Kings and rooks on their starting squares, all castling rights, nothing in
    // between.
    // White plays e1-g1: kingside castle. The king ends on g1 and the rook moves
    // from h1 to f1.
    String fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("e1", "g1"));

    assertThat(outcome.legal()).isTrue();
    // First rank after the castle: R, three empty, R, K, one empty (the king ends
    // on g1).
    assertThat(outcome.state().currentFen()).startsWith("r3k2r/8/8/8/8/8/8/R4RK1");
    // After castling, White loses both castling rights and Black keeps theirs.
    assertThat(outcome.state().currentFen()).contains(" b kq ");
  }

  @Test
  void castlingQueenside_isLegalAndReflectedInFen() {
    // Same setup as above; White plays e1-c1, the queenside castle. King ends on
    // c1, rook from
    // a1 ends on d1.
    String fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("e1", "c1"));

    assertThat(outcome.legal()).isTrue();
    // First rank after the queenside castle: two empty, king, rook, three empty,
    // rook.
    assertThat(outcome.state().currentFen()).startsWith("r3k2r/8/8/8/8/8/8/2KR3R");
    assertThat(outcome.state().currentFen()).contains(" b kq ");
  }

  @Test
  void enPassant_capturesOpposingPawn() {
    // Position after 1. e4 d6 2. e5 f5. The black f-pawn just moved two squares,
    // landing next to
    // the white e5 pawn. White's en-passant target is f6 (recorded in the FEN).
    // White plays
    // e5-f6: the e-pawn moves diagonally to f6 and the black f5 pawn is captured.
    String fen = "rnbqkbnr/ppp1p1pp/3p4/4Pp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("e5", "f6"));

    assertThat(outcome.legal()).isTrue();
    // The pawn that was on f5 is gone in the resulting position; the captured
    // square is empty
    // and the capturing pawn now sits on f6. This is the part of en passant that
    // distinguishes
    // it from a normal capture (which would leave the captured piece on f5 — but
    // here f5 was the
    // origin square, not the destination).
    assertThat(outcome.state().currentFen())
        .startsWith("rnbqkbnr/ppp1p1pp/3p1P2/8/8/8/PPPP1PPP/RNBQKBNR");
  }

  @Test
  void promotionToQueen_returnsFenWithQueen() {
    // White pawn one move away from promotion; kings far apart so there is no
    // incidental check.
    // White plays a7-a8 promoting to queen.
    String fen = "8/P7/8/8/8/8/8/k6K w - - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("a7", "a8", Piece.QUEEN));

    assertThat(outcome.legal()).isTrue();
    // The first rank of the FEN (which represents rank 8) must start with Q.
    assertThat(outcome.state().currentFen()).startsWith("Q7/");
  }

  @ParameterizedTest(name = "underpromotion to {0} writes \"{1}\" on a8")
  @CsvSource({"KNIGHT, N", "BISHOP, B", "ROOK,   R"})
  void underpromotion_returnsFenWithExpectedPiece(Piece target, String expectedFenSymbol) {
    // Same setup as promotionToQueen. The parameter list intentionally excludes
    // QUEEN so the
    // dedicated promotionToQueen_* test still acts as the documenting example for
    // the canonical
    // case, and so a regression where the service silently promotes to queen
    // regardless of input
    // surfaces here.
    String fen = "8/P7/8/8/8/8/8/k6K w - - 0 1";

    GameState state = rules.initialState(fen);
    MoveOutcome outcome = rules.applyMove(state, move("a7", "a8", target));

    assertThat(outcome.legal()).isTrue();
    assertThat(outcome.state().currentFen()).startsWith(expectedFenSymbol + "7/");
  }

  @Test
  void invalidFen_throwsIllegalArgumentException() {
    // Gibberish that chesslib cannot parse. Under the new API, an unparseable
    // starting FEN is a
    // programmer error and surfaces as an IllegalArgumentException from
    // initialState — distinct
    // from a runtime illegal move, which still returns a legal=false MoveOutcome.
    assertThatThrownBy(() -> rules.initialState("this is not a fen"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("this is not a fen");
  }

  @Test
  void threefoldRepetition_returnsDrawStatus() {
    // Canary for history preservation. The threefold-repetition rule consults
    // chesslib's internal
    // position-history hash; if ChessRules ever reverts to "load a single FEN and
    // decide", that
    // history will be empty and this test will fail. Twelve half-moves total — Nf3
    // Nc6 Ng1 Nb8
    // played three times — produce three occurrences of the starting position and
    // the draw flag
    // flips on chesslib's `isDraw()` after the 12th move.
    GameState state = rules.initialState(START_FEN);
    Move[] cycle = {
      move("g1", "f3"),
      move("b8", "c6"),
      move("f3", "g1"),
      move("c6", "b8"),
      move("g1", "f3"),
      move("b8", "c6"),
      move("f3", "g1"),
      move("c6", "b8"),
      move("g1", "f3"),
      move("b8", "c6"),
      move("f3", "g1"),
      move("c6", "b8"),
    };

    MoveOutcome last = null;
    for (Move m : cycle) {
      last = rules.applyMove(state, m);
      assertThat(last.legal())
          .as("each move in the repetition cycle must be legal: %s", m)
          .isTrue();
      state = last.state();
    }

    assertThat(last).isNotNull();
    assertThat(last.state().currentStatus()).isEqualTo(GameStatus.DRAW);
    assertThat(last.state().history()).hasSize(cycle.length);
  }

  @Test
  void replayingHistoryProducesExpectedFen() {
    // Sanity check on the replay loop. Play four moves from the standard start
    // through
    // ChessRules; play the same four moves directly on a chesslib Board; the two
    // FENs must
    // match. Catches off-by-one bugs in the history-replay loop.
    GameState state = rules.initialState(START_FEN);
    state = applyAndUnwrap(state, move("e2", "e4"));
    state = applyAndUnwrap(state, move("e7", "e5"));
    state = applyAndUnwrap(state, move("g1", "f3"));
    state = applyAndUnwrap(state, move("b8", "c6"));

    Board reference = new Board();
    reference.loadFromFen(START_FEN);
    reference.doMove(
        new com.github.bhlangonijr.chesslib.move.Move("e2e4", reference.getSideToMove()));
    reference.doMove(
        new com.github.bhlangonijr.chesslib.move.Move("e7e5", reference.getSideToMove()));
    reference.doMove(
        new com.github.bhlangonijr.chesslib.move.Move("g1f3", reference.getSideToMove()));
    reference.doMove(
        new com.github.bhlangonijr.chesslib.move.Move("b8c6", reference.getSideToMove()));

    assertThat(state.currentFen()).isEqualTo(reference.getFen());
  }

  // --- helpers
  // -----------------------------------------------------------------------------------

  private GameState applyAndUnwrap(GameState state, Move move) {
    MoveOutcome outcome = rules.applyMove(state, move);
    assertThat(outcome.legal()).as("move %s must be legal in this test", move).isTrue();
    return outcome.state();
  }

  private static Move move(String from, String to) {
    return new Move(new Square(from), new Square(to), Optional.empty());
  }

  private static Move move(String from, String to, Piece promotion) {
    return new Move(new Square(from), new Square(to), Optional.of(promotion));
  }
}
