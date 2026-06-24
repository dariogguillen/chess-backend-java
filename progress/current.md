# Current session

**Status:** ACTIVE — feature `friends-list` (priority 23.8) in_progress.
Plan locked below, awaiting user approval before dispatching the implementer.

## Feature 23.8 `friends-list` — plan (2026-06-23)

First feature of the social pair (friends → direct invitations). Mutual
request/accept friendships, discovered by a **shareable friend code**.
Product decisions taken with the user (2026-06-23):

1. **Modelo:** request/accept mutuo (estados PENDING → ACCEPTED).
2. **Descubrimiento:** friend code compartible (sin enumeración del padrón).
3. **Alcance:** ciclo completo (send / accept / reject / cancel / list
   friends / list incoming+outgoing / remove).

### Leader architecture decisions (within the locked scope)
- **friend_code**: columna `NOT NULL UNIQUE` en `users` (VARCHAR(8)),
  generada en **un solo lugar** (`FriendCodeGenerator`, espeja el generador
  de room-code de `RoomService` con retry por colisión y el alfabeto sin
  caracteres ambiguos) en los DOS paths de creación de usuario
  (`AuthService.register` + el find-or-create de OAuth). Los usuarios
  preexistentes en prod se **backfillean en la migración V3**.
- **friendships** guarda **UUIDs** (`requester_id`, `addressee_id`), SIN
  `@ManyToOne` — consistente con cómo `games` denormaliza. El `displayName`
  vivo se obtiene con **entity joins** de Hibernate en JPQL
  (`JOIN User u ON u.id = f.addresseeId`), no snapshot → un rename del amigo
  se refleja.
- **Unicidad por par desordenado**: índice UNIQUE sobre
  `(LEAST(requester_id,addressee_id), GREATEST(...))` → a lo sumo UNA
  relación por par, en cualquier dirección. Bloquea A→B y B→A simultáneos.
- **reject / cancel / remove BORRAN la fila** (no hay estado REJECTED);
  status solo PENDING|ACCEPTED. Re-solicitar después de un reject es válido.
- **Sin fuga de existencia**: aceptar/borrar una request donde el caller no
  es participante devuelve `404 FRIEND_REQUEST_NOT_FOUND` (mismo código que
  si no existiera), no 403.

### REST surface (todo bajo `/api/me`, Bearer JWT, patrón `@AuthenticationPrincipal User`)
- `GET    /api/me/friend-code` → `{ friendCode }` (aislado; NO toca MeResponse de la feature 16).
- `POST   /api/me/friends/requests` body `{ friendCode }` → 201; 404 code, 422 self, 409 already / duplicate.
- `POST   /api/me/friends/requests/{id}/accept` → addressee acepta; 404 si no es el addressee.
- `DELETE /api/me/friends/requests/{id}` → cancel (requester) o reject (addressee); 204.
- `DELETE /api/me/friends/{userId}` → remove accepted friend; 204 / 404 FRIEND_NOT_FOUND.
- `GET    /api/me/friends?page&size` → accepted, proyecta al OTRO user (id, displayName vivo, friendCode, friendsSince).
- `GET    /api/me/friends/requests/incoming?page&size` → PENDING donde soy addressee.
- `GET    /api/me/friends/requests/outgoing?page&size` → PENDING donde soy requester.

Paginación = `Page<T>` Spring Data, default 20 / max 100 / page≥0, igual que
feature 19 (`MyGamesPage`). Out-of-range → 400 VALIDATION_FAILED.

### Files to create
- `src/main/resources/db/migration/V3__add_friend_code_and_friendships.sql`
- `domain/Friendship.java` (@Entity, UUIDs, status enum), `domain/FriendshipStatus.java`
- `persistence/FriendshipRepository.java` + projection records `FriendSummary`, `FriendRequestSummary`
- `service/FriendshipService.java`, `service/FriendCodeGenerator.java`
- `web/me/FriendshipController.java` + DTOs (`SendFriendRequestRequest`, `FriendCodeResponse`, Page wrappers)
- `exception/`: `FriendCodeNotFoundException`, `FriendRequestNotFoundException`,
  `FriendNotFoundException` (404), `AlreadyFriendsException`,
  `DuplicateFriendRequestException` (409), `SelfFriendshipException` (422)
