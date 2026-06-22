# Declarative Per-Game Provisioning Pipeline — Design

> Status: **Draft / WIP** (opt-in, behind a feature flag). Scope: *compatibility improvement +
> code-quality* (migrates hard-coded per-game fixes to shareable declarative recipes). No
> behaviour change for existing containers when the flag is off.

## 1. Motivation

GameNative already boots PC games on Android using the same open-source stack as comparable
projects (Wine/Proton, DXVK, VKD3D, Box64, FEXCore, Turnip). The difference between "boots" and
"doesn't boot" is almost never the orchestration code — it is the **state of the Wine prefix that
is applied before boot**: which components are pinned, which environment variables and DLL
overrides are set, which registry keys are written, and **which Windows redistributables are
installed** (vcrun, dotnet, d3dx, physx, openal…).

GameNative does part of this already, but in three disconnected pieces:

1. **Component pinning is server-driven and device-aware** via
   [`BestConfigService`](../app/src/main/java/app/gamenative/utils/BestConfigService.kt): it fetches
   a GPU-matched `bestConfig` from `api.gamenative.app/api/best-config`, validates component
   versions against [`manifest.json`](../manifest.json), and installs what is missing through
   [`ManifestInstaller`](../app/src/main/java/app/gamenative/utils/ManifestInstaller.kt). This is
   effectively the *config-download → component-recommend → component-install* shape, but it only
   carries **component versions** (and a few env vars).
2. **Env vars, DLL overrides, registry patches, launch args, INI/file writes, folder cleanups** are
   **hard-coded in Kotlin** as 35 entries in
   [`GameFixesRegistry`](../app/src/main/java/app/gamenative/gamefixes/GameFixesRegistry.kt)
   (e.g. `STEAM_413420` — Danganronpa 2 `d3dx9_43` override; `STEAM_990080` — Hogwarts Legacy
   ProgramData wipe; `STEAM_1962700` — Subnautica 2 `Engine.ini`). They are not shareable, not
   diffable, and require a new app release to change.
3. **Dependency installation is hard-coded and narrow.**
   [`PreInstallSteps`](../app/src/main/java/app/gamenative/utils/PreInstallSteps.kt) only runs
   installers that already ship inside the game's `_CommonRedist` directory (VcRedist, PhysX,
   OpenAL, XNA, GOG ISI, Ubisoft Connect). Wine-Mono is installed unconditionally and
   `installRedistributables()` is incomplete.
   [`LaunchDependencies`](../app/src/main/java/app/gamenative/utils/LaunchDependencies.kt) handles a
   fixed set of download/extract tasks (Proton, Steam assets, EOS overlay, GOG deps). **There is no
   generic winetricks-style verb engine** that can download-and-install an arbitrary redistributable
   that the game does not already bundle.

### The gap, precisely

There is no single, **declarative, versioned, shareable** description of *everything a given game
needs* (pinned components + env + DLL overrides + registry patches + **dependency verbs**),
resolved at launch with clear precedence and device branching, applied **idempotently and
transactionally**, and **offline-safe**. This document specifies that pipeline and how it plugs into
the existing code without disturbing it.

## 2. Existing architecture (verified map)

