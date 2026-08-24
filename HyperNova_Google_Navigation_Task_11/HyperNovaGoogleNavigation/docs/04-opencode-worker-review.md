# OpenCode Worker Review

Date: 2026-08-19

## Worker: documentation draft

- Model: `opencode/deepseek-v4-flash-free`
- Task: draft `README.md`, Gemini Maps Grounding extension design, and the
  runtime validation plan.
- Owned files: `README.md`, `docs/02-gemini-maps-grounding-extension.md`, and
  `docs/03-test-and-runtime-validation-plan.md` only.
- Result: process exited successfully; it changed only owned files.
- Worker verification: debug assembly and unit tests passed at its checkpoint;
  lint exposed one location-permission defect in `MainActivity`.
- Codex review: accepted after correcting the permission defect and revising
  stale build/lint claims, SDK baseline details, legal-map-padding behavior,
  and pre-key evidence.
- Disposition: **ACCEPTED WITH REWORK**.

## Worker: unit-test scaffolding

- Model: `opencode/deepseek-v4-flash-free`
- Task: create focused tests for API-key policy, destination-token storage,
  request deduplication, route geometry, session reduction, and Navigator
  readiness.
- Owned files: the corresponding package paths under `app/src/test` only.
- Result: created the requested suites, but its test run found three failures.
  The worker then stalled while diagnosing an invalid route fixture and was
  interrupted (exit 130); its last edit also left temporary diagnostic output.
- Codex review: removed the diagnostic, corrected the route fixture to use
  valid world coordinates, strengthened placeholder detection, added the
  required coroutine opt-in, reviewed every assertion, and added independent
  contract-projection coverage.
- Final verification: 55 tests, 0 failures, 0 errors.
- Disposition: **PARTIALLY ACCEPTED, THEN REWORKED BY CODEX**.

## Invocation note

OpenCode CLI `1.17.11` was verified locally. This installed version does not
support the requested `--auto` option, so workers were invoked non-interactively
with `opencode run --agent build --dangerously-skip-permissions` under the
session's explicit authorization. The interactive TUI was never opened. No
worker committed, pushed, or modified a frozen/external project.
