# Feature 14 — README polish

**Feature ID:** `readme-polish` (from `feature_list.json`)

**Status:** done

---

## What we built

The repo's front door: a full rewrite of `README.md` so the artefact a
recruiter sees in the first 30 seconds matches what was actually shipped.
The previous version still framed the project as "work in progress" and
buried the production deployment, the agent harness, and the
testing-with-Testcontainers posture behind sections only a curious reader
would scroll to. The rewrite leads with the pitch, the live URLs, a
seven-bullet "what this demonstrates" highlights section, and two Mermaid
diagrams (layered architecture + end-to-end sequence) that render natively
on GitHub. A new "Engineering process" section makes the leader /
implementer / reviewer harness visible — links over duplication, so the
README points at `CLAUDE.md` / `AGENTS.md` / `.claude/agents/*` /
`feature_list.json` / `progress/` / `notes/` rather than re-explaining
them. The existing engineering depth (the three local-dev workflows, the
curl + wscat examples, the AWS + Caddy + OIDC deployment shape) was kept
intact, the stale lines were removed, and the out-of-scope section was
lifted verbatim from `docs/architecture.md` so reviewers see boundaries
as decisions rather than gaps.

## Java / Spring concepts that appear

This is a docs-only feature; no Java/Spring code shipped. The concepts
worth pinning are the ones a senior engineer would expect to see honoured
when they read the README cold:

