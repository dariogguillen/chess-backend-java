# Current session

**Status:** closed — no active feature.

Last closed feature: `disconnect-notifications` (priority 11.5) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `docker-compose` (priority 12) — likely redundant
with feature 7 (`backend-containerize`) which already shipped the
local docker-compose; planning should start by auditing what's
left or whether to mark redundant.

---

## Mid-grace UX events are live

The `/topic/games/{gameId}` STOMP topic now carries four event
types in a sealed-interface `GameStateEvent` family with an
explicit `type` discriminator:

- `MoveEvent` (`type: "MOVE"`) — every successful move.
- `GameAbandonedEvent` (`type: "GAME_ABANDONED"`) — emitted on
  grace-period timeout.
- `PlayerDisconnectedEvent` (`type: "PLAYER_DISCONNECTED"`) —
  emitted immediately on disconnect, carries the absolute
  `gracePeriodEndsAt: Instant` for the opponent's local countdown.
- `PlayerReconnectedEvent` (`type: "PLAYER_RECONNECTED"`) —
  emitted only when the reconnect actually cancels a pending
  timer (guard against the cancel-vs-fire race).

The two pre-existing events were retrofitted with the `type` field
without breaking any call site or any wire consumer (Jackson
ignores unknown fields by default). The codebase-wide rule —
"polymorphic topic gets the discriminator; single-event topic
doesn't" — is now documented at the top of the STOMP contract
section in `architecture.md`.

## Frontend cross-repo work unlocked

The frontend repo's `Play.tsx` can now:

1. Migrate from awkward shape-based discrimination
   (`if ('abandonedBy' in event)`) to `switch (event.type)`.
   Backward-compatible — ship any time.
2. Render a reconnecting banner on `PlayerDisconnectedEvent` using
   `gracePeriodEndsAt` for a local-clock countdown.
3. Clear the banner on `PlayerReconnectedEvent`.

Contract is in `docs/architecture.md`. Frontend adopts on its own
schedule.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
