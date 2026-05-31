package io.github.dariogguillen.chess.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.dariogguillen.chess.TestcontainersConfiguration;
import io.github.dariogguillen.chess.domain.GameStatus;
import io.github.dariogguillen.chess.domain.Move;
import io.github.dariogguillen.chess.domain.Player;
import io.github.dariogguillen.chess.domain.Side;
import io.github.dariogguillen.chess.domain.Square;
import io.github.dariogguillen.chess.service.GameStore;
import io.github.dariogguillen.chess.service.bot.BotEngine;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * End-to-end integration test for the vs-bot game flow (feature 23, `bot-opponent`) with a mocked
 * {@link BotEngine} — so {@code ./init.sh} stays green without Stockfish installed. The engine is
 * scripted by call order (it replays its prearranged moves regardless of the FEN it receives),
 * which keeps the test FEN-agnostic; the human side is driven through the real REST + STOMP
 * surface.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>A {@code opponentKind=BOT} create returns a non-null {@code gameId} immediately with the
 *       bot on the right side.
 *   <li>A human move triggers a bot reply that arrives as an ordinary {@link MoveEvent},
 *       indistinguishable on the wire from a two-human move.
 *   <li>A short scripted forced-mate sequence (the bot blunders into Fool's Mate) terminates the
 *       game with {@code CHECKMATE}.
 *   <li>A bot-as-white room opens with the bot's first move arriving unprompted.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BotGameIT {

  private static final long RECEIVE_TIMEOUT_MS = 4_000L;
  private static final long SUBSCRIBE_REGISTRATION_DELAY_MS = 1_000L;
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(6);

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GameStore gameStore;

  @MockitoBean private BotEngine botEngine;

  private WebSocketStompClient stompClient;
  private RestTemplate restTemplate;

  @BeforeEach
  void setUp() {
    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    ObjectMapper clientMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    converter.setObjectMapper(clientMapper);
    stompClient.setMessageConverter(converter);
    restTemplate = new RestTemplateBuilder().build();
  }

  @AfterEach
  void tearDown() {
    if (stompClient != null) {
      stompClient.stop();
    }
  }

  @Test
  void createBotRoom_humanWhite_returnsGameIdImmediatelyWithBotOnBlack() throws Exception {
    JsonNode create = createBotRoom("Alice", "WHITE");

    String gameId = create.get("gameId").asText();
    assertThat(gameId).isNotBlank();
    assertThat(create.get("role").asText()).isEqualTo("WHITE");
    assertThat(create.get("joinToken").isNull()).isTrue();

    var game = gameStore.findById(UUID.fromString(gameId)).orElseThrow();
    assertThat(game.white().isBot()).isFalse();
    assertThat(game.black().isBot()).isTrue();
    assertThat(game.black().id()).isEqualTo(Player.BOT_PLAYER_ID);
  }

  @Test
  void humanMove_triggersBotReply_asOrdinaryMoveEvent() throws Exception {
    // Bot is black; it replies e7e5 to the human's e2e4.
    when(botEngine.chooseMove(anyString(), anyInt())).thenReturn(move("e7", "e5"));
    JsonNode create = createBotRoom("Alice", "WHITE");
    UUID gameId = UUID.fromString(create.get("gameId").asText());
    UUID humanId = UUID.fromString(create.get("playerId").asText());

    StompSession session = connect();
    BlockingQueue<MoveEvent> queue = subscribe(session, gameId);

    applyMove(gameId, humanId, "e2", "e4");

    // First event: the human's move. Second event: the bot's reply.
    MoveEvent human = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    MoveEvent bot = queue.poll(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertThat(human).isNotNull();
    assertThat(human.from()).isEqualTo("e2");
    assertThat(bot).isNotNull();
    assertThat(bot.movedBy()).isEqualTo(Player.BOT_PLAYER_ID);
    assertThat(bot.side()).isEqualTo(Side.BLACK);
    assertThat(bot.from()).isEqualTo("e7");
    assertThat(bot.to()).isEqualTo("e5");
    assertThat(bot.moveNumber()).isEqualTo(2);
  }

  @Test
  void botAsWhite_opensWithFirstMove_thenForcedMateTerminates() throws Exception {
    // The bot (white) blunders into Fool's Mate: 1.f3 .. 2.g4. The human (black) plays e5 then
    // Qh4#. The bot's opening move is triggered at room creation and runs async, so it can land
    // before a STOMP client can subscribe (no replay); we assert the opening via the authoritative
    // game store, and the wire-identical MoveEvent of a bot reply is pinned by the test above.
    when(botEngine.chooseMove(anyString(), anyInt()))
        .thenReturn(move("f2", "f3"))
        .thenReturn(move("g2", "g4"));
    JsonNode create = createBotRoom("Alice", "BLACK");
    UUID gameId = UUID.fromString(create.get("gameId").asText());
    UUID humanId = UUID.fromString(create.get("playerId").asText());

    // Bot opens unprompted (white), landing in the authoritative state.
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () -> {
              var game = gameStore.findById(gameId).orElseThrow();
              assertThat(game.moves()).hasSize(1);
              assertThat(game.moves().get(0).from().value()).isEqualTo("f2");
            });

    // Human replies e5; bot answers g4 (scripted) — back to two moves on the board.
    applyMove(gameId, humanId, "e7", "e5");
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () -> {
              var game = gameStore.findById(gameId).orElseThrow();
              assertThat(game.moves()).hasSize(3);
              assertThat(game.moves().get(2).from().value()).isEqualTo("g2");
            });

    // Human delivers Qd8-h4#; the game ends in checkmate.
    applyMove(gameId, humanId, "d8", "h4");
    await()
        .atMost(POLL_TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(gameStore.findById(gameId).orElseThrow().status())
                    .isEqualTo(GameStatus.CHECKMATE));
  }

  private JsonNode createBotRoom(String displayName, String preferredSide) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        "{\"displayName\":\""
            + displayName
            + "\",\"preferredSide\":\""
            + preferredSide
            + "\",\"opponentKind\":\"BOT\"}";
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/api/rooms",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            String.class);
    return objectMapper.readTree(response.getBody());
  }

  private StompSession connect() throws Exception {
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
        .get(RECEIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private BlockingQueue<MoveEvent> subscribe(StompSession session, UUID gameId)
      throws InterruptedException {
    BlockingQueue<MoveEvent> queue = new ArrayBlockingQueue<>(16);
    session.subscribe(
        "/topic/games/" + gameId,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return MoveEvent.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            queue.offer((MoveEvent) payload);
          }
        });
    Thread.sleep(SUBSCRIBE_REGISTRATION_DELAY_MS);
    return queue;
  }

  private void applyMove(UUID gameId, UUID playerId, String from, String to) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Player-Id", playerId.toString());
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    restTemplate.exchange(
        baseUrl() + "/api/games/" + gameId + "/moves",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private static Move move(String from, String to) {
    return new Move(new Square(from), new Square(to), Optional.empty());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }
}
