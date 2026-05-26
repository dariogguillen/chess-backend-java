# Current session

**Status:** closed — no active feature. Maintenance feature
`cors-cloudflare-origin` (priority 15) closed on 2026-05-25.

See `progress/history.md` for the entry, including the operator
follow-up about checking `/opt/chess/.env` on EC2 after the next
deploy.

---

## Project state

- **23 done, 0 in_progress, 0 pending** in `feature_list.json`.
- The portfolio remained closed throughout this maintenance feature;
  feature 15 was a reopen via a new entry, not a plan extension.
- Next session continues in maintenance mode unless the user adds
  another entry for a bug fix, dep bump, or cross-repo coordination.

## Operator follow-ups carried over

- **Post-push to main:** the `deploy.yml` workflow ships the new YAML
  default automatically. After the deploy goes green, SSH to EC2 and
  run `cat /opt/chess/.env | grep CHESS_CORS`:
  - If the env var override exists and still points at `github.io`,
    remove it or update it to the Cloudflare origin.
  - If not set, no further action — the new YAML default takes effect
    automatically.
- **Verify in browser:** load `https://chess-frontend-52i.pages.dev/`
  in two browsers, create a room, join, play a move. Confirms the CORS
  preflight succeeds end-to-end against the deployed backend.

Carried from earlier sessions, still applicable:

- Rotate the RDS master password (`docs/deploy-runbook.md` for the
  procedure).
- Configure branch protection on `main` to require the `CI` check
  before merge.
- Consider replacing the static "181 tests" claim in README:23 with
  a Maven plugin that emits the count, OR remove the number entirely.
