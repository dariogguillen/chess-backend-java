# Current session

**Status:** ACTIVE — feature `direct-invitations` (priority 23.9) in_progress.
Plan locked below, awaiting user approval before dispatching the implementer.

## Feature 23.9 `direct-invitations` — plan (2026-06-24)

Second feature of the social pair (friends → invitations). Invite an accepted
friend to a room you created; real-time delivery via per-user STOMP push.
Product decisions taken with the user (2026-06-24):

1. **Modelo:** invitar a una **sala ya creada** (el invite entrega el joinToken
   de 22.7 de forma autorizada; no es un "challenge" que crea la sala al aceptar).
2. **Entrega:** **push STOMP por-usuario** (`/user/queue/invitations`) **+ lista
   REST** (`GET /api/me/invitations`) para el invitado offline.
3. **Persistencia:** **efímera en Redis**, atada a la room (TTL 24h, sin migración).

### Leader architecture decisions (within the locked scope)
- **accept = join server-side**: el endpoint de accept ejecuta el join usando el
  `joinToken` leído de la Room en el server → el token **nunca viaja al cliente**
  del invitado. Reusa `RoomService.joinRoom`, así que el invitador se entera por
  el **`RoomJoinedEvent` ya existente** (9.5) — NO se emite un evento nuevo para
  accept (no duplicar señal).
- **Identidad de la invitación = roomId** desde la óptica del invitado (a lo sumo
  una invitación por (room, invitee)); accept/decline se keyean por `{roomId}`.
- **Sin SCAN**: índice por-invitee en Redis (hash `invitations:user:{inviteeId}`,
  campo=roomId) para listar; liveness re-validada contra el RoomStore al
  leer/aceptar (room existe, WAITING_FOR_PLAYER, slot libre, tiene joinToken);
  entradas stale se podan lazy. TTL del hash = active-state TTL (refresh al escribir).
- **Reuso de errores**: gate de amistad → `FriendNotFoundException` (404, ya
  existe); room llena/bot/legacy → `RoomFullException` (409, ya existe);
  duplicado mismo (room,invitee) → idempotente (overwrite, sin error).
- **Push solo a sesiones autenticadas**: `convertAndSendToUser` resuelve por el
  `StompPrincipal.getName()` = userId (feature 20). Sesión anónima no recibe push
  (ok — invitations requieren cuenta).

### REST surface (todo bajo `/api/me`, Bearer JWT, patrón `@AuthenticationPrincipal User`)
- `POST   /api/me/invitations` body `{ roomId, friendUserId }` → 201; 404 friend/room, 403 non-member, 409 full.
- `GET    /api/me/invitations` → invitaciones entrantes vivas (roomId, inviter, timeControl, lado, createdAt).
- `POST   /api/me/invitations/{roomId}/accept` → join server-side; devuelve RoomResponse; 404 invite, 409 full.
- `DELETE /api/me/invitations/{roomId}` → decline (invitee); push InvitationDeclinedEvent al inviter; 204.
- `DELETE /api/me/invitations/{roomId}/to/{inviteeUserId}` → cancel (inviter); push InvitationCancelledEvent al invitee; 204.

### STOMP (NUEVO destino por-usuario)
- `WebSocketConfig`: `enableSimpleBroker("/topic", "/queue")` + `setUserDestinationPrefix("/user")`.
- Familia sellada `InvitationEvent` (discriminador `type()`): `InvitationReceivedEvent`
  (→ invitee al enviar), `InvitationDeclinedEvent` (→ inviter), `InvitationCancelledEvent` (→ invitee).
- Accept NO emite evento nuevo: el inviter recibe `RoomJoinedEvent` en `/topic/rooms/{roomId}`.

### Files to create
- `cache/InvitationStore.java` (iface) + `cache/RedisInvitationStore.java`
- `domain/Invitation.java` (record: roomId, inviterUserId, inviterDisplayName, createdAt)
- `service/InvitationService.java`
- `web/me/InvitationController.java` + DTOs (`SendInvitationRequest`, `InvitationResponse`; accept reusa `web/room/RoomResponse`)
- `websocket/InvitationEvent.java` (sealed) + `InvitationReceivedEvent`, `InvitationDeclinedEvent`, `InvitationCancelledEvent`
- `exception/InvitationNotFoundException.java` (404), `exception/NotRoomMemberException.java` (403)
- `notes/23.9-direct-invitations.md`; test `web/me/InvitationIT.java`

### Files to modify
- `config/WebSocketConfig.java` (/queue broker + user-destination prefix)
- `exception/ErrorResponse.java` (+2 codes → 21) y el canary `OpenApiIT`
- `docs/architecture.md` (Invitations + STOMP user-destination + API contract), `README.md`
- Posible: exponer en `RoomService` un helper de membership/lookup si `joinRoom` no alcanza tal cual.

