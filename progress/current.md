# Current session

**Status:** closed — no active feature.

Last closed feature: `room-lifecycle-events` (priority 9.5) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `rest-cors` (priority 10).

---

## End-to-end gameplay unblocked

The room creator now has two complementary ways to learn when their
opponent has joined:

- **REST poll**: `GET /api/rooms/{id}` returns the current room state
  including `gameId` (or `null`). 404 on unknown id.
- **STOMP push**: subscribe to `/topic/rooms/{roomId}` to receive a
  `RoomJoinedEvent { type: "ROOM_JOINED", roomId, gameId, blackPlayer }`
  the moment the second player joins.

The two ship together because they cover each other: STOMP cannot
replay missed messages, so the GET is the fallback (and the
frontend's `creator-game-discovery` flow uses it as the primary
poll).

The `/topic/rooms/{roomId}` topic is designed to host future
lifecycle events (`RoomClosedEvent`, `PlayerLeftEvent`, ...) without
contract churn — discrimination via an explicit `type` JSON field,
sealed-interface-based hierarchy for compile-time exhaustiveness.

The frontend repo (`chess-game`) had its features 4 and 5 closed
before this; its pending `creator-game-discovery` feature can now
be planned and consumes either or both of the new mechanisms.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
- `https://chess-backend.duckdns.org/api/rooms/{id}` (new)
