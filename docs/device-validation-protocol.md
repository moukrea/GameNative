# Device Validation Protocol — Per-Game Provisioning Pipeline

> The declarative provisioning pipeline is verified by machine for everything that *can* be: schema
> validity, engine idempotence/transactionality, resolver precedence, device branching, offline
> fallback, and per-fix migration conformance (all headless unit tests). The **one thing that cannot
> be automated is whether a game actually boots on real hardware.** This document is the human
> handoff for that step: a set of witness games, a candidate recipe for each, and the on-device
> procedure to confirm (or refute) them. Nothing here has been validated on a device by the author.

## 1. Build & enable

1. Build a debug APK of this branch (`feat/per-game-provisioning-pipeline`):
   `./gradlew :app:assembleModernDebug` (or `assembleLegacyDebug` for API < 29 devices). Requires NDK
   `27.3.13750724`; install it via `sdkmanager "ndk;27.3.13750724"` if missing.
2. Install on the target device and log in to the relevant store (Steam/GOG/Epic).
3. **Enable the flag.** The pipeline is opt-in and off by default
   (`PrefManager.enablePerGameProvisioning`). Until a Settings toggle is wired, flip it for testing by
   writing the preference, e.g. from a debug build via the app's preferences, or assert it is read as
   `enable_per_game_provisioning=true` in the `PluviaPreferences` DataStore.
4. With the flag **off**, behaviour is unchanged (legacy `GameFixesRegistry` path) — use this as the
   A/B baseline.

## 2. What to capture per game

For each witness game, record both **flag off** (baseline) and **flag on** (pipeline):

- Boots to main menu / in-game? (yes / black screen / crash-to-launcher / hang)
- `logcat` around launch (filter the `Provisioning`, `GameFixes`, `wine`, `box64` tags).
- The container config actually applied (graphics driver, DXVK/VKD3D, Box64 preset, env, DLL
  overrides) and which provisioning recipe + source resolved.
- If it boots: average FPS and any visual/audio defects.

## 3. Witness games & candidate recipes

The first set are **migrations** of existing in-repo fixes — these should behave identically to the
legacy path (regression check). The last entries are **new candidate recipes** (hypotheses) that the
legacy path does not cover and that this pipeline is meant to unblock.

| Game | Store / appId | Recipe source | What it applies | Expected |
| --- | --- | --- | --- | --- |
| Danganronpa 2 | Steam 413420 | migrated (`steam-413420`) | `WINEDLLOVERRIDES` d3dx9_43/d3dcompiler_43 = native,builtin | boots past shader init |
| Hogwarts Legacy | Steam 990080 | migrated (`steam-990080`) | wipes `ProgramData/Hogwarts Legacy` each launch | boots without Denuvo/EOS stale-state hang |
| Subnautica 2 | Steam 1962700 | migrated (`steam-1962700`) | writes `Engine.ini` UE renderer tuning into the prefix | boots with stable renderer |
| Slay the Spire 2 | Steam 2868840 | migrated (`steam-2868840`) | launch arg `--rendering-driver vulkan` + `icu=d` override | boots |
| Fallout 3 | GOG 1454315831 | migrated (`gog-1454315831`) | registry `Installed Path` (`<InstallPath>` substituted) | launcher finds install |
| Moonlighter | GOG 1589319779 | migrated (`gog-1589319779`) | installs GOG `MSVC2017` / `MSVC2017_x64` deps | boots |

### New candidate recipes to validate (not in the legacy path)

These exercise the **winetricks-style verb engine**. They are best-effort hypotheses based on the
games' known Windows dependencies; confirm or correct them on device.

| Game | Store / appId | Candidate recipe (`dependencies` + overrides) | Rationale |
| --- | --- | --- | --- |
| **Mirror's Edge (2008)** | **Steam 17410** | `dependencies: [physx, vcrun2010, d3dx9_43]`; `dllOverrides: { d3dx9_43: native }` | UE3 title that needs legacy PhysX + the 2010 VC runtime + d3dx9; a frequent "won't even launch" case the legacy path doesn't address |
| Bastion | Steam 107100 | `dependencies: [d3dx9_43, vcrun2010]` | XNA/DX9-era title needing d3dx9 |
| Magicka | Steam 42910 | `dependencies: [xact, d3dx9_43, dotnet48]` | XNA + XACT audio |
| Just Cause 2 | Steam 8190 | `dependencies: [d3dx9_43, vcrun2008]` | DX9 + VC 2008 |

A candidate recipe is a JSON object matching `docs/provisioning-pipeline-design.md` §3.1, e.g.:

```json
{
  "schemaVersion": 1,
  "id": "steam-17410",
  "match": { "source": "STEAM", "appId": "17410" },
  "dllOverrides": { "d3dx9_43": "native" },
  "dependencies": ["physx", "vcrun2010", "d3dx9_43"],
  "provenance": { "source": "candidate", "confidence": "experimental", "device": "<your device>" }
}
```

> Note: the built-in verb registry currently seeds `vcrun2015-2022`, `dotnet48`, `openal`,
> `d3dcompiler_47` and `d3dx9_43`. Validating the candidates above will surface which additional
> verbs (`physx`, `vcrun2010`, `vcrun2008`, `xact`) to seed next from winetricks.

## 4. Procedure (per game)

1. Install the game and confirm it appears in the library.
2. Launch with the flag **off**; record the baseline outcome + logcat.
3. Launch with the flag **on**; record the outcome + logcat + the resolved recipe/source.
4. For a migration game: outcomes should match the baseline (no regression).
5. For a candidate game: note whether the recipe changes the outcome (boots vs. doesn't), and capture
   the exact failure if it still fails (missing verb? wrong override? component pin?).
6. **Re-launch test (idempotence + offline):** launch a second time (recipe marker present → engine
   no-ops) and confirm no slowdown or re-install; then enable airplane mode and launch again to
   confirm the cached recipe still applies (offline-safe).

## 5. Reporting back

For each game, report: store/appId, device (SoC/GPU/driver), flag-off vs flag-on outcome, the
resolved recipe, and logcat excerpts. Confirmed recipes can be folded into the migrated catalog or
contributed to the community catalog; refuted ones tell us which verbs/overrides to fix. This is the
feedback loop the opt-in analytics path automates over time.