| Concern | Where | Notes |
| --- | --- | --- |
| Per-game prefix ("container") | `com.winlator.container.Container` (`.java`), `ContainerManager`, `ImageFs` | `.container` JSON at `imagefs/home/xuser-<id>/.container`; id = `STEAM_<appid>`, `GOG_<id>`, `EPIC_<id>`, `CUSTOM_GAME_<id>`. Free-form per-container state via `Container.getExtra/putExtra(extraData: JSONObject)`. |
| UI/transient config | `ContainerData.kt` (~100 fields), `ContainerUtils.{getOrCreateContainer,applyToContainer,toContainerData}` | `applyToContainer` writes fields + D3D registry, then `Container.saveData()`. |
| Env vars | `Container.envVars` (space-escaped string), `com.winlator.core.envvars.EnvVars` | `WINEDLLOVERRIDES` lives inside `envVars`. |
| Registry | `com.winlator.core.WineRegistryEditor` | Atomic (temp-file + rename), already idempotent (upsert-if-absent). Edits `system.reg`/`user.reg`. |
| Component catalog | `manifest.json` → `ManifestData{version,updatedAt,items:Map<type,List<ManifestEntry{id,name,url,variant,arch}>>}`, `ManifestRepository` (GitHub + 24h cache), `ManifestComponentHelper`, `ManifestInstaller`, `com.winlator.contents.{ContentProfile,ContentsManager}` | `ManifestContentTypes`: `DRIVER, DXVK, VKD3D, BOX64, WOWBOX64, FEXCORE, WINE, PROTON`. |
| Server config (3-stage) | `BestConfigService.{fetchBestConfig,validateComponentVersions,parseConfigToContainerData,resolveMissingManifestInstallRequests}` → `ManifestInstaller`; entry at `PluviaMain.preLaunchApp()` & `BaseAppScreen.applyKnownConfigForLibraryItem()` | `BestConfigResponse{bestConfig, matchType, matchedGpu, matchedDeviceId, matchedStore}` — already provides the **device-branching signal**. |
| Hard-coded fixes | `GameFixesRegistry.applyFor(context, appId, container)` at `XServerScreen.kt:3168`; types: `RegistryKeyFix, LaunchArgFix, WineEnvVarFix, IniFileFix, PrefixFileFix, DeleteFolderFix, GOGDependencyFix, KeyedCompositeGameFix` | Keyed by `(GameSource, catalogId)`. `fixesProvider()` is test-injectable. |
| Deps today | `PreInstallSteps` (+ `Marker`/`MarkerUtils` markers in game dir), `LaunchDependencies` (`LaunchDependency{appliesTo,isSatisfied,getLoadingMessage,install}`) | No generic download-and-install verb engine. |
| Launch flow | `MainViewModel.launchApp()` → `preLaunchApp()` → `XServerScreen.setupXEnvironment()` (`:3062`): launcher → `GameFixesRegistry.applyFor()` (`:3168`) → merge `container.envVars` (`:3209`) → PreInstallSteps chain (`:3196/:3327`) → Mono (`:4108`) → `startEnvironmentComponents()` (`:3433`) → `ProcessHelper.exec()` | Bionic/Glibc launcher variants. |
| Global prefs / flags | `app.gamenative.PrefManager` (DataStore `PluviaPreferences`, typed keys, `setPref()`); existing `autoApplyKnownConfig` | Home for the new opt-in flag. |
| Tests | JUnit4 + Robolectric 4.14 + mockk 1.13.5; tasks `:app:testLegacyDebugUnitTest`, `:app:testModernDebugUnitTest`; `testModern/resources/robolectric.properties` pins API 34 | File I/O is **raw `java.io.File`** (no abstraction). Provider-injection idiom already used (`fixesProvider`, `PreInstallSteps.setStepsProviderForTests`). |

## 3. Design

### 3.0 Package layout (new code)

```
app/src/main/java/app/gamenative/provisioning/
  model/        Recipe + sub-models (kotlinx.serialization), schema version
  schema/       RecipeValidator, RecipeSchemaVersion, migration of older schema versions
  engine/       PrefixState (interface) + FilePrefixState + ProvisioningEngine + result/markers
  verbs/        Verb, VerbRegistry, VerbExecutor, seeded verb definitions (verbs.json)
  resolver/     RecipeResolver (3-stage, precedence, device branching, offline cache)
  catalog/      RecipeCatalogSource (EmuReady / open repo), LocalRecipeStore, cache
  migration/    Built-in recipe catalog seeded from the current GameFixesRegistry fixes
app/src/test/java/app/gamenative/provisioning/
  InMemoryPrefixState + unit tests; app/src/test/resources/provisioning/ fixtures
```

All new behaviour is reachable only when the feature flag is on (see §3.6).

### 3.1 Recipe schema (declarative, versioned)

A **recipe** is the per-game unit of provisioning and the **catalog interchange format**. JSON,
modeled with `kotlinx.serialization` (already a project dependency). `schemaVersion` is mandatory;
the validator rejects unknown majors and migrates known older minors.

