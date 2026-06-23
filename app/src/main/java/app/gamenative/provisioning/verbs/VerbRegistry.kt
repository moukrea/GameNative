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
         * The built-in registry = the full GameHub/winetricks dependency catalog (~99 verbs)
         * plus the hand-curated definitions, which take precedence for the few verbs where we
         * ship explicit binary placement (e.g. d3dcompiler_47).
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

    /** Microsoft .NET Framework 4.8 (offline installer). */
    val DOTNET48 = VerbDefinition(
        name = "dotnet48",
        description = "Microsoft .NET Framework 4.8 offline installer",
        downloads = listOf(
            VerbDownload("https://go.microsoft.com/fwlink/?linkid=2088631", "dotnet48-offline.exe"),
        ),
        dllOverrides = mapOf("mscoree" to "native"),
        installerCommand = "wine dotnet48-offline.exe /sfxlang:1027 /q /norestart",
        provenance = WINETRICKS,
    )

    /** OpenAL runtime (oalinst). */
    val OPENAL = VerbDefinition(
        name = "openal",
        description = "OpenAL runtime (oalinst)",
        downloads = listOf(
            VerbDownload("https://openal.org/downloads/oalinst.zip", "oalinst.zip"),
        ),
        installerCommand = "wine oalinst.exe /s",
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

    // The VC++ 2015-2022 runtimes are binary-compatible and ship as one redist, so the older
    // verb names are aliases of the same definition.
    val VCRUN2015 = VCRUN2022.copy(name = "vcrun2015")
    val VCRUN2017 = VCRUN2022.copy(name = "vcrun2017")
    val VCRUN2019 = VCRUN2022.copy(name = "vcrun2019")

    val DEFINITIONS: List<VerbDefinition> = listOf(
        VCRUN2022,
        VCRUN2019,
        VCRUN2017,
        VCRUN2015,
        DOTNET48,
        OPENAL,
        D3DCOMPILER_47,
        D3DX9_43,
    )
}
