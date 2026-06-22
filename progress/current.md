# Current session

**Status:** closed — no active feature. Feature
`bot-strength-fairy-stockfish` (priority 23.7) closed on 2026-05-30 with
reviewer approval and explicit user sign-off. Session ended for the day.
See `progress/history.md` for the entry.

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
- **Smoke test pendiente del PRÓXIMO push**: al pushear la 26, GitHub Actions
  corre el `deploy.yml` NUEVO (el del commit), así que ese push valida el scp.
  Sugerido: añadir un comentario inocuo a `docker-compose.prod.yml`, push, y
  `sudo cat /opt/chess/docker-compose.prod.yml` en la EC2 para confirmar el sync.

### Pendientes de esta línea ops
- Al **encender**: conseguir root (`ubuntu` vía EC2 Instance Connect, funciona
  con la instancia recién arrancada) y aplicar **swap (1-2 GB) + cap de `-Xmx`**
  en la JVM para que aguante encendida sin colgarse.
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
