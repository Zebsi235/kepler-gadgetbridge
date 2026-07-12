# Sync F91 Kepler fork → upstream Gadgetbridge $UPSTREAM

Upstream **Gadgetbridge $UPSTREAM** has been released (this fork is currently on
**$BASE**). Produce an updated app that is **latest upstream Gadgetbridge + all F91
Kepler customizations**, and deliver it as a **reviewable PR** into
`feat/f91kepler-device`. Once a human approves and it is merged, tagging cuts the
signed release automatically.

You (the Autonomous Issue Agent) own everything from fetching upstream to a **green,
reviewable PR**: fetch → merge → resolve conflicts → bump version → make CI pass. A
human reviews the PR; the release is cut after merge (see the last section).

## Non-negotiable rules
- **Never add co-authorship or AI attribution anywhere.** No `Co-Authored-By:`
  trailers, no "Generated with…" / "Co-authored with…" markers, no mention of an AI
  in any commit message, the PR title, the PR body, or any issue/PR comment. Author
  every commit as the repository owner only.
- Work on a **new branch off `feat/f91kepler-device`** and open the PR **into
  `feat/f91kepler-device`**. Never commit directly to `feat/f91kepler-device` or
  `master`, and never force-push a shared branch.
- **Preserve Kepler code** wherever it and upstream touch the same lines; take
  upstream everywhere else. Do not revert unrelated upstream improvements.
- If conflicts are pervasive or ambiguous, **stop and hand off**: open the PR as a
  draft that spells out exactly what a human still needs to resolve. Do not guess.

## Repository map
- Remotes: `origin` = this GitHub fork; `upstream` =
  `https://codeberg.org/Freeyourgadget/Gadgetbridge.git` (the real Gadgetbridge).
- `feat/f91kepler-device` is the working branch (holds all Kepler work); `master`
  only mirrors `upstream/master`.
- **Kepler-owned paths** — keep *our* side on conflict, and never delete these:
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/f91kepler/**`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/f91kepler/**`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/devices/f91kepler/**`
  - `app/src/main/res/drawable/ic_device_f91kepler*`, `app/src/main/res/xml/devicesettings_f91kepler_*`
  - the `F91_KEPLER(...)` entry in `app/src/main/java/.../model/DeviceType.java`
  - the `f91kepler` entries in `app/src/main/res/values/strings.xml` and `arrays.xml`

## Steps

### 1. Prepare
```bash
git remote add upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git 2>/dev/null || \
  git remote set-url upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git
git fetch upstream --tags
git checkout feat/f91kepler-device && git pull --ff-only
git checkout -b sync/upstream-$UPSTREAM
```

### 2. Merge upstream (prefer the release tag, fall back to master)
```bash
git merge --no-edit $UPSTREAM || git merge --no-edit upstream/master
```

### 3. Resolve conflicts
Favor **our** Kepler code where it collides with upstream; take upstream everywhere
else. Recurring conflicts seen in the last sync (0.92.2 had **five** — expect
similar):
- **`DeviceCoordinator.java` / `AbstractDeviceCoordinator.java`** — upstream
  periodically adds new coordinator API (last time: `supportsMetrics` /
  `getMetricsSampleProvider`). **Take upstream's version.** `F91KeplerCoordinator`
  extends `AbstractBLEDeviceCoordinator` → `AbstractDeviceCoordinator`, which
  provides the default implementations, so no Kepler-side edit is needed.
- **`app/build.gradle`** (version block) — take upstream's `versionName`; the
  `versionCode` is set in step 4.
- **`CHANGELOG.md`** and **`app/src/main/res/xml/changelog_master.xml`** — **take
  upstream verbatim** (`git checkout --theirs <file>`). This fork keeps no
  Kepler-specific changelog entries on purpose (avoids conflicts).

Then prove it is fully resolved:
```bash
git diff --name-only --diff-filter=U        # MUST be empty
grep -rnE '^(<<<<<<<|=======|>>>>>>>)' app/ *.gradle 2>/dev/null && echo "MARKERS REMAIN" || echo "clean"
```

### 4. Version bump — `app/build.gradle`, in `defaultConfig`
- `versionName` → `$UPSTREAM` (tracks the upstream base).
- `versionCode` → **one greater than upstream's merged `versionCode`**. Upstream's
  can now exceed this fork's last release, so it must strictly beat it or the app
  won't install as an update. Check:
  `git show upstream/master:app/build.gradle | grep versionCode`.
- This bump is also what stops the upstream watcher from re-opening this issue.

### 5. Commit (no co-authorship — re-read the rules above)
```bash
git add -A
git commit -m "Merge upstream Gadgetbridge $UPSTREAM into f91kepler-device"
# optional separate commit:
# git commit -m "build: bump versionCode to <N> for the v$UPSTREAM-kepler release"
```

### 6. Push and open the PR
```bash
git push -u origin sync/upstream-$UPSTREAM
gh pr create --base feat/f91kepler-device --head sync/upstream-$UPSTREAM \
  --title "Sync upstream Gadgetbridge $UPSTREAM into f91kepler-device" \
  --body "<summary — NO attribution>"
```
The PR body must summarize: the upstream range merged, each conflict and how it was
resolved, the version bump, and the CI result.

### 7. Make CI green — this is the build + the tests
Opening the PR triggers the **Build & Release (Kepler)** workflow on the
`pull_request` event. It provisions **JDK 21** (upstream's `FitCodeGenerator` module
requires a Java-21 toolchain — a plain JDK-17 build fails there, so **let CI do the
building; do not fight a local JDK**), installs the Android SDK (platform 36 /
build-tools 36.1.0), then runs `:app:assembleMainlineDebug` and
`:app:testMainlineDebugUnitTest`.
```bash
gh pr checks --watch            # wait for the run to finish
gh run view --log-failed        # if red, read the failing step
```
Fix whatever the merge broke — commonly a **new upstream abstract method to
implement**, a **moved/renamed API** the Kepler code calls, or a **resource-merge
error** in the f91kepler XML/strings. Push fixes to the same branch (CI re-runs).
Repeat until **every check is green**.

## Definition of done (agent)
- PR open into `feat/f91kepler-device` with **all CI checks green** (APK builds under
  JDK 21, unit tests pass).
- No conflict markers; Kepler customizations intact; `versionName` = `$UPSTREAM` and
  `versionCode` bumped above upstream's.
- **Every commit and the PR body free of co-authorship / AI attribution.**
- PR body summarizes the merge, conflicts, version bump, and CI status.
- If you bailed: PR is a **draft** clearly stating what a human must resolve.

## Release — after a human reviews & merges (do NOT self-merge)
Once the PR is approved and merged into `feat/f91kepler-device`:
```bash
git checkout feat/f91kepler-device && git pull --ff-only
git tag -a v$UPSTREAM-kepler -m "Kepler-Gadgetbridge $UPSTREAM (F91 Kepler)"
git push origin v$UPSTREAM-kepler
```
The tag push triggers **Build & Release (Kepler)**, which builds the signed APK
(signing key comes from the `KEPLER_KEYSTORE_*` repo secrets) and publishes the
GitHub Release with the APK attached. No manual signing or uploading.
