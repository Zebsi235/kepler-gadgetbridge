# Kepler release build (runbook)

The published Kepler APK is the **release** build (not debuggable), signed with
the Kepler key. This page says why, what the fork changes for it, how CI builds
it, and what to check before each release. Developer entry point: `docs/KEPLER.md`.

## Why

Up to v0.94.0-kepler-3 every published APK was `assembleMainlineDebug`, signed
with the Kepler key through the *debug* signing config. A debug build is
`android:debuggable="true"`, which means anyone with USB access to an unlocked
phone can use `adb shell run-as nodomain.freeyourgadget.gadgetbridge` (or
`adb backup` on older Android) to read the app's private data: the Gadgetbridge
database with health data and the notification history, preferences, and log
files. `BuildConfig.DEBUG` is also `true`, which turns on developer-only code
paths (listed below). A release build closes both.

## What the fork changes

1. `app/build.gradle`: a `keplerRelease` signing config that reads the same
   system properties as the Kepler debug signing config (`kepler_store_file`,
   `kepler_store_password`, `kepler_key_alias`, `kepler_key_password`), so it is
   the same key. The `release` build type uses it **only when
   `kepler_store_file` is set**. Without the property `assembleMainlineRelease`
   behaves exactly as upstream: an unsigned release APK.
   (When the property is set, the `banglejs` flavor's release is also signed with
   the Kepler key; the fork does not build that flavor.)
