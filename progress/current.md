# Current session

**Status:** session closed.

Feature 6 (`websocket-realtime`) was closed on 2026-05-19. The feature
added Spring WebSocket + STOMP, broadcasting a `MoveEvent` to
`/topic/games/{gameId}` after every successful move via REST. The
canonical STOMP contract lives in `docs/architecture.md` → "STOMP API
contract" and will be mirrored by `chess-frontend` when it reaches its
own feature 5 (`stomp-live-updates`). Manual end-to-end testing with a
STOMP client was intentionally deferred to that frontend feature, where
the real `@stomp/stompjs` client and real CORS handshake will exercise
the contract in a browser. See `progress/history.md` for the full close
entry.

The next feature in `feature_list.json` is `spectator-mode`
(priority 6.5). It builds directly on `websocket-realtime`: non-player
participants subscribe to the game's STOMP topic and receive live
state updates, and the server tracks a live viewer count per game and
publishes it so the UI can render it. The user explicitly requested
the viewer count be visible — see the project memory and the feature's
acceptance criteria. The leader will open a plan here once the scope
and key decisions for `spectator-mode` are aligned with the user.