```jsonc
{
  "schemaVersion": 1,
  "id": "steam-413420",                  // stable recipe id
  "match": {                              // identity & matching rules
    "source": "STEAM",                    // GameSource: STEAM|GOG|EPIC|AMAZON|CUSTOM_GAME
    "appId": "413420",                    // store id (catalogId for EPIC)
    "exeNameContains": null,              // optional secondary match
    "exeSha256": null                     // optional exact match
  },
  "prefixArch": "win64",                  // win32 | win64 (informational; WoW64 already handled)
  "components": {                         // pin by manifest id (see manifest.json)
    "proton": "proton-10.0-arm64ec-2",
    "dxvk": "2.4.1",
    "vkd3d": null, "driver": null, "box64": null, "fexcore": null, "wowbox64": null
  },
  "env": { "DXVK_FRAME_RATE": "60", "BOX64_DYNAREC_BIGBLOCK": "0" },
  "dllOverrides": { "d3dx9_43": "native,builtin", "d3dcompiler_43": "native,builtin" },
  "registry": [
    { "hive": "system", "key": "Software\\Wine\\Direct3D", "name": "csmt", "type": "dword", "value": "0" }
  ],
  "dependencies": ["d3dx9_43", "vcrun2019"],   // winetricks-style verbs (see §3.2)
  "launch": { "args": "--rendering-driver vulkan", "wrapper": null },
  "deviceOverrides": [                    // branch on device profile (reuses BestConfig signals)
    { "when": { "gpuFamily": "adreno6xx" },
      "set": { "components": { "dxvk": "1.10.3" }, "env": { "MESA_VK_VERSION_OVERRIDE": "1.1" } } }
  ],
  "provenance": {                         // facts only — no proprietary payloads
    "source": "community", "confidence": "verified",
    "contributor": "emuready:user/123", "verifiedOn": "2026-06-01", "device": "Snapdragon 8 Gen 2"
  }
}
```

Design points:
- **Components reference `manifest.json` ids** — the recipe never embeds binaries; resolution and
  download reuse `ManifestComponentHelper`/`ManifestInstaller`.
- **`dllOverrides`** is a structured map that the engine merges into `Container.envVars`'
  `WINEDLLOVERRIDES` (rather than the user pasting a raw string), preserving user-set entries.
- **`registry`** patches are applied via `WineRegistryEditor` (already idempotent).
- **`dependencies`** are verb names resolved against the verb registry (§3.2) — this is the missing
  winetricks layer.
- **`deviceOverrides`** are evaluated against a `DeviceProfile` derived from the same signals
  `BestConfigService` already computes (`matchedGpu`/GPU family). No new fingerprinting.
- The schema is a strict superset of what `GameFixesRegistry` can express, which is what makes the
  Phase 5 migration mechanical (§3.5).

### 3.2 Provisioning engine + verb registry (the missing winetricks layer)

**`PrefixState`** — the testability keystone. Because core file I/O is raw `java.io.File`, we
introduce a narrow interface over the operations the engine needs, with two implementations:

```kotlin
interface PrefixState {
    fun fileExists(path: String): Boolean
    fun writeFile(path: String, bytes: ByteArray)
    fun readFile(path: String): ByteArray?
    fun setRegistry(hive: Hive, key: String, name: String, type: RegType, value: String)
    fun getRegistry(hive: Hive, key: String, name: String): String?
    fun getEnv(): MutableMap<String, String>      // backed by EnvVars
    fun setEnv(env: Map<String, String>)
    fun isMarked(marker: String): Boolean          // idempotency markers
    fun mark(marker: String)
}
```
- `FilePrefixState` wraps a real `Container` (rootDir, `WineRegistryEditor`, `EnvVars`, marker files
  in `extraData`/game dir per existing `MarkerUtils` convention).
- `InMemoryPrefixState` (test source set) keeps maps/sets — lets us assert resulting state with **no
  device and no real Wine**, which is what makes the programmatic oracle (and the autonomous loop)
  possible.

**`Verb`** — one winetricks-equivalent redistributable:

