# CLAUDE.md

Kepler Gadgetbridge: the public Android app for the Kepler watch, a fork of
Gadgetbridge (GitHub `Zebsi235/kepler-gadgetbridge`; use `gh`, not `glab`).
Default branch `feat/f91kepler-device`.

**Start here: [docs/KEPLER.md](docs/KEPLER.md)** (branches, where the Kepler
code is, firmware gating, release notes, build host). Release runbook:
[docs/KEPLER_RELEASE_BUILD.md](docs/KEPLER_RELEASE_BUILD.md).

Hard rules:

- Work on a branch, PR into `next/...` or `feat/f91kepler-device`; never
  push to `feat/f91kepler-device` or `master` directly. Only the owner tags.
- Hide features the watch firmware lacks; no "needs newer firmware" hints.
- Release notes: Codeberg style, no usernames, always "Built on Gadgetbridge <base>".
- No gradle builds on the station Pi: zitadelle, else jarvis, or CI.
- No AI mention in commits, PRs or notes.