- `notes/23.8-friends-list.md`
- Tests: `FriendshipIT`, `FriendCodeGeneratorTest`

### Files to modify
- `domain/User.java` (+friend_code field/getter), `service/auth/AuthService.java`
  + el OAuth success/find-or-create (set friend code on creation)
- `exception/ErrorResponse.java` (+6 allowableValues) y el canary IT de error-codes
- `docs/architecture.md` (sección Friends + API contract), `README.md` (endpoints)

### Verification
`./init.sh` — cubierto por `FriendshipIT` (full lifecycle + 401s + pagination
+ no-leak 404 + regresión anónima), `FriendCodeGeneratorTest`, y el canary de
error-codes. Sin STOMP. Cross-repo: nueva superficie REST, aditiva; el
frontend construye la UI de amigos (documentado en architecture.md).

### Concepts for the feature note
JPQL entity joins (Hibernate) vs relaciones `@ManyToOne`; invariante de
unicidad por par desordenado a nivel DB vs chequeo en app; el enum
`FriendshipStatus` como ADT; backfill en migración + generación en runtime;
paralelos Scala/doobie (SQL join explícito) y el 404-no-leak.

### Next step
Esperar OK del user al plan → dispatch `implementer` → `./init.sh` →
`reviewer` → user OK → `done` + nota + history.

---

## Ops session 2026-06-22 — infra apagada (modo bajo demanda)

Sesión de mantenimiento (no feature). Hallazgos y acciones:

- **Producción estaba caída.** La EC2 `i-0af4adeab6d7afb02` (t3.micro, 1 GB,
  región us-east-2, EIP `18.189.228.186`) se colgó (`InstanceStatus: impaired`)
  el **2026-05-29 ~09:46 UTC**, ~2.5 h después del último deploy, y quedó zombie
  ~3 semanas. Causa más probable: **RAM al 82% en idle + 0 swap → livelock**
  (no confirmada por kernel log: el journald del boot -1 requiere root y la pass
  de sudo del user `deploy` se perdió; `ubuntu`+Instance Connect es la vía root).
  Un reboot la recuperó pero se volvió a colgar en ~20 min → fallo recurrente.
- **CORRECCIÓN a una nota previa que era FALSA:** producción NO tenía
  Fairy-Stockfish ni el bot. El deploy se dispara `on push to main`, y `main`
  local está **7 commits adelante de `origin/main`** (nunca se pusheó). Lo
  desplegado es `a5dde2d` (2026-05-29). Sin push, no hay deploy.
- **Costo:** uso bruto ~$28/mes (RDS $10.9 + EC2 $7 + IPv4 $2.55), **cubierto
  100% por créditos**. Saldo real (Billing → Credits, 2026-06-22): **~$129
  restantes** ($140 emitidos = Free Tier $100 + 2× Explore AWS $20), **todos
  vencen 20/05/2027**. A 24/7 se agotarían ~oct 2026; apagado bajo demanda
  sobran hasta el vencimiento → NO hay urgencia financiera. El modo bajo-demanda
  vale por evitar los cuelgues, no por dinero.
- **DECISIÓN: portfolio en modo "apagar bajo demanda".** Se pararon **EC2 + RDS**
  (`chess-backend-postgres`) el 2026-06-22. Piso de costo apagado ~$6/mes
  (EBS + RDS storage + EIP). Scripts start/stop entregados (RDS primero al
  encender; EIP mantiene la IP → DNS estable).

### Hotfix CI (2026-06-22) — AuthCoreIT aislamiento — ✅ RESUELTO Y DESPLEGADO
- El push de los 7 commits disparó el deploy; falló en `Build and verify` por
  `AuthCoreIT` persistiendo users sin `users.deleteAll()` en `@BeforeEach` →
  choque con `alice@/bob@example.com` (BD Testcontainers compartida). Primera
  vez que la suite completa corría junta en CI.