```kotlin
interface Verb {
    val name: String                                   // "vcrun2019", "d3dx9_43", "dotnet48"
    fun isSatisfied(state: PrefixState): Boolean       // marker / file probe
    suspend fun install(ctx: VerbContext, state: PrefixState)  // download → extract/run → overrides → mark
}
```
- Verb **definitions are seeded from winetricks as a factual oracle** (download URL(s), file names,
  SHA-256, target DLLs in `system32`/`syswow64`, the override flags, any installer command). The
  *data* lives in `verbs/verbs.json` with `provenance`; the **executor is new Kotlin** built on the
  existing download (`SteamService.fetchFileWithFallback`/manifest downloaders), extraction
  (`commons-compress`/`zstd-jni`), the prefix Wine-command machinery (`ProcessHelper.exec`), and
  `WineRegistryEditor`. We do **not** copy the winetricks bash.
- **Priority verbs** (unblock the most games): `vcrun2005/2008/2010/2012/2013/2015+(2017/2019/2022)`,
  `d3dx9`/`d3dx9_43`, `d3dcompiler_43`/`d3dcompiler_47`, `dotnet48`/`dotnet6/7/8`, `physx`,
  `corefonts`, `openal`, `xact`.

**`ProvisioningEngine.apply(recipe, state, deviceProfile): ProvisioningResult`**
1. Resolve effective recipe = base merged with matching `deviceOverrides`.
2. Compute a **content hash** of the effective recipe; if `state.isMarked("recipe:<hash>")`, **no-op**
   (converged — "do nothing if state hasn't drifted").
3. Otherwise apply in order — **components → env → dllOverrides → registry → dependency verbs** —
   each step **idempotent** (skip if already satisfied) and recorded so a mid-way failure can roll
   back env/override/registry deltas (transactional; addresses the prefix-corruption history).
4. On success, `state.mark("recipe:<hash>")` and persist the resolved recipe for offline reuse.

### 3.3 Launch-time resolver (three stages, device-branched, offline-safe)

`RecipeResolver.resolve(appId, deviceProfile): ResolvedRecipe?` mirrors the documented three-stage
shape and the existing `BestConfig` flow:

1. **Resolve config** — pick a recipe by precedence: **local user override → community catalog
   (EmuReady / open repo, §3.4) → heuristic default** (derive a minimal recipe from the existing
   `BestConfig`/manifest result so the new path is never *worse* than today).
2. **Apply recommended components** — pin via `ManifestComponentHelper`/`ManifestInstaller` (reuses
   the proven path; no duplication).
3. **Install dependencies** — run the recipe's verbs through the engine (§3.2).

Cross-cutting:
- **Device branching** uses the `DeviceProfile` from the same data `BestConfigService` already
  derives (`matchedGpu`, GPU family). `deviceOverrides` refine the recipe per SoC/GPU/driver.
- **Offline-safe, never fail-closed.** The last successfully *resolved* recipe is cached per game
  (in `Container.extraData`). With no network, the resolver applies the cached recipe and boots —
  a game launched once online must relaunch offline. On reconnect it self-heals (re-resolves and
  re-applies on drift). We explicitly do **not** reproduce the "refuse to launch if device lookup
  returns 404" failure mode.

Integration points (all gated by the flag):
- `ContainerUtils.getOrCreateContainer()` / `applyToContainer()` — seed/persist resolved recipe.
- `XServerScreen.setupXEnvironment()` just before `GameFixesRegistry.applyFor()` (`:3168`) and the
  env merge (`:3209`) — apply env/dll/registry from the engine.
- A new `PerGameProvisioningDependency : LaunchDependency` inserted into
  `LaunchDependencies.ensureLaunchDependencies()` — runs the verb installs with the existing
  progress-callback UX.
- `BestConfigService.parseConfigToContainerData()` — feed server config into the heuristic-default
  recipe.

### 3.4 Catalog integration (EmuReady / open recipe repo) + analytics feedback

- A `RecipeCatalogSource` fetches recipes from an open source (EmuReady or a dedicated open recipe
  repo), cached like `ManifestRepository` (TTL + offline fallback). Recipes are seeded from **open
  data only** (EmuReady, publicly-documented config mappings) — *facts*, never binaries.
- The recipe format **is** the interchange format, so contributions round-trip.
- Feedback loop reuses the **existing opt-in analytics** (PostHog, already capturing
  game-launch/close with "container config"): emit `(recipeId, recipeHash, deviceProfile,
  bootOutcome)` so the catalog can rank configs. Strictly opt-in (`PrefManager.usageAnalyticsEnabled`);
  no new fingerprinting.

