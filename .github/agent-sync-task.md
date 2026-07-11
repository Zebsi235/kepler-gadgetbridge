Upstream **Gadgetbridge $UPSTREAM** is newer than this fork's current base (**$BASE**). This issue is picked up automatically by the Autonomous Issue Agent, which opens a PR merging upstream into `feat/f91kepler-device`. A human then reviews/merges and tags the release.

## Task (for the agent)
You are on a branch based off `feat/f91kepler-device`. Do all work here and open a PR into `feat/f91kepler-device`. Do **NOT** tag, build, or publish.

1. Add/refresh the upstream remote and fetch it:
   ```bash
   git remote add upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git 2>/dev/null || \
   git remote set-url upstream https://codeberg.org/Freeyourgadget/Gadgetbridge.git
   git fetch upstream --tags
   ```
2. Merge upstream release `$UPSTREAM` into the working branch (fall back to `upstream/master` if the tag isn't present):
   ```bash
   git merge $UPSTREAM || git merge upstream/master
   ```
3. Resolve conflicts. Favor **our** F91/Kepler device code where it and upstream touch the same lines; take upstream changes everywhere else. Do not revert unrelated upstream improvements.
4. In `app/build.gradle`, set `versionName` to `$UPSTREAM` and bump `versionCode` to greater than upstream's merged value. (This is what stops this watcher from re-opening the issue.)
5. If submodules changed (`.gitmodules` present), run `git submodule update --init --recursive`.
6. Do **NOT** run a full Gradle build (too heavy here). Verify no `<<<<<<<`/`=======`/`>>>>>>>` markers remain and `git status` is clean, then commit.

## Acceptance
- PR opened into `feat/f91kepler-device` merging `$UPSTREAM`, with no conflict markers, Kepler customizations preserved, and `versionName` = `$UPSTREAM`.
- PR body summarizes the upstream range merged and any conflicts resolved.
- If conflicts are pervasive or ambiguous, stop and describe the situation in the PR for a human to finish rather than guessing.

## After merge (human)
Review & merge the PR, then `git tag -a v$UPSTREAM-kepler -m "..."` and push the tag — that triggers **Build & Release (Kepler)** to build the signed APK and publish the release.