### Verification
`./init.sh` — `InvitationIT` cubre: send happy + 404 non-friend + 404 room + 403
non-member + 409 full/bot; GET lista solo vivas (poda stale); accept hace el join
(2do player + gameId) y el inviter recibe `RoomJoinedEvent`; accept tras llenarse
→ 409; decline/cancel borran; **push por-usuario recibido por un STOMP client
autenticado** suscripto a `/user/queue/invitations` (reusa patrón de `StompAuthIT`);
sesión sin token NO recibe push; 401 sin Bearer en cada endpoint; regresión anónima.

### Concepts for the feature note
Per-user STOMP push (`convertAndSendToUser` + user-destination prefix) vs topic
broadcast; el modelo efímero-Redis-atado-a-la-room vs tabla durable; reusar
`RoomJoinedEvent` en vez de emitir uno redundante; paralelos Scala (fs2 Topic
keyed by user / Queue[F] por subscriber).

### Cross-repo
Nueva superficie REST + NUEVO user-destination STOMP (`/user/queue/invitations`,
requiere CONNECT autenticado). El frontend construye la UI de invitaciones y se
suscribe a la user-queue. Documentado en architecture.md. Sin cambios a contratos
existentes (aditivo).

### Next step
Esperar OK del user → dispatch `implementer` → `./init.sh` → `reviewer` → user OK
→ `done` + nota + history.

---

## Project state

- **39 done, 0 in_progress, 1 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending feature

- **24 `random-matchmaking`** — Redis-backed matchmaking queue. **Deferred by
  the user** behind the social pair (friends → invitations). Stays `pending`,
  not next in intent.

---

## Just-closed feature — uncommitted, flag for `git add`

`friends-list` (23.8) closed with the working tree **uncommitted** (the user
batches/handles commits). 22 new untracked files + modifications to tracked
files. Full file list in the `history.md` 23.8 entry. Notable new dirs/files
to not miss when staging: `db/migration/V3__add_friend_code_and_friendships.sql`,
the six `exception/Friend*Exception.java`, `domain/Friendship{,Status}.java`,
`service/{FriendCodeGenerator,FriendshipService}.java`,
`persistence/Friendship*`/`Friend*Summary.java`, `web/me/Friendship*` + DTOs,
and tests `web/me/FriendshipIT.java`, `service/FriendCodeGeneratorTest.java`.
Prior commit on `main`: `3daea6f feat: sync docker compose file to EC2 on deploy`.

---

## Roadmap (user re-prioritisation, 2026-05-30; friends now done)

bot work (done) → **friends list (done, 23.8)** → **direct invitations** (next;
layered on the accepted-friends set + `room-access-tokens` 22.7) →
**random-matchmaking (24) deferred** until after those. Direct invitations is
NOT yet an entry in `feature_list.json` — promote it when the user picks it up
(new entry, priority between 23.8 and 24, full harness cycle).

### Other future scope not yet promoted

- **Bot Phase 2**: MultiPV-4 + randomized weakness (full Lichess model).
- **"Section to learn" using the Lichess API** (user's idea, 2026-05-30).

---

## Carried ops state (infra in "apagar bajo demanda" mode)

Full context in `[[project-infra-on-demand-mode]]` memory and the history. The
still-live facts:

- **Mode:** EC2 + RDS stopped on demand for credits; t3.micro livelocks
  without swap. Deploy = `git push origin main` → GitHub Actions.
- **✅ Resolved 2026-06-23** (verified on the running instance, host
  `ip-172-31-42-67`): feature 26 `scp` smoke test passed (`/opt/chess`
  yml byte-identical to repo HEAD); **swap (2 GB) + `-Xmx320m` cap applied**
  (`free -h`: 693/911 MiB used but 217 MiB available, swap ~untouched). The
  OOM/livelock scenario is mitigated; the instance now stays up.
- **Reminder for next shutdown:** RDS auto-restarts after 7 days → re-stop it
  manually (or wire EventBridge+Lambda).
- **Deploying the local work:** `main` carries bot + Fairy-Stockfish +
  room-tokens + time-control + choose-side and is already deployed (the
  2026-06-22 push). The friends-list commit (when the user makes it) will
  deploy the V3 migration + the friends surface on the next push — the V3
  Flyway migration runs against prod RDS on that deploy; nothing else
  operationally special.

---

## Leader notes for the next session

- Repo is in extension mode at 39/0/1. Next feature per the user's roadmap is
  **direct invitations** (not yet an entry); `random-matchmaking` (24) stays
  deferred. New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry).
- **Direct invitations** will build directly on this feature's accepted-friends
  set: invite a friend (by their user id, from your friends list) to a room you
  created, gated by `room-access-tokens` (22.7)'s join token. Likely a new
  STOMP topic for the invitee's notification + a REST endpoint to send/accept an
  invite. Plan it fresh; coordinate the STOMP contract with the frontend.
- Per `[[feedback-user-handles-commits]]`: the user handles commits. The
  friends-list working tree is uncommitted — flagged above for `git add`.
- **History gap noticed:** feature 26 `deploy-config-sync` (done 2026-06-22) was
  recorded in the prior `current.md` and `feature_list.json` but never got its
  own `progress/history.md` entry. Low priority; backfill if a clean history
  matters for the portfolio narrative.
