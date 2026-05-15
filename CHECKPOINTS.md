# CHECKPOINTS

This is the checklist a feature must satisfy before it can be marked as
`done` in `feature_list.json`. It is the canonical "definition of done"
for this project.

`reviewer` walks through this list and rejects the feature if any item
fails. `leader` does not mark a feature as `done` before `reviewer`
approves.

---

## Mandatory checks (every feature)

### Build and test

- [ ] `./init.sh` exits 0.
- [ ] No tests were skipped, ignored, or commented out to make the
  build pass.
- [ ] No assertions were weakened from a previous version of the
  code.

### Scope and state

- [ ] The feature being closed is the one that was marked
  `in_progress` in `feature_list.json`.
- [ ] Only one feature is `in_progress` at any time during the work.
- [ ] All acceptance criteria for the feature (in
  `feature_list.json`) are visibly satisfied.

### Code

- [ ] New public service methods have JavaDoc covering contract and
  exceptions.
- [ ] No JPA entities are exposed through controllers or WebSocket
  messages. DTOs (records) are used at the boundary.
- [ ] Constructor injection only. No `@Autowired` on fields, no
  setter injection.
- [ ] No new dependencies in `pom.xml` without a justification in the
  commit message.
- [ ] No Lombok. No WebFlux. No H2 or embedded fakes for Postgres
  or Redis in tests.

### Tests

Integration tests (`*IT.java`) are the **baseline coverage** for every
feature. Unit tests (`*Test.java`) are added **only when they earn their
place** — see `docs/conventions.md` for the criteria.

- [ ] New REST endpoints have an integration test (`*IT.java`)
  covering happy path and at least one error path (if an error path
  exists in the endpoint's contract).
- [ ] New WebSocket flows have an integration test with a real
  STOMP client.
- [ ] New persistence code has an integration test using
  Testcontainers (real Postgres / real Redis).
- [ ] New code at the service or domain layer has unit tests **when the
  logic justifies one** — non-trivial branching, pure logic the IT
  cannot reasonably exercise, real edge cases the IT does not reach,
  or large input combinations. Trivial delegation, wiring, DTOs,
  records, `@Configuration` classes with trivial `@Bean`s, and
  defensive fallbacks for situations that cannot occur in production
  do **not** require a unit test.

### Errors

- [ ] New error conditions throw a specific exception from the
  hierarchy in `exception/`.
- [ ] The global handler maps the exception to the right HTTP status
  and a structured JSON body.
- [ ] Controllers do not catch their own exceptions.

### Logging

- [ ] Significant state changes are logged at `INFO`.
- [ ] Unexpected-but-handled situations are logged at `WARN`.
- [ ] No secrets or sensitive payloads in logs.
- [ ] Parameterized log messages (no string concatenation).

### Persistence (if applicable)

- [ ] Flyway migrations are forward-only. No edits to migrations that
  have already been applied.
- [ ] Migration filenames follow `V{N}__{description}.sql`.
- [ ] New entities and queries have integration tests.

### Feature note (mandatory)

- [ ] A file exists at `notes/NN-<feature-id>.md`, where `NN` matches
  the priority in `feature_list.json` (zero-padded).
- [ ] The note follows the structure in `notes/_template.md`. Every
  section from the template is present.
- [ ] The "What we built" section describes the user-visible behavior
  in 2-3 sentences.
- [ ] The "Java / Spring concepts that appear" section names at least
  one concept and explains how it is used in *this* feature, not in
  general terms.
- [ ] The "Decisions taken" section covers at least one non-trivial
  decision, with alternatives and reasoning.
- [ ] The "How this compares to what I know" section has at least one
  concrete comparison with Scala/Typelevel or Node.
- [ ] The "File map" section lists the files added or modified, with
  a one-line description each.

### Documentation (verified at review time)

These are implementer-scope items. They must be satisfied for the
reviewer to approve.

- [ ] If the feature changed the public API or the way to run the
  project, `README.md` was updated.
- [ ] If the feature introduced a new architectural decision,
  `docs/architecture.md` was updated.

The plan in `progress/current.md` is expected to state explicitly
whether each of these applies for the feature. If the plan says they
do not apply (e.g. "feature 2 introduces no public API"), the reviewer
treats them as N/A.

---

## Closing tasks (leader, post-approval)

These tasks execute **after** the reviewer approves. They are **not**
reviewer checkpoints — a feature can be approved with them pending,
because they are sequenced to happen after approval. The leader owns
them.

1. Update `feature_list.json` — set `status: "done"` on the closed
   feature.
2. Append a one-paragraph entry to `progress/history.md` describing
   what changed, which files were touched, and a link to the feature
   note.
3. Replace `progress/current.md` with a "session closed" note.

The reviewer should not fail a feature because these are pending; they
have not happened yet by design.

---

## Sign-off

A feature is fully `done` when:

1. All reviewer checkpoints above pass (reviewer approves).
2. The leader has completed the three closing tasks above.

If any reviewer checkpoint fails, the feature stays `in_progress`. Do
not negotiate with the checklist.