### 3.5 Migration of hard-coded fixes

Each `GameFix` type maps cleanly to a recipe primitive:

| Fix type | Recipe field |
| --- | --- |
| `WineEnvVarFix` | `env` / `dllOverrides` |
| `RegistryKeyFix` | `registry` |
| `LaunchArgFix` | `launch.args` |
| `IniFileFix` | file write (engine `writeFile`, INI-merge helper) |
| `PrefixFileFix` | file write |
| `DeleteFolderFix` | `cleanup.deletePaths` |
| `GOGDependencyFix` | `dependencies` (GOG-sourced verbs) |
| `KeyedCompositeGameFix` | a single recipe with several fields |

The 35 fixes ship as a **built-in recipe catalog** (`migration/`), parsed and applied by the engine
when the flag is on; the legacy `GameFixesRegistry` path remains as fallback when the flag is off.
This both validates the schema against real cases and removes duplication (a ROADMAP "Now" goal).

### 3.6 Feature flag, retro-compat, transactional safety

- New `PrefManager` boolean `enablePerGameProvisioning` (default **off**). When off, behaviour is
  byte-for-byte the current path; existing containers are untouched.
- Per-recipe idempotency markers live in `Container.extraData` so re-applying converges and a
  config import carries its applied-state with it.
- Engine application is transactional per launch: env/override/registry deltas are journaled and
  rolled back on mid-step failure, so a failed provisioning never leaves a half-written prefix.

## 4. Programmatic oracle (what gates each phase)

Machine-checkable, with printed evidence — the only thing **out** of scope is real boot on hardware
(handed off in `docs/device-validation-protocol.md`):

- **Build**: `./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest` (the exact CI
  command) is green.
- **Unit tests**: schema validation against fixtures; engine idempotency (apply ×2 ⇒ identical
  state, 2nd is a no-op); resolver precedence (user > catalog > default); device branching; offline
  fallback; verb `isSatisfied`/marker logic on `InMemoryPrefixState`.
- **Conformance harness**: apply a recipe to an `InMemoryPrefixState` and assert resulting state
  (overrides set, deps marked installed, env present) — no device, no Wine.
- **Lint/format**: `./gradlew lintKotlin` (kotlinter/ktlint) green.
- **Git/PR**: branch rebased on `upstream/master`, no conflicts; draft PR open.

## 5. Phase plan

0. **Discovery + design** (this doc). ✔
1. **Schema + validator + fixtures** — `model/`, `schema/`, JSON fixtures, unit tests.
2. **Engine + verb registry** — `PrefixState` (+ in-memory), `ProvisioningEngine`, `VerbRegistry`,
   priority verbs seeded from winetricks facts, idempotency/transaction tests, conformance harness.
3. **Launch resolver** — 3-stage resolution, precedence, device branching, offline cache, behind the
   flag; wired into `getOrCreateContainer`/`setupXEnvironment`/`LaunchDependencies`.
4. **Catalog integration** — `RecipeCatalogSource` (EmuReady/open repo) + opt-in analytics feedback.
5. **Migration** — built-in recipe catalog seeded from the 35 `GameFixesRegistry` fixes; legacy
   fallback retained.
6. **PR upstream + handoff** — draft PR; `docs/device-validation-protocol.md` with witness games
   (incl. Mirror's Edge) and the on-device boot procedure.

## 6. Resolved decisions (from discovery open questions)

- **Where recipes are stored**: local override + community catalog file (cached), *not* new
  `manifest.json` fields — keeps the component catalog clean and the recipe format independent.
- **Idempotency**: content-hash marker in `Container.extraData`; engine skips on no-drift.
- **Pins are exact `manifest.json` ids** (no semver constraints) to match the existing resolver.
- **Availability**: missing component ⇒ **lenient** (fall back to default + log), never fail-closed.
- **Engine vs GameFixes**: GameFixes is migrated *into* recipes; the two never both run for a game.
- **Testability**: `PrefixState` interface in main code (no test-only flag pollution), concrete
  in-memory impl in the test source set.
