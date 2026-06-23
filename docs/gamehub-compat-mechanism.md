# What makes GameHub's compatibility better — and how it's ported to GameNative

> Clean-room analysis of XiaoJi **GameHub** (`com.xiaoji.egggame`) and its open re-publication via the
> **GameHub Lite / BannerHub** catalog (`landscape-api-*.vgabc.com` → `bannerhub-api.the412banner.workers.dev`),
> to understand and reimplement what makes GameHub run many Windows games that a vanilla prefix won't.
> Only facts (which dependency/component/env a game needs, and the algorithm) are reused — no GameHub
> code is copied and no proprietary binaries are bundled (see `THIRD_PARTY_NOTICES`).

## TL;DR — the "secret sauce" is not per-game recipes

GameHub does **not** carry a big per-game recipe database (its catalog has ~70 per-game config-file
patches; per-game *dependency* lists are not declared — see below). Its superior compatibility comes from
**the state of the Wine prefix before boot**, on four axes:

1. **A default prefix provisioned with the common Windows runtimes.** GameHub's 114 "dependency
   components" are exactly the **winetricks verb set** (`vcrun2005`–`2022`, `d3dx9`, `d3dcompiler_*`,
   `dotnet20`–`dotnet48`, `physx`, `xact`, `xaudio2.7`, `xinput`, `dmusic`/`directshow`/`quartz`,
   `gdiplus`, `mono`, `oalinst`, `msxml`, `xna31/40`, …). The common ones are baked into its 173 MB
   `imagefs`; more are installed on demand. **This is the single biggest factor.**
2. **Box64 / FEX execution tuning.** `BOX64_DYNAREC=1, BIGBLOCK=2, STRONGMEM=0, WEAKBARRIER=1,
   SAFEFLAGS=0, FASTROUND/FASTNAN=1, NATIVEFLAGS=1`; `FEX TSOEnabled=1, HalfBarrierTSOEnabled=1,
   Multiblock=1, VectorTSO=0`.
3. **Device-aware component selection** — Wine/Proton build, DXVK, VKD3D, Box64/FEX, GPU driver chosen
   per device (Adreno vs generic) and per category from `getAllComponentList` (575 components) +
   `getDefaultComponent`.
4. **Stock Wine/Proton** — no Wine source patches; compatibility is from orchestration + the above, not
   a magic Wine fork.

### Why Mirror's Edge (Steam 17410, UE3 + PhysX) boots under GameHub

Through the **default path**, not a per-game entry. A UE3/PhysX game launched in GameHub lands in a
default container whose prefix has **PhysX + d3dx9 + the VC++ runtimes** present and the Box64/FEX
tuning applied — so it boots. A vanilla GameNative prefix lacks these, so the same game fails before
the renderer initializes. The fix is to **provision the prefix with the same baseline**.

## Catalog model (verified live)

`bannerhub-api.the412banner.workers.dev/v6/simulator/v2/…`:
- `getAllComponentList` → **575 components**: `type` 1=translator(Box64/FEX, 38), 2=GPU driver(292),
  3=DXVK(51), 4=VKD3D(9), 5=game-patch(70), 6=dependency(114), 8=steam-client. Each an
  `EnvLayerEntity{id,name,version,downloadUrl,fileMd5,type,…}`.
- `getDefaultComponent` → recommended dxvk/vkd3d/steamClient/container per category.
- `getContainerList` → 10 Wine/Proton builds (wine9.5–10.6, proton9/10/11, x64 + arm64ec).
- `getImagefsDetail` → the base firmware/imagefs (the pre-provisioned rootfs).
- Per-game data: the 70 type-5 patches are config-file overlays dropped into `drive_c/users/<user>/…`;
  there is **no per-game dependency declaration** — GameHub installs deps just-in-time at launch via
  `IEmuContainer.installDependency(name, …)` (a winetricks reimplementation).

## What is ported into GameNative (this work)

All behind the opt-in `PrefManager.enablePerGameProvisioning` flag; zero regression when off.

