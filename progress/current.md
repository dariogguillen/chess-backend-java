# Current session

**Status:** session closed.

Feature 5 (`game-rest-api`) was closed on 2026-05-19. The feature shipped
`POST /api/games/{id}/moves` and `GET /api/games/{id}`, the supporting
`GameService`, five new exceptions (including the new abstract
`UnprocessableException` → 422), the `Game` domain change adding
`startingFen`, and the `GameStatus.isTerminal()` helper. A closing
iteration applied two architectural cleanups: `MoveDto` became a
nested `MoveSummary` inside `GameStateResponse`, and the in-memory
stores moved from `service/` to `cache/` with their annotation changed
from `@Service` to `@Component`. See `progress/history.md` for the full
close entry.

The next feature in `feature_list.json` is `websocket-realtime`
(priority 6). It introduces Spring WebSocket with STOMP so that after
a move is accepted by the REST endpoint, the new state is broadcast
to all subscribers of the game's topic. This is the first feature
where the springdoc OpenAPI spec stops being the sole source of truth
for the API — STOMP surfaces are documented separately in the README.
The leader will open a plan here once the scope and key decisions for
`websocket-realtime` are aligned with the user.
