package io.github.dariogguillen.chess.web.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.Game;
import io.github.dariogguillen.chess.domain.GameResult;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Piece;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.persistence.GameEntity;
import io.github.dariogguillen.chess.persistence.GameHistoryRepository;
import io.github.dariogguillen.chess.persistence.UserRepository;
import io.github.dariogguillen.chess.service.GameHistoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@code GET /api/me/games}. Boots the full Spring context with
 * Testcontainers Postgres + Redis so the JWT filter chain, the JPA archive, and the page-shaped
 * controller projection all participate end-to-end.
 *
 * <p>Each case wipes the {@code users} and {@code games} tables in {@link #cleanState()} so they
 * are independent. The seven cases mirror the verification list in {@code progress/current.md}
 * under "Feature 19 — Verification".
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyGamesIT {

  private static final String STARTING_FEN =
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
  private static final String FINAL_FEN =
      "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private GameHistoryRepository games;
  @Autowired private GameHistoryService gameHistoryService;

  @BeforeEach
  void cleanState() {
    games.deleteAll();
    users.deleteAll();
  }

  /**
   * Wipe the {@code games} table after every case so the existing {@link
   * io.github.dariogguillen.chess.web.auth.AuthEndpointsIT} (which only wipes {@code users}) does
   * not trip on the {@code games_white_user_id_fkey} / {@code games_black_user_id_fkey} FK
   * constraints when it tries to delete users this IT linked to archived games. Mirrors the
   * cross-IT cleanup ordering: child rows ({@code games}) before parent rows ({@code users}).
   */
  @AfterEach
  void cleanUpGames() {
    games.deleteAll();
  }

  @Test
  void getMyGames_withoutAuth_returns401WithAuthenticationRequired() throws Exception {
    mockMvc
        .perform(get("/api/me/games"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", equalTo("AUTHENTICATION_REQUIRED")))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getMyGames_emptyHistory_returns200WithEmptyPage() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(get("/api/me/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(0)))
        .andExpect(jsonPath("$.totalElements", equalTo(0)))
        .andExpect(jsonPath("$.totalPages", equalTo(0)))
        .andExpect(jsonPath("$.number", equalTo(0)))
        .andExpect(jsonPath("$.size", equalTo(20)));
  }

  @Test
  void getMyGames_userHasArchivedGames_returnsOnlyOwn() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");
    RegisteredUser bob = register("bob@example.com", "supersecret", "Bob");

    // Alice plays two terminal games (one as white, one as black). Bob plays one against a guest.
    Game aliceWhiteGame =
        newTerminalGame(GameStatus.CHECKMATE, alice.id(), "Alice", null, "Guest")
            .withResult(GameResult.WHITE_WIN);
    gameHistoryService.archive(aliceWhiteGame);
    Thread.sleep(10);
    Game aliceBlackGame =
        newTerminalGame(GameStatus.STALEMATE, null, "Guest", alice.id(), "Alice")
            .withResult(GameResult.DRAW);
    gameHistoryService.archive(aliceBlackGame);
    Thread.sleep(10);
    Game bobGame = newTerminalGame(GameStatus.CHECKMATE, bob.id(), "Bob", null, "Guest");
    gameHistoryService.archive(bobGame);

    MvcResult result =
        mockMvc
            .perform(
                get("/api/me/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.totalElements", equalTo(2)))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    JsonNode content = body.get("content");
    // Newest first: aliceBlackGame archived after aliceWhiteGame.
    assertThat(content.get(0).get("gameId").asText()).isEqualTo(aliceBlackGame.id().toString());
    assertThat(content.get(0).get("selfSide").asText()).isEqualTo("BLACK");
    assertThat(content.get(0).get("opponentDisplayName").asText()).isEqualTo("Guest");
    assertThat(content.get(0).get("status").asText()).isEqualTo("STALEMATE");
    // The result (feature 23.92) rides on the wire: the stalemate is a DRAW.
    assertThat(content.get(0).get("result").asText()).isEqualTo("DRAW");
    assertThat(content.get(1).get("gameId").asText()).isEqualTo(aliceWhiteGame.id().toString());
    assertThat(content.get(1).get("selfSide").asText()).isEqualTo("WHITE");
    assertThat(content.get(1).get("opponentDisplayName").asText()).isEqualTo("Guest");
    assertThat(content.get(1).get("result").asText()).isEqualTo("WHITE_WIN");

    // Bob's game must not appear in Alice's list.
    String resp = result.getResponse().getContentAsString();
    assertThat(resp).doesNotContain(bobGame.id().toString());
  }

  @Test
  void getMyGames_anonymousArchivedGames_notVisible() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Anonymous game (both sides null) — pre-feature-19 shape.
    Game anonGame = newTerminalGame(GameStatus.CHECKMATE, null, "Guest1", null, "Guest2");
    gameHistoryService.archive(anonGame);

    mockMvc
        .perform(get("/api/me/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(0)))
        .andExpect(jsonPath("$.totalElements", equalTo(0)));
  }

  @Test
  void getMyGames_pagination_respectsPageAndSize() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    for (int i = 0; i < 5; i++) {
      Game game = newTerminalGame(GameStatus.CHECKMATE, alice.id(), "Alice", null, "Guest-" + i);
      gameHistoryService.archive(game);
      Thread.sleep(5);
    }

    mockMvc
        .perform(
            get("/api/me/games?page=0&size=2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(2)))
        .andExpect(jsonPath("$.totalElements", equalTo(5)))
        .andExpect(jsonPath("$.totalPages", equalTo(3)))
        .andExpect(jsonPath("$.number", equalTo(0)))
        .andExpect(jsonPath("$.size", equalTo(2)));

    mockMvc
        .perform(
            get("/api/me/games?page=2&size=2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.totalElements", equalTo(5)))
        .andExpect(jsonPath("$.number", equalTo(2)));
  }

  @Test
  void getMyGames_invalidPagination_returns400() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    mockMvc
        .perform(
            get("/api/me/games?size=101")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));

    mockMvc
        .perform(
            get("/api/me/games?page=-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", equalTo("VALIDATION_FAILED")));
  }

  @Test
  void getMyGames_authenticatedGameCreation_populatesUserIdColumns() throws Exception {
    RegisteredUser alice = register("alice@example.com", "supersecret", "Alice");

    // Create room as authenticated Alice; she becomes white.
    MvcResult createResult =
        mockMvc
            .perform(
                post("/api/rooms")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Alice\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
    String roomId = createBody.get("roomId").asText();
    String joinToken = createBody.get("joinToken").asText();
    UUID whitePlayerId = UUID.fromString(createBody.get("playerId").asText());

    // Anonymous guest joins as black, supplying the join token Alice shared.
    MvcResult joinResult =
        mockMvc
            .perform(
                post("/api/rooms/{id}/join", roomId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Guest\",\"joinToken\":\"" + joinToken + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode joinBody = objectMapper.readTree(joinResult.getResponse().getContentAsString());
    UUID gameId = UUID.fromString(joinBody.get("gameId").asText());
    UUID blackPlayerId = UUID.fromString(joinBody.get("playerId").asText());

    // Drive Fool's Mate to checkmate so the game terminates and is archived.
    applyMove(gameId, whitePlayerId, "f2", "f3");
    applyMove(gameId, blackPlayerId, "e7", "e5");
    applyMove(gameId, whitePlayerId, "g2", "g4");
    applyMove(gameId, blackPlayerId, "d8", "h4");

    GameEntity archived = games.findById(gameId).orElseThrow();
    assertThat(archived.getStatus()).isEqualTo(GameStatus.CHECKMATE);
    assertThat(archived.getWhiteUserId())
        .as("white_user_id must be Alice's id (authenticated room-create path)")
        .isEqualTo(alice.id());
    assertThat(archived.getBlackUserId())
        .as("black_user_id must be null (anonymous join path)")
        .isNull();

    // Cross-check via the read surface: Alice sees the game.
    mockMvc
        .perform(get("/api/me/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", equalTo(1)))
        .andExpect(jsonPath("$.content[0].gameId", equalTo(gameId.toString())))
        .andExpect(jsonPath("$.content[0].selfSide", equalTo("WHITE")))
        .andExpect(jsonPath("$.content[0].opponentDisplayName", equalTo("Guest")));
  }

  private void applyMove(UUID gameId, UUID playerId, String from, String to) throws Exception {
    mockMvc
        .perform(
            post("/api/games/{id}/moves", gameId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Player-Id", playerId.toString())
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isOk());
  }

  private RegisteredUser register(String email, String password, String displayName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        String.format(
                            "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                            email, password, displayName)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return new RegisteredUser(
        UUID.fromString(body.get("user").get("id").asText()), body.get("token").asText());
  }

  private static Game newTerminalGame(
      GameStatus status, UUID whiteUserId, String whiteName, UUID blackUserId, String blackName) {
    Player white = new Player(UUID.randomUUID(), whiteName, whiteUserId);
    Player black = new Player(UUID.randomUUID(), blackName, blackUserId);
    Move move = new Move(new Square("e2"), new Square("e4"), Optional.<Piece>empty());
    return new Game(
        UUID.randomUUID(), "ROOM01", white, black, STARTING_FEN, FINAL_FEN, status, List.of(move));
  }

  private record RegisteredUser(UUID id, String token) {}
}