| GameHub element | GameNative port |
| --- | --- |
| 114 dependency components = winetricks verbs | **`gamehub-verbs.json`** — 99 verbs seeded from winetricks (download URLs, SHA-256, guest install commands, DLL overrides), loaded into the verb registry. |
| Default-prefix common runtimes | **`gamehub-baseline.json`** recipe — installs VC++ 2005-2022, d3dx9/d3dcompiler, .NET 4.8, PhysX, XAct, XNA, dsound, gdiplus, openal, applied under every game. |
| Box64/FEX tuning defaults | the baseline recipe's `env` (BOX64_DYNAREC_* / FEXCORE_*), applied set-if-absent at launch. |
| 70 per-game config patches | **`gamehub-recipes.json`** — 19 per-game config-file recipes (the ones with meaningful, Steam-matched configs), path-normalized to GameNative's `xuser`. |
| Device branching (Adreno/generic) | the recipe `deviceOverrides` + `DeviceProfile`; resolver applies the matching overlay. |
| 3-stage launch (config → components → deps) | the `RecipeResolver` (precedence: user > GameHub > migrated) + `ProvisioningEngine` (idempotent, transactional) at the `XServerScreen` pre-launch hook. |
| Just-in-time dependency install (`IEmuContainer.installDependency`) | **`ProvisioningDepsStep`** — a `PreInstallStep` that downloads the resolved installer verbs (VC++ 2005-2022, PhysX, .NET 4.8, XNA) from their official vendors (SHA-256-verified) into `C:\.gnprov\<verb>\` and runs them silently in the Wine guest via the existing pre-install chain. |

## Steam DRM (the *other* boot blocker — e.g. Mirror's Edge)

Runtime provisioning does **not** fix Steam-DRM games. A Steam-DRM-wrapped exe shows **"Application
load error 3:0000065432"** when it can't reach a Steam client to validate ownership. This is
orthogonal to VC++/PhysX/.NET.

How GameHub solves it: it ships a full **real Steam client** (an 11 MB Rust reimplementation,
`libsteamkit_core.so`) that logs into the user's real account and serves genuine ownership tickets —
out of scope to replicate. But **GameNative already bundles the open-source answer**: the Goldberg
`steam_api` emulator (`assets/steampipe/`), a cold-client `steamclient` loader, `generateInterfacesFile`,
`ensureSteamSettings`, and Steamless. It exposes three launch paths in `MainViewModel.launchApp`
(real-Steam → restore Valve DLLs; `useLegacyDRM` → Goldberg `steam_api`; default → cold-client
`steamclient`). The missing piece was simply that **none of it is keyed by appid** — a fresh game
defaults to the cold-client path, and for a 2008 title like Mirror's Edge the stub survives.

This work adds that appid-keying declaratively: a recipe's optional **`steamDrm`** block
(`SteamDrmStrategy` = `legacy_goldberg` | `cold_client` | `real_steam` | `auto`, plus `unpack`) is
applied by `PerGameProvisioning` to the container's *existing* DRM toggles
(`setUseLegacyDRM`/`setUnpackFiles`/`setLaunchRealSteam`). It selects an already-shipped mechanism —
nothing new is bundled, no third-party code is copied (it mirrors only the *semantics* of GameHub's
per-game `fakeSteamClient`/`offlineMode` flags). It is set once per container (respecting later manual
changes) and re-applied immediately by the "Re-apply provisioning" menu action.

The Mirror's Edge recipe (`gamehub-recipes.json`, appid 17410) selects `legacy_goldberg` + `unpack` —
the investigation's leading hypothesis for its 2008 SteamStub. **This needs on-device confirmation**:
which exact mode boots a given DRM title is a fact to validate on hardware and then encode in the
recipe.

## How the dependency install works (the boot-maker)

GameHub bakes the common Windows runtimes into its base `imagefs`. GameNative ships no such image, so
`ProvisioningDepsStep` provisions the prefix **at launch** instead, reusing the same pre-install chain
that already installs a game's bundled VC++/PhysX redists:

1. **Resolve** — baseline deps (always) + the per-game recipe's deps, de-duplicated. Only verbs whose
   payload is a directly-runnable installer (`.exe`/`.msi`) are run here (`ProvisioningInstallers.INSTALL_FLAGS`);
   DLL-drop verbs (d3dx9, d3dcompiler, xact) are handled declaratively, not by a guest installer.
2. **Download** — each redistributable from its official vendor URL via the app's own `SteamService.fetchFile`,
   SHA-256-verified, staged once per prefix under `drive_c/.gnprov/<verb>/` (so VC++ isn't re-downloaded per
   game). Each download is bounded by a 180 s timeout; a stuck transfer is dropped, never wedging launch.
3. **Install** — the staged installers run silently in the guest (`/q`, `/install /quiet /norestart`,
   PhysX `/s`, `msiexec /i` for MSIs), chained through `chainPreInstallSteps` (markers, `wineserver -k`
   between steps, "Installing prerequisites…" splash).
4. **Idempotent + recoverable** — a per-game marker means each game provisions at most once; if *any* verb
   fails to download/stage, the marker is **withheld** so the whole step retries next launch (cached
   downloads make the retry cheap). "Verify Files" clears the marker to force a clean re-provision.

Fully opt-in (`PrefManager.enablePerGameProvisioning`, default off): when off, `appliesTo` returns false and
there is zero behavior change.

## What still needs on-device validation (human handoff)

The pipeline compiles, unit-tests green, and is wired end-to-end, but the **silent installs + the resulting
boot** can only be confirmed on hardware: the per-verb silent flags are winetricks-faithful facts but are
best-effort on Wine/Box64, and a few runtimes (legacy .NET full installers — `dotnet40/45/46` — deliberately
excluded from auto-run for this reason) are historically fragile under emulation. The Box64/FEX tuning and the
config-file recipes apply declaratively and need no install step.

Concretely, to validate Mirror's Edge (Steam 17410): enable the flag, launch it, and confirm the baseline
installs PhysX + VC++ into the container (watch the "Installing prerequisites…" splash, then check
`drive_c/.gnprov/` and the game's marker) and the game boots — versus flag-off.