- Fix (implementer): `@BeforeEach { users.deleteAll(); }` en `AuthCoreIT`
  (replica `AuthEndpointsIT:48-50`). Reviewer APROBÓ, `./init.sh` verde.
- Commit `e0f247b`, re-push → deploy `27980818579` **success** (2026-06-22).
- **Producción AL DÍA**: ya no está 7 commits atrás. La imagen en vivo incluye
  bot + Fairy-Stockfish, room-access-tokens, game-time-control, choose-side, y
  arranca con el cap de JVM (`JAVA_TOOL_OPTIONS`) sobre el swap de 2 GB.
- **Follow-up NO bloqueante**: `StompAuthIT` persiste `users` sin limpiar (mismo
  patrón), pero con emails únicos (`alice-stomp@`, `*-spoof@`) que hoy no
  colisionan. Arreglarlo requiere borrado en cascada por FKs games/rooms→users
  (patrón de `MyGamesIT`). Tarea aparte si en el futuro otro IT comparte esos
  emails.

### Feature 26 `deploy-config-sync` — ✅ DONE (2026-06-22, reviewer approved + user OK)
- Disparada por el dolor de hoy: el cap de JVM hubo que ponerlo a mano en
  `/opt/chess` porque `deploy.yml` no sincroniza el compose del repo.
- Plan: añadir un `scp` del `docker-compose.prod.yml` del runner a
  `/opt/chess/` en la EC2 **antes** de `docker compose pull && up -d`, dentro
  del step "Deploy to EC2 over SSH" (reusa la `DEPLOY_SSH_KEY` ya configurada).
  El `.env` NUNCA se toca (credenciales operator-managed). Documentar en
  `docs/architecture.md` y `docs/deploy-runbook.md`.
- **Verificación:** `./init.sh` pasa (no toca app), pero el smoke test real
  (editar yml → push → confirmar en EC2) es un deploy de verdad → lo hace el
  user, o un `workflow_dispatch` tras aprobación. CUIDADO de no romper el
  deploy (acabamos de estabilizar prod).
- Cerrada: implementer → reviewer APROBÓ → user OK → `done`. `feature_list.json`
  ahora 38 done / 0 in_progress / 1 pending (solo queda 24, diferido).
- **Smoke test ✅ PASADO (2026-06-23)**: tras un deploy, el
  `cat /opt/chess/docker-compose.prod.yml` en la EC2 (host
  `ip-172-31-42-67`) salió **byte-idéntico** al `docker-compose.prod.yml`
  del repo HEAD — mismos comentarios ricos (colon-bug, auth-bundle, CORS
  NOTE), `JAVA_TOOL_OPTIONS` y los 4 env vars de auth. El `scp` sobrescribió
  el archivo que dejó el hotfix manual de la 25 → la sincronización
  repo→EC2 funciona end-to-end. Feature 26 verificada en producción.

### Pendientes de esta línea ops
- ✅ **RESUELTO (2026-06-23)**: swap + cap `-Xmx` aplicados y verificados en la
  EC2 encendida (host `ip-172-31-42-67`). `swapon --show`: `/swapfile` 2 GB,
  47 MiB usados. `free -h`: 693 MiB de 911 usados pero **217 MiB available**
  (buff/cache reclamable), swap casi sin tocar. El cap `-Xmx320m` del compose
  contiene el heap; el escenario de OOM-kill por 0-swap (RSS ~400 MiB sin
  colchón) quedó mitigado. La instancia ya aguanta encendida sin colgarse.
- **RDS se auto-enciende a los 7 días** → re-parar manual, o montar
  EventBridge+Lambda que lo re-pare.
- Si en algún momento se quiere desplegar el trabajo local (bot, room-tokens,
  game-time-control, choose-side): el user hace `git push origin main` → GHA
  despliega los 7 commits. Ojo memoria t3.micro al meter Fairy-Stockfish.

---

## Project state

- **37 done, 0 in_progress, 2 pending** in `feature_list.json`.
- **Production deploy:** <https://chess-backend.duckdns.org>
- **Frontend deploy:** <https://chess-frontend-52i.pages.dev/>