- **GitHub-native Mermaid rendering.** GitHub's markdown renderer
  inlines `mermaid` fenced code blocks as SVG since 2022. No build step,
  no binary artefact, no Pandoc pipeline. The diagram source lives next
  to the prose, diffs cleanly, and survives a rename or a refactor
  without a parallel image edit. See the
  [GitHub blog post](https://github.blog/2022-02-14-include-diagrams-markdown-files-mermaid/)
  for the rendering contract.
- **Mermaid `flowchart TB` for layered architecture.** The
  `docs/architecture.md` "Layered architecture" section already
  documents the dependency direction as strictly top-to-bottom; `TB`
  (top-bottom) is the syntactically explicit form of the same
  constraint. `subgraph Backend { ... }` groups the Spring Boot
  application's internal layers and makes the external dependencies
  (Redis, Postgres, chesslib) visually separate from the deployable.
- **Mermaid `sequenceDiagram` for the end-to-end flow.** Sequence
  diagrams compress prose: "Player A creates a room, subscribes to the
  room topic, Player B joins via REST, the backend writes Redis and
  creates a Game, broadcasts ROOM_JOINED..." becomes ten lines of
  formal notation a recruiter can scan in under five seconds. The
  `actor` keyword distinguishes humans from systems; the `Note over
  ...` block bridges the "moves continue until terminal status"
  ellipsis without inventing a synthetic transition.
- **springdoc-served Swagger UI as a live API reference.** The "Try it
  live" block links to `https://chess-backend.duckdns.org/swagger-ui/index.html`
  because the running production app is the most honest API reference
  the repo can offer. springdoc generates it from the controllers'
  `@Operation` / `@ApiResponse` / `@Schema` annotations, so the README
  does not need to maintain a parallel endpoint table.

## Decisions taken

- **Decision:** Use the **Hybrid pitch paragraph** from the locked plan
  verbatim — no rewording during implementation.
  **Alternatives considered:** Iterating on phrasing while writing the
  README, splitting the pitch into two paragraphs, leading with the
  "portfolio rewrite" framing instead of the technical posture.
  **Why this one:** The pitch was the one piece the user reviewed and
  approved word-for-word in `progress/current.md`. The implementer's
  job is to honour the plan, not to "improve" approved copy. A
  reword would force a re-review for no engineering gain.

- **Decision:** Two Mermaid diagrams (architecture + sequence), no more.
  **Alternatives considered:** One diagram, three diagrams (add a
  deployment topology diagram), no diagrams (prose only).
  **Why this one:** Architecture answers "what are the boxes and how
  do they depend on each other?"; sequence answers "what happens when
  Player A clicks Create Room?". Those are the two questions a
  recruiter asks before they read code. A deployment topology diagram
  would duplicate the prose in the Deployment section without adding
  scannable value; the runbook owns operator-grade depth.

- **Decision:** Dedicated `## Engineering process` section with one
  bullet per harness file, linking to each.
  **Alternatives considered:** A single paragraph mentioning the
  harness in passing; duplicating each file's contents into the README;
  hiding the harness behind a "Process" subsection of `Deployment`.
  **Why this one:** The harness is the single most distinctive feature
  of this repo as a portfolio piece. Most Java backends do not have
  three role files and a persisted state store. Burying it would
  squander the differentiator; duplicating the files would create a
  drift surface. Linking sits exactly between the two and trusts the
  reader to click through if they care.

- **Decision:** "Try it live" formatted as three bullets with inline
  descriptions, followed by a one-line "how to use the demo"
  paragraph.
  **Alternatives considered:** A table (frontend / backend / docs as
  rows), a single paragraph with inline links, a hero-style block
  pinned at the very top above the pitch.
  **Why this one:** The locked plan specifies this exact shape. A
  table would be heavier than three bullets; a paragraph would bury
  the URLs in prose; a hero block above the pitch would put the demo
  before the "what is this" sentence, which is the wrong reading
  order for someone who landed on the repo without context.

- **Decision:** Out-of-scope section lifted from `docs/architecture.md`
  ("What is intentionally out of scope") rather than rewritten.
  **Alternatives considered:** Linking to the architecture doc's
  section without duplicating the items; expanding each item with a
  paragraph; dropping the section.
  **Why this one:** The architecture doc is comprehensive but is not
  what a recruiter reads first. Surfacing the four bullets (auth,
  ratings, tournaments, time controls) in the README pre-empts the
  "why didn't you ship X" question without forcing a click-through.
  Duplication is mild and the architecture doc remains the single
  source for the longer reasoning.

## How this compares to what I know

- **In Scala / Typelevel this would be...** the README discipline of an
  sbt multi-module build that treats `project/Common.scala` and a
  per-module `README.md` as part of the contract. The Typelevel
  ecosystem is unusually good at this: the http4s, cats-effect, and
  fs2 READMEs each open with a one-paragraph pitch, a `libraryDependencies
  += ...` snippet, and a working `IOApp` sketch — in that order — so
  a new reader can build a hello-world without leaving the page.
  This README is shaped on the same principle: pitch, live URLs,
  scannable highlights, then progressively deeper sections for the
  readers who keep scrolling. The Mermaid-as-code choice is the same
  argument as `mdoc` — render diagrams from the source of truth at
  build time rather than maintaining a parallel `docs/diagrams/`
  directory of binaries.

- **In Node this would be...** a `README.md` with shields.io badges,
  a "Quick start" `npm install && npm test` block, a Mermaid diagram
  if the maintainer is on the modern end of the ecosystem, and a
  `docs/` folder with deeper architecture material. The shape is
  similar; the discipline is harder to find because Node projects
  rarely encode it in agent role files. The leader / implementer /
  reviewer harness is the part of this repo that has no obvious
  Node parallel — the closest equivalent would be a `CONTRIBUTING.md`
  that pretends a single contributor follows the same rituals.

## Gotchas / things I learned the hard way

- **GitHub renders `mermaid` blocks but not every Mermaid feature.**
  GitHub's renderer lags the latest Mermaid release by several versions.
  Theme directives (`%%{init: {'theme': 'dark'} }%%`), some newer node
  shapes, and `journey` / `gantt` extensions can silently fall back to
  raw text. The defensive choice is to stick to the syntax that has
  shipped in Mermaid for years (`flowchart TB`, `sequenceDiagram`,
  `subgraph`, `Note over`, `actor`) and skip the cosmetic flourishes.
- **The "Try it live" frontend URL must be the trailing-slash form.**
  GitHub Pages serves the SPA from `/chess-frontend/` (with the
  trailing slash). Linking to the no-slash form triggers a 301
  redirect, which works but looks unpolished in a recruiter's network
  tab. One-character detail; worth pinning.
- **Mermaid's `note over` line breaks render as literal `<br/>` if
  you escape them.** The cleanest way to put a multi-line note over a
  sequence diagram is to keep it on a single line and trust the
  renderer to wrap. Escaped HTML in Mermaid prose is a footgun.

## To dig deeper

- [GitHub blog: include diagrams in Markdown files with Mermaid](https://github.blog/2022-02-14-include-diagrams-markdown-files-mermaid/)
- [Mermaid: flowchart syntax](https://mermaid.js.org/syntax/flowchart.html)
- [Mermaid: sequence diagram syntax](https://mermaid.js.org/syntax/sequenceDiagram.html)
- [Diátaxis](https://diataxis.fr/) — the four-mode documentation framework
  (tutorial, how-to, reference, explanation). The README sits at the
  intersection of all four; `docs/architecture.md` is the explanation
  pole, `docs/deploy-runbook.md` is the how-to pole, the OpenAPI spec
  is the reference pole, and the "Try it live" + "Running locally"
  bridge the tutorial pole.
- The READMEs of [http4s](https://github.com/http4s/http4s/blob/main/README.md),
  [cats-effect](https://github.com/typelevel/cats-effect/blob/series/3.x/README.md),
  and [fs2](https://github.com/typelevel/fs2/blob/main/README.md) as
  Scala-side references for the discipline.

## File map

- `README.md` — the rewrite. Sections (in order): title + CI badge,
  one-paragraph pitch, Try it live, What this demonstrates,
  Architecture (Mermaid `flowchart TB`), End-to-end flow (Mermaid
  `sequenceDiagram`), Stack, Running locally (3 workflows), API
  (REST + Swagger), WebSocket (STOMP), Deployment, Engineering process,
  Repository structure, Out of scope, License.
- `notes/14-readme-polish.md` — this note.
- `progress/current.md` — the locked plan with the four decisions the
  implementer honoured verbatim.
- `docs/architecture.md` — unchanged; the README links to it for depth.
- `docs/deploy-runbook.md` — unchanged; the README links to it from
  the Deployment section.
