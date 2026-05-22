# Current session

**Status:** closed — no active feature.

Last closed feature: `disconnect-handling` (priority 11) on 2026-05-22.
See `progress/history.md` for the full entry.

---

To start the next session, the leader picks the lowest-priority
feature with `status: "pending"` from `feature_list.json` and writes
a plan here.

Next in queue: `disconnect-notifications` (priority 11.5).

---

## Disconnect lifecycle is in place

The backend now correctly handles mid-game disconnections:

- **Detect**: `PlayerSessionTracker` listens STOMP `SessionDisconnectEvent`
  and identifies the player via the existing `playerId` header.
- **Grace**: a 60s timer (configurable via `chess.disconnect.grace-period`)
  is started per `(playerId, gameId)`.
- **Reconnect**: if the same `playerId` resubscribes to
  `/topic/games/{gameId}` within the window, the timer is cancelled
  and the game continues unchanged.
- **Abandon**: if the timer fires, the game is mutated to
  `ABANDONED` in Redis, archived to Postgres (via the existing
  `GameHistoryService.archive` path now invoked from a second call
  site), and a terminal `GameAbandonedEvent` is broadcast on
  `/topic/games/{gameId}` so the opponent learns the outcome and
  the identity of the winner.

The race condition between `cancel` and the scheduler's `fire` was
found and closed with per-key `ReentrantLock` serialisation on
start/cancel/fire — the three operations are mutually exclusive.

Server-restart limitation: in-flight grace-period timers are lost
on restart. Documented in `architecture.md` and the note; a
polish opportunity for a future feature.

**Cross-repo coordination remaining**: the frontend's `Play.tsx`
needs to handle the new `GameAbandonedEvent` on
`/topic/games/{gameId}`, distinguishing it from `MoveEvent` by
shape (presence of `abandonedBy`). Feature 11.5 will retrofit a
`type` discriminator and the distinction becomes
`switch (event.type)`. Backend ships the contract today; frontend
adopts on its own schedule.

Live URLs (unchanged):

- `https://chess-backend.duckdns.org/api/health`
- `https://chess-backend.duckdns.org/swagger-ui/index.html`
