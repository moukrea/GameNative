package app.gamenative.provisioning.verbs

import app.gamenative.provisioning.model.Provenance

/**
 * Registry of available dependency verbs.
 *
 * Built-in definitions are seeded from the winetricks project, used as a factual oracle for *what*
 * each redistributable is and *where* it comes from. Only references are stored: the redistributables
 * themselves are downloaded at runtime from their official vendors and are never bundled or
 * redistributed by GameNative. `sha256` is left null where a verified hash is not yet recorded;
 * the catalog layer may pin hashes later.
 */
class VerbRegistry private constructor(private val verbs: Map<String, Verb>) {

    fun get(name: String): Verb? = verbs[name]

    fun has(name: String): Boolean = verbs.containsKey(name)

    fun names(): Set<String> = verbs.keys

    companion object {
        fun fromDefinitions(definitions: List<VerbDefinition>): VerbRegistry =
            VerbRegistry(definitions.associate { it.name to DataDrivenVerb(it) })

        /**
         * The built-in registry = the full GameHub/winetricks dependency catalog (~99 verbs, with
         * SHA-256-pinned official downloads) plus the hand-curated definitions, which take
         * precedence ONLY for the few verbs where we ship explicit DLL placement (d3dcompiler_47,
         * d3dx9_43). The catalog entries win for everything else, so installer verbs keep their
         * pinned hashes and direct vendor URLs.
         */
        fun builtin(): VerbRegistry {
            val merged = LinkedHashMap<String, VerbDefinition>()
            GameHubVerbCatalog.definitions.forEach { merged[it.name] = it }
            BuiltinVerbs.DEFINITIONS.forEach { merged[it.name] = it }
            return fromDefinitions(merged.values.toList())
        }
    }
}

/**
 * Built-in, winetricks-seeded verb definitions covering the redistributables that unblock the most
 * games. Extending the catalog is purely additive: append a [VerbDefinition].
 */
object BuiltinVerbs {

    private val WINETRICKS = Provenance(source = "winetricks", confidence = "reported")

    /** Visual C++ 2015–2022 runtimes (x86 + x64), the single most common dependency. */
    val VCRUN2022 = VerbDefinition(
        name = "vcrun2022",
        description = "Microsoft Visual C++ 2015-2022 runtime (x86 + x64)",
        downloads = listOf(
            VerbDownload("https://aka.ms/vs/17/release/vc_redist.x86.exe", "vc_redist.x86.exe"),
            VerbDownload("https://aka.ms/vs/17/release/vc_redist.x64.exe", "vc_redist.x64.exe"),
        ),
        installerCommand = "wine vc_redist.x86.exe /quiet /norestart & wine vc_redist.x64.exe /quiet /norestart",
        provenance = WINETRICKS,
    )

    /** d3dcompiler_47.dll — required by many D3D11 games and by DXVK's HLSL path. */
    val D3DCOMPILER_47 = VerbDefinition(
        name = "d3dcompiler_47",
        description = "Direct3D HLSL compiler d3dcompiler_47.dll (native)",
        downloads = listOf(
            VerbDownload(
                "https://github.com/mozilla/fxc2/raw/master/dll/d3dcompiler_47_32.dll",
                "d3dcompiler_47_32.dll",
            ),
            VerbDownload(
                "https://github.com/mozilla/fxc2/raw/master/dll/d3dcompiler_47.dll",
                "d3dcompiler_47_64.dll",
            ),
        ),
        placeFiles = listOf(
            PlacedFile(fromFile = "d3dcompiler_47_32.dll", toPrefixPath = "windows/syswow64/d3dcompiler_47.dll"),
            PlacedFile(fromFile = "d3dcompiler_47_64.dll", toPrefixPath = "windows/system32/d3dcompiler_47.dll"),
        ),
        dllOverrides = mapOf("d3dcompiler_47" to "native"),
        provenance = WINETRICKS,
    )

    /** Legacy d3dx9 (d3dx9_43) shader helper, extracted from the DirectX June 2010 redist. */
    val D3DX9_43 = VerbDefinition(
        name = "d3dx9_43",
        description = "Legacy Direct3D 9 helper d3dx9_43.dll, from the DirectX June 2010 redist",
        downloads = listOf(
            VerbDownload(
                "https://download.microsoft.com/download/8/4/A/84A35BF1-DAFE-4AE8-82AF-AD2AE20B6B14/directx_Jun2010_redist.exe",
                "directx_Jun2010_redist.exe",
            ),
        ),
        placeFiles = listOf(
            PlacedFile(
                fromFile = "directx_Jun2010_redist.exe",
                archiveEntry = "DXSETUP/APR2007_d3dx9_33_x86.cab/d3dx9_43.dll",
                toPrefixPath = "windows/syswow64/d3dx9_43.dll",
            ),
        ),
        dllOverrides = mapOf("d3dx9_43" to "native"),
        provenance = WINETRICKS,
    )

    // Only the DLL-placement verbs override the catalog: they materialize specific native DLLs the
    // catalog (which is override-only for these) does not. Installer verbs (vcrun*, dotnet48,
    // openal, physx, xna*) deliberately resolve to the SHA-256-pinned gamehub-verbs.json entries.
    // VCRUN2022 is retained for unit tests but is intentionally NOT registered here.
    val DEFINITIONS: List<VerbDefinition> = listOf(
        D3DCOMPILER_47,
        D3DX9_43,
    )
}