### Pending features (lowest priority first)

- **24 `random-matchmaking`** — Redis-backed matchmaking queue.
  **Deferred by the user** behind the friends-list + invitations pair
  (see roadmap). Stays `pending`, not next in intent.
- **26 `deploy-config-sync`** — scp `docker-compose.prod.yml` to EC2 on
  every deploy (operational/infra; no application code).

---

## Commit state — 23.7 staged, ready for its own commit

- **23 (`bot-opponent`) + 23.5 (`bot-difficulty`)**: already committed by
  the user in `7222479 feat: bot implementation stockfish`.
- **23.7 (`bot-strength-fairy-stockfish`)**: staged (the leader ran
  `git add -A`), working tree clean — a clean independent commit (23
  files). The user just needs to commit it.

Suggested commit message:
`feat(bot): Lichess-style Elo strength via Fairy-Stockfish (skill+depth)`

---

## Carried deploy actions

- **22.7 `room-access-tokens`** still needs a coordinated frontend deploy
  (live frontend joins token-less; backend in-flight-safe via null-token
  legacy rule).
- **Bot (23 / 23.7)**: ⚠️ CORREGIDO 2026-06-22 — esta nota afirmaba que la
  imagen desplegada ya bundleaba Fairy-Stockfish. Es **FALSO**: esos commits
  nunca se pushearon, así que producción (`a5dde2d`) no los tiene. La imagen
  con Fairy-Stockfish (`fairy_sf_14`, `/usr/local/bin/fairy-stockfish`) solo
  se construirá cuando el user haga `git push origin main`. Verificar entonces
  que el path coincida con `chess.bot.engine-path`.

---

## Roadmap (user re-prioritisation, 2026-05-30)

Order the user wants: bot work (done: 23, 23.5, 23.7) → **friends list**
→ **direct invitations** (layered on friends + `room-access-tokens` 22.7)
→ **random-matchmaking (24) deferred** until after those. Friends-list
and invitations are NOT yet entries in `feature_list.json` — promote when
the user picks one up (new entry, priority < 24, full harness cycle).

## Other future scope not yet promoted

- **Bot Phase 2**: MultiPV-4 + randomized weakness (the full Lichess
  model) for more human-feeling errors.
- **"Section to learn" using the Lichess API** (user's idea, 2026-05-30)
  — a future feature, unrelated to the engine work.

---

## Leader notes for the next session

- Repo is in extension mode. `feature_list.json` is at 37/0/2. Per the
  user's roadmap, the next feature is a **friends list** (not yet an
  entry); `random-matchmaking` (24) is deferred.
- New features get a full harness cycle (leader plan → implementer →
  reviewer → user OK → feature note → history entry).
- Sub-agent dispatch works directly: `Agent(subagent_type: "implementer"
  | "reviewer")` resolves (frontmatter added 2026-05-29).
- Per [[feedback-user-handles-commits]]: the user handles commits, BUT
  has twice explicitly asked the leader to run `git add` for the
  accumulating bot bundle (23/23.5/23.7) — the index currently holds all
  three. Keep flagging the uncommitted set at every close so it doesn't
  grow unbounded; the user is batching the bot work into one commit.
- **Async-IT flakiness pattern to watch** (bit us in 23.7): any IT that
  `await()`s on a Redis-state condition and then asserts a Postgres
  archive or a `verify(messagingTemplate…)` OUTSIDE the await is racy,
  because the terminal-action services (GameAbandonService /
  GameTimeoutService / BotMoveService.failGame) do compute(Redis) →
  archive(Postgres) → broadcast on an async thread. Put the archive
  assertion inside `untilAsserted` and use Mockito `timeout()` for
  broadcast verifies. Worth a sweep of the other terminal-path ITs if
  flakiness recurs.
- When the user picks up "friends list": new domain area (a
  friendship/relationship between `users`), needs a Flyway `V3`
  migration; it's the prerequisite the user named for direct invitations.
  Nothing in the repo models friendships yet — plan it fresh.
