# Verification

This document defines how to prove that a feature works. The principle is
simple: **only an executable, repeatable check counts**. Reasoning,
intuition, and "it looks right" do not count.

The entry point is `./init.sh`. A passing run is the only acceptable
evidence that a feature is done.

---

## What `./init.sh` does

In order:

1. **Sanity checks.** Java 21+ is installed, the Maven wrapper exists,
   required files (`pom.xml`, `CLAUDE.md`, `AGENTS.md`,
   `feature_list.json`) are present.
2. **`feature_list.json` invariants.** At most one feature can have
   `status: "in_progress"`. If more, the script fails — this is the
   one-feature-at-a-time enforcement.
3. **Compile.** `./mvnw -q -DskipTests clean compile`.
4. **Tests and verify.** `./mvnw -q verify`. This phase runs unit and
   integration tests, and any other configured plugins (formatting,
   static analysis if added later).

If every step exits 0, the script prints "All checks passed." and
returns 0. Any non-zero exit means stop and read the output.

## Test strategy

There are two layers of tests, each with a specific purpose.

### Unit tests

- File pattern: `*Test.java`.
- Run by the `maven-surefire-plugin` during the `test` phase.
- Do not boot Spring. Use plain JUnit 5.
- Use Mockito for collaborators when needed.
- Target: pure logic and domain code (e.g., `ChessRulesTest`,
  `RoomValidatorTest`, simple service logic).

Unit tests must be fast. A unit test that takes more than 100 ms is
suspect — it is probably an integration test in disguise.

### Integration tests

- File pattern: `*IT.java`.
- Run by the `maven-failsafe-plugin` during the `verify` phase.
- Boot a Spring context (`@SpringBootTest`).
- Use **Testcontainers** for real Postgres and Redis. No H2, no
  embedded Redis.
- Use `MockMvc` for REST endpoints. Use a real STOMP client (
  `WebSocketStompClient`) for WebSocket flows.

Each `IT` is end-to-end within its slice: a controller `IT` exercises
the controller → service → repository → database path.

```java
@SpringBootTest
@Testcontainers
class RoomControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;

    @Test
    void createsARoomAndReturnsItsId() throws Exception {
        mvc.perform(post("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "displayName": "Alice" }
                    """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.id").isString());
    }
}
```

### Coverage

We do not enforce a numeric coverage target. The expectation is
qualitative: **every public service method has at least one test**, and
**every controller endpoint has at least one integration test covering
the happy path and the most likely failure case**.

## What "done" means per feature type

A feature is done when **all** of the following are true:

1. The acceptance criteria listed in `feature_list.json` for that
   feature are satisfied.
2. `./init.sh` passes.
3. New code has tests at the appropriate level (unit, integration, or
   both).
4. `progress/history.md` has an entry describing what changed.
5. The feature's status in `feature_list.json` is `done`.

### Feature type variations

**A pure domain feature** (e.g., `chesslib-integration`) is done when
unit tests cover the expected behavior, including edge cases relevant
to chess: castling, en passant, promotion, check, checkmate, stalemate,
draws.

**A REST feature** (e.g., `room-rest-api`) is done when an integration
test exercises:

- The happy path (200/201 with correct response body).
- Validation errors (400 with structured error body).
- Domain errors (404, 409, 422 with the right error code).

**A WebSocket feature** (e.g., `websocket-realtime`) is done when an
integration test connects a real STOMP client, performs the
subscribe/send/receive cycle, and asserts the broadcast.

**A persistence feature** (e.g., `postgres-game-history`) is done when:

- The Flyway migration applies cleanly on an empty database.
- An integration test inserts and reads a row through the
  repository.
- The migration is forward-only (no edits to applied migrations).

**An infra feature** (e.g., `docker-compose`) is done when:

- `docker compose up -d` succeeds locally.
- The README has a "Run locally" section showing how to use it.

## Manual smoke tests

Some things are easier to verify by hand. When the feature involves
real-time behavior, a manual smoke test is a useful supplement to the
integration tests (not a replacement):

1. Start the stack: `docker compose up -d` then `./mvnw spring-boot:run`.
2. Use two `wscat` sessions or two browser tabs.
3. Walk through the user flow.

Document the smoke test in `progress/history.md` so reviewers know it
was performed.

## Failure mode

If `./init.sh` fails:

1. Read the failure output from top to bottom.
2. Fix the root cause, not the symptom. Do not skip tests, comment
   them out, or weaken assertions.
3. Re-run `./init.sh` until it passes.
4. Only then update `feature_list.json` and `progress/`.

Never declare a feature done with a failing `init.sh`. The discipline
is the value.
