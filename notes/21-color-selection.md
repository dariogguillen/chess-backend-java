# Feature 21 — Let the room creator choose their side (WHITE/BLACK/RANDOM)

**Feature ID:** `color-selection` (from `feature_list.json`)

**Status:** done

---

## What we built

Until now a room's creator was always white and the joiner always black —
the side was implicit in list position (`players[0]` = creator = white).
This feature lets the creator pick their side at room-create time: `WHITE`,
`BLACK`, or `RANDOM` (a server-side coin flip the client cannot bias). The
chosen side is persisted on the `Room`, the joiner automatically gets the
opposite, and every role / white-black decision now derives from the stored
side instead of from list order. The change is additive and
backwards-compatible: a client that omits the new field gets white exactly
as before.

## Java / Spring concepts that appear

- **Backwards-compatible record evolution.** Adding a component to a
  `record` normally breaks every `new Room(...)` call site and every old
  serialised payload. We reuse the `Player.userId` pattern (feature 19):
  the new canonical 4-arg constructor `Room(id, players, status, creatorSide)`
  is the one Jackson targets when deserialising; a null-tolerant **compact
  constructor** rewrites a `null` `creatorSide` to `Side.WHITE` (so rooms
  serialised into Redis *before* this deploy still deserialise, as
  white-creator rooms); and a 3-arg **convenience constructor** delegates
  with `Side.WHITE` so every existing call site compiles unmodified.
  [Records](https://docs.oracle.com/en/java/javase/21/language/records.html)
- **Enum-typed request component → OpenAPI.** `CreateRoomRequest.preferredSide`
  is a real `SidePreference` enum, not a `String`. springdoc renders an
  enum component as an OpenAPI `enum` array automatically, which
  `openapi-typescript` on the frontend turns into a TypeScript literal
  union (`"WHITE" | "BLACK" | "RANDOM"`) — no hand-maintained
  `allowableValues` list. The `@Schema` annotation only adds a human
  description and example.
- **Injectable randomness seam.** The `RANDOM` coin flip lives in a
  `@Component RandomSideChooser` (`SecureRandom`-backed) rather than inline
  in the service. Two payoffs: server-authoritative randomness (anti-cheat,
  mirroring `RoomCodeGenerator`'s ownership of room-code randomness), and
  a seam a unit test can stub with Mockito so the otherwise-nondeterministic
  `RANDOM` branch becomes assertable.
- **Exhaustive `switch` expression over an enum.** `resolveCreatorSide`
  uses an arrow `switch` with one arm per `SidePreference` constant; the
  compiler enforces exhaustiveness, so adding a fourth preference later
  would fail to compile until handled.

## Decisions taken

- **Decision:** a separate `SidePreference { WHITE, BLACK, RANDOM }` enum
  for the request, distinct from the domain `Side { WHITE, BLACK }`.
  - **Alternatives considered:** reuse `Side` and treat `null` as "random";
    or add a `RANDOM` constant directly to `Side`.
  - **Why this one:** `Side` models a *real* piece owner / side-to-move; a
    game is never "random". Polluting `Side` with `RANDOM` would force every
    downstream consumer to handle an impossible case. Keeping the intent
    (`SidePreference`) separate from the resolved value (`Side`) means
    `RANDOM` is resolved once, at the create boundary, and never leaks
    inward. The acceptance text said "`Side preferredSide`"; the plan
    refined this to the cleaner two-enum split.
- **Decision:** resolve `RANDOM` at create time and store a concrete `Side`,
  never store `RANDOM`.
  - **Alternatives considered:** store the preference and resolve lazily at
    join time.
  - **Why this one:** the creator learns their colour immediately in the
    create response, the joiner deterministically gets the opposite, and no
    layer downstream of the room ever has to special-case `RANDOM`.
- **Decision:** derive roles from `Room.creatorSide`, keeping list position
  to identify *who* (creator vs joiner) and the side field to decide *which
  colour*.
  - **Alternatives considered:** add a `role`/`side` field to `Player`.
  - **Why this one:** keeps the `Player` domain record minimal and keeps the
    derivation in one boundary mapper, consistent with the existing
    position-based design — we changed the rule, not the shape.

## How this compares to what I know

- **In Scala / Typelevel this would be...** `SidePreference` is a small
  sealed ADT (`sealed trait SidePreference; case object White/Black/Random`)
  and `Side` a separate two-case ADT — exactly the "model the impossible
  state away" instinct. The null-tolerant compact constructor is the
  awkward Java stand-in for what you'd express with a smart constructor or
  an `Option` default; Java records have no `copy` with defaults, so the
  convenience-constructor overload plays the role of `Room.apply` with a
  defaulted parameter. Injecting `RandomSideChooser` is the same move as
  threading a `Random[F]` (cats-effect `Random`) so the effect is at the
  edge and tests can supply a deterministic instance — here Mockito's stub
  is the "deterministic `Random`".
- **In Node this would be...** `preferredSide` would be an optional field on
  the request body with `??  "WHITE"` defaulting, and `RANDOM` a
  `crypto.randomInt(2)` flip behind a small injected function so a test can
  monkeypatch it. The enum→literal-union story is the inverse direction of
  what you'd do with `zod`: here the backend enum *generates* the frontend
  union via OpenAPI codegen instead of the client declaring it.

## Gotchas / things I learned the hard way

- The compact constructor runs **before** the explicit body assignments,
  so the `null → WHITE` default must live in the compact constructor itself
  (not the convenience overload) for Jackson deserialisation of legacy
  Redis payloads to benefit from it.
- Mockito's `compute(anyString(), any())` stub has to actually *invoke* the
  passed `BiFunction` (via `thenAnswer`) — the production logic that builds
  the `Room`/`Game` lives inside that lambda, so a plain `thenReturn` would
  skip exactly the code under test.

## To dig deeper

- [Java records (JLS / tutorial)](https://docs.oracle.com/en/java/javase/21/language/records.html)
  — canonical vs compact vs additional constructors.
- [springdoc-openapi — enums in the schema](https://springdoc.org/) — how
  enum components surface as OpenAPI `enum` arrays.
- [`SecureRandom`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/SecureRandom.html)
  — why a CSPRNG for an anti-cheat coin flip.

## File map

- `src/main/java/.../domain/SidePreference.java` — new request-intent enum.
- `src/main/java/.../domain/Room.java` — added `creatorSide`; canonical +
  convenience + null-tolerant compact constructors.
- `src/main/java/.../service/RandomSideChooser.java` — server-side coin flip.
- `src/main/java/.../service/RoomService.java` — `createRoom` resolves and
  stores the side; `joinRoom` assigns white/black from the stored side.
- `src/main/java/.../web/room/CreateRoomRequest.java` — added
  `preferredSide`.
- `src/main/java/.../web/room/RoomController.java` — returns the resolved
  role on create, the opposite on join.
- `src/main/java/.../web/room/RoomDetailsMapper.java` — roles from
  `creatorSide`.
- `src/main/java/.../web/room/RoomDetailsResponse.java`,
  `RoomResponse.java` — updated `@Schema` / JavaDoc.
- `src/test/java/.../service/RoomServiceTest.java` — new unit test:
  deterministic `RANDOM` + black-creator white/black assignment.
- `src/test/java/.../domain/RoomTest.java`,
  `web/room/RoomDetailsMapperTest.java`,
  `web/room/RoomControllerIT.java`,
  `web/room/RoomDetailsControllerIT.java` — extended coverage.
- `docs/architecture.md` — API-contract section for `preferredSide`.
