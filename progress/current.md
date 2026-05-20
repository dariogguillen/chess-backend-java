# Current session

**Status:** session closed.

Feature 6.5 (`spectator-mode`) was closed on 2026-05-19. The feature
added a `ViewerCountTracker` listening to Spring's STOMP session
events, broadcasting `ViewerCountEvent` to
`/topic/games/{gameId}/viewers` on every viewer count change. Player
exclusion uses a trust-on-self-declaration `playerId` STOMP header on
SUBSCRIBE. The cross-repo contract documentation extended (not
rewrote) the "STOMP API contract" section of `docs/architecture.md`,
which `chess-frontend`'s feature 5 (`stomp-live-updates`) will mirror
when it lands. Manual end-to-end testing deferred to that frontend
feature. See `progress/history.md` for the full close entry.

The next feature in `feature_list.json` is `redis-active-state`
(priority 7). It moves the in-memory state of active rooms and games
to Redis with a TTL (e.g. 24h) so abandoned rooms self-clean. This is
the first feature that introduces the `cache/` package as a real
inhabitant (it was named in `docs/conventions.md` from day one and
the `service/` interface seam was deliberately designed for this
swap — see `RoomStore`/`GameStore` interfaces vs `InMemoryRoomStore`/
`InMemoryGameStore` impls from features 4 and 5). The leader will
open a plan here once the scope and key decisions for
`redis-active-state` are aligned with the user.