2. `.github/workflows/build.yml`: builds `assembleMainlineRelease` when the
   signing secrets are present, else `assembleMainlineDebug` (see "How CI builds
   it"). The unit tests stay `testMainlineDebugUnitTest`.

## Same app, same key: updating in place works

- applicationId: `nodomain.freeyourgadget.gadgetbridge` from `defaultConfig`.
  The `mainline` flavor sets no `applicationIdSuffix`, and neither the `debug`
  nor the `release` build type adds one (only upstream's `nightly` and
  `nopebble` build types do, with `.nightly` / `.nightly_nopebble`).
- versionName / versionCode: from `defaultConfig`, identical for debug and
  release. Only `nightly`/`nopebble` override the versionCode.
- Certificate: both signing configs read the same keystore and alias.

So a release APK installs over a debug-built APK of the same or lower
versionCode without uninstalling and keeps all data, bonds and settings. Going
back from release to debug also works (same key), but is not something to ship.

## What the upstream release build type does

```
release {
    minifyEnabled true
    proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
}
```

- **R8 is on** (code shrinking + optimisation with `proguard-android-optimize.txt`).
- **No obfuscation**: `app/proguard-rules.pro` has `-dontobfuscate`, so class,
  method and enum names stay as written and stack traces stay readable.
- **No resource shrinking**: `shrinkResources` is not set.
- `debuggable` defaults to `false` for `release`.
- Upstream's own `nightly` build type uses the same R8 settings, so upstream
  code is exercised minified every night. **The Kepler code is not**: it has
  only ever run in debug (unminified) builds.

The fork does not change any of these settings.

### R8 risk review for the Kepler code

Checked in `devices/f91kepler/` and `service/devices/f91kepler/`:

- `F91KeplerCoordinator` and `F91KeplerSupport` are created by reflection
  (`DeviceType` / `DeviceSupportFactory`); kept by the existing
  `DeviceCoordinator` and `DeviceSupport` keep rules in `proguard-rules.pro`.
- `F91KeplerImageActivity` and `F91KeplerPairAgainReceiver` are in the
  manifest; `F91KeplerCropView` is in `layout/activity_f91kepler_image.xml`;
  `XTimePreference` / `DragSortListPreference` are in the `devicesettings_f91kepler*`
  XML. AAPT generates keep rules for all of these.
- `F91KeplerSettingsCustomizer` is `Parcelable`; its `CREATOR` is kept by the
  default Android rules.
- `NotificationType.name()` is used in `F91KeplerSupport`; safe because of
  `-dontobfuscate`.
- No Gson models, `Class.forName`, `getDeclared*` or GreenDAO entities in the
  Kepler code. The Kepler preferences are plain string keys.

Residual risk is in R8 *optimisation* (inlining, class merging, removing code
it thinks is unreachable), which no review fully rules out. That is what the
phone + rig check below is for.

## `BuildConfig.DEBUG` paths that change behaviour

`BuildConfig.DEBUG` was `true` in the debug-built APKs and is `false` in the release build. None are in
the Kepler code; the ones that touch a Kepler user:

| Where | Debug (before) | Release |
|---|---|---|
| `service/DeviceCommunicationService.java` (`connectToDevice`, reconnect path) | `stressTestConnect()`: calls `connect()` on a helper object that **catches and logs exceptions** and returns `false` | `deviceSupport.connect()` directly; an exception now reaches the outer `catch`, which disposes the support. Default stress prefs are 0 extra / serial, so normally the same single `connect()` call |
| `DeviceCommunicationService.java` (`connectToDevice` dispose, `removeDeviceSupport`) | `stressTestDispose()`: `dispose()`, plus a second one if the stress pref is set | plain `dispose()` |
| `Logging.java` `resolveRootLevel` | root log level DEBUG (TRACE when trace logging is on) | **INFO in logcat unless "write log files" is on**; with file logging it stays DEBUG. Users must turn on file logging before sending a log |
| `Logging.java` rolling policy | 100 MB per file, 200 MB cap | 10 MB per file, 100 MB cap |
| `GBApplication` constructor | StrictMode thread + VM policies (log only) | off |
| `GBApplication.isDebug()` → default of the "crash notification" pref | on by default | off by default (only for users who never touched the pref) |
| `GBExceptionHandler` | heap dump to external cache on OutOfMemoryError | none |
| `adapter/GBDeviceAdapterv2.java` | "Test new function" item in the device menu | hidden |
| `activities/devicesettings/DeviceSpecificSettingsFragment.java` | per-device debug settings screen (coordinator's `getSupportedDebugSettings`) | hidden |
| `service/DeviceSupportFactory.java` | honours `pref_device_debug_remove_busy_checking` | ignored |
| `activities/debug/DatabaseTableDebugFragment.kt` | "drop table" in the database debug screen | hidden |
| `activities/NotificationFilterActivity.java`, `externalevents/NotificationListener.java` | extra filter timing / dump logging | none |

The rest (Garmin, Zepp OS, Fossil, Ultrahuman, Xiaomi, OneTouch, PineTime DFU)
does not affect the Kepler Watch.

## How CI builds it

CI builds and publishes the release APK whenever the signing key is present
(every build in this repository); without the key it falls back to the debug
build so a fork's PR is still checked, and a tag build refuses to publish. The
artifact is `kepler-mainline-release` (`Gadgetbridge-Kepler-<version>-vc<code>.apk`).
A release build also runs lint-vital (`lintVitalAnalyzeRelease`) with
`abortOnError = true`.

History: the switch from debug to release builds came with v0.95.1-kepler-1; the
certificate of the first release APK matched v0.94.0-kepler-3 (SHA-256
C3:3B:9E:5A:...:A0:8E).

## Steps per release

1. Download `kepler-mainline-release` from the CI run of the commit to be
   released (`rc/*` branch).
2. Check the APK:
   - `apksigner verify --print-certs Gadgetbridge-Kepler-*.apk` and the same
     for the current public APK: the SHA-256 certificate digests must match.
   - `aapt dump badging <apk>`: `package: name='nodomain.freeyourgadget.gadgetbridge'`,
     the expected versionCode, and **no** `application-debuggable` line.
   - The CI log line "Built ... (release, signed=true)" shows the secrets were
     present; an unsigned APK (`*-unsigned.apk`) must never be handed out.
3. Full phone + rig check (below) with the APK installed **over** the current
   public APK, without uninstalling.
4. The owner tags; CI publishes the GitHub Release. Release notes: `docs/KEPLER.md`.

## Required check before shipping

On a phone that has the current public APK, paired with a Kepler Watch, data
present:

- Install the release APK over it. App opens, the watch is still listed and
  bonded, its settings and the notification history are still there.
- Connect, disconnect, reconnect (also: Bluetooth off/on, walk out of range),
  and after a phone reboot. Watch the reconnect path closely, it is the one
  `BuildConfig.DEBUG` changes.
- Every Kepler settings screen opens and saves (firmware 3.0 and 3.1+ screens,
  watch modes list, sound settings, face).
- Image screen: pick, crop, rotate, upload.
- Notifications reach the watch, dismissals clear them, the notification mark
  on connect works.
- Alarms set and fire; time sync; find phone / find watch.
- The "pair again" notification after a lost bond.
- Turn on "write log files", export a log, and check it contains the Kepler
  DEBUG lines.
- A HIL rig run on the watch with the release APK on the rig phone.
