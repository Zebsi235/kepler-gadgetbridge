# Kepler Gadgetbridge: developer entry point

This fork (GitHub `Zebsi235/kepler-gadgetbridge`, upstream Codeberg
`Freeyourgadget/Gadgetbridge`) is the public Android app for the Kepler watch.
Everything Kepler-specific is listed here; the rest is upstream Gadgetbridge.

## Branches

| Branch | What |
|---|---|
| `feat/f91kepler-device` | default branch: all Kepler work, the line releases are tagged on |
| `next/v<base>-kepler-<n>` | collects the feature PRs for the next release (e.g. `next/v0.95.1-kepler-3`) |
| `rc/v<base>-kepler-<n>` | release candidate: `next/...` merged + versionCode bump; draft PR into `feat/f91kepler-device` |
| `feat/f91kepler-*`, `fix/f91kepler-*` | one PR each, into the current `next/...` (or `feat/f91kepler-device`) |
| `master` | mirrors upstream `master` only |
| `archive/*` | kept history, not built |

Tags `v<base>-kepler-<n>` (e.g. `v0.95.1-kepler-2`): only the owner tags; a
`v*` tag makes CI publish the GitHub Release. Upstream updates are merged, not
rebased (published tags must stay ancestors); see `.github/agent-sync-task.md`.

## Where the Kepler code is

- `app/src/main/java/.../devices/f91kepler/`: coordinator, settings, firmware
  gates (`F91KeplerFirmware`), modes, image tools.
- `app/src/main/java/.../service/devices/f91kepler/`: BLE support, protocol,
  transfers, notifications.
- `app/src/test/java/.../devices/f91kepler/`: unit tests.
- `app/src/main/res/xml/devicesettings_f91kepler*.xml`, `f91kepler` strings.
- Protocol truth: the firmware (`kepler/firmware`) and
  `keplerHIL/docs/BLE_PROTOCOL.md`.

## Firmware-version gating

The watch's firmware version (Device Information) decides what is shown.
`F91KeplerFirmware` holds the minimum versions (`MIN_31`, `MIN_32`,
`MIN_LOST_ALERT`, ...) and `atLeast()` / `knownBelow()`. While the version
is unknown, `atLeast()` is false, so nothing newer is offered until the watch
has reported it. Rules:

- **Hide** every setting and feature the connected watch's firmware lacks.
  No "needs newer firmware" hints, no greyed-out entries.
- Nothing is written to the watch for a feature its firmware does not have.
- New gated settings go into the settings customizer's gate table, and
  `F91KeplerFirmwareGateTest` checks them (from `next/v0.95.1-kepler-3` on).

## Release notes

Codeberg style (like upstream's changelog), plain user-facing lines, one
`* Kepler: ...` line per change, no usernames or PR authors. Every release
note starts with **"Built on Gadgetbridge <base>"** (link to upstream), then
the version and versionCode, then which watch firmware it is for.

## Build and CI

- Never build on the station Pi (guard hook). Local gradle builds: zitadelle
  (default) or jarvis (Windows, when zitadelle can't and it is on).
- Usually CI is enough: `.github/workflows/build.yml` builds the APK
  (signed release build when the key is present) and runs the unit tests on
  every push and PR.
- Release build, signing and the per-release checks:
  [KEPLER_RELEASE_BUILD.md](KEPLER_RELEASE_BUILD.md).
