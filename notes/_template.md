# Feature NN — <Feature title>

**Feature ID:** `<feature-id>` (from `feature_list.json`)

**Status:** done | in progress

---

## What we built

Two or three sentences. What problem this solves, what user-visible
behavior or capability it adds.

## Java / Spring concepts that appear

A bulleted list. For each concept:

- **`Concept name`** — short explanation. What it is, how Spring or
  Java exposes it, where in the code you see it. Link to the official
  documentation.

Examples of what goes here:

- `@RestController` and how request mapping resolves.
- Constructor injection vs `@Autowired` field injection.
- Records as DTOs and how Spring's `Jackson` handles them.
- `@Transactional` and its proxy-based mechanics.
- Spring's `MessageConverter` chain.
- Testcontainers lifecycle.

Keep this section practical. Two or three concepts well explained beat
ten concepts in name only.

## Decisions taken

For each non-trivial decision:

- **Decision:** what we chose.
- **Alternatives considered:** the other options we looked at.
- **Why this one:** trade-offs, constraints, the deciding factor.

Examples:

- Why we used REST + STOMP instead of a single bidirectional
  WebSocket protocol.
- Why we store active state in Redis and not in Postgres.
- Why we chose `chesslib` over implementing rules from scratch.

This section is the one a reviewer will read most carefully. Be
specific and brief.

## How this compares to what I know

This is the most useful section if you come from another ecosystem.
Show the parallels and the differences.

- **In Scala / Typelevel this would be...** — equivalent or near-
  equivalent. Where the model is similar, where it diverges.
- **In Node this would be...** — same exercise.

Examples:

- "`@Transactional` in Spring is conceptually like passing a
  `Transactor[F]` in Doobie, but declarative — Spring wraps the
  bean in a proxy that opens and commits the transaction around the
  method call."
- "A Spring `@Service` lifecycle-managed singleton is what you would
  get in Cats Effect by constructing the service once at the edge
  of the world and threading it through."
- "Spring's `@RestController` annotation is roughly an `http4s`
  `HttpRoutes` definition, but with the routing inferred from
  annotations on methods."

## Gotchas / things I learned the hard way

Things that surprised you, that took longer than expected, that you
would do differently next time.

- Honest entries beat polished ones.
- One sentence each is enough.
- If you did not hit any gotchas, you can write "None this round."
  Do not invent gotchas.

## To dig deeper

Links you found useful while building this:

- Official Spring docs page on X.
- Stack Overflow answer that clarified Y.
- Blog post or talk that gave a good mental model.
- Section in Spring's reference manual.

## File map

Where this feature lives in the repo. Helps a future you find it.

- `src/main/java/.../Foo.java` — what it does.
- `src/test/java/.../FooIT.java` — what it covers.
- `src/main/resources/db/migration/V1__bar.sql` — what it adds.
- ...
