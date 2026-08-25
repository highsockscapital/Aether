package com.highsockscapital.sunshine.mod

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import com.highsockscapital.sunshine.data.SunshineDiagnosticLogger
import com.highsockscapital.sunshine.data.SunshineModKernel
import com.highsockscapital.sunshine.data.SunshineModOperationDecision
import com.highsockscapital.sunshine.data.SunshineModOperationInterceptor
import com.highsockscapital.sunshine.data.SunshineModServiceHandler
import com.highsockscapital.sunshine.data.SunshineModServiceMethod
import com.highsockscapital.sunshine.data.PiExtensionStateRepository
import com.highsockscapital.sunshine.data.pi.PiKernelBridge
import com.highsockscapital.sunshine.runtime.AlpineRuntime
import com.highsockscapital.sunshine.ui.SunshineUiState
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val SunshineNativeExtensionGuestDirectory = "/root/.sunshine/extensions"
private const val NativeModPreferences = "sunshine_native_mods"
private const val PreferenceStartupInProgress = "startup_in_progress"
private const val PreferenceSafeMode = "safe_mode"
private const val PreferenceLastLoadingMod = "last_loading_mod"
private const val PreferenceFailedModIds = "failed_mod_ids"
private const val NativeModUiStableDelayMillis = 5_000L
private const val SunshineNativeApiVersion = 1

enum class SunshineNativeComponentMode {
    Before,
    After,
    Replace,
    Wrap,
    Hide,
}

fun interface SunshineNativeHost {
    suspend fun invoke(
        method: String,
        args: JSONObject,
    ): JSONObject
}

data class SunshineNativeComponentContext(
    val target: String,
    val uiState: SunshineUiState,
    val publicState: JSONObject,
    val host: SunshineNativeHost,
)

interface SunshineNativeComponentRenderer {
    @SuppressLint("ComposableNaming")
    @Composable
    fun render(
        context: SunshineNativeComponentContext,
        next: @Composable () -> Unit,
    )
}

data class SunshineNativeComponentRegistration(
    val target: String,
    val id: String,
    val owner: String,
    val mode: SunshineNativeComponentMode,
    val priority: Int,
    val sequence: Long,
    val renderer: SunshineNativeComponentRenderer?,
)

data class SunshineNativeToolTitleRegistration(
    val toolName: String,
    val runningTitle: String,
    val completedTitle: String,
    val owner: String,
    val priority: Int,
    val sequence: Long,
)

class SunshineNativeToolTitleRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val _registrations = MutableStateFlow<List<SunshineNativeToolTitleRegistration>>(emptyList())
    val registrations: StateFlow<List<SunshineNativeToolTitleRegistration>> = _registrations.asStateFlow()

    fun register(
        toolName: String,
        runningTitle: String,
        completedTitle: String,
        owner: String,
        priority: Int = 100,
    ): () -> Unit {
        val registration = SunshineNativeToolTitleRegistration(
            toolName = toolName.trim().also { require(it.isNotBlank()) { "Native tool title requires a tool name." } },
            runningTitle = runningTitle.trim().also { require(it.isNotBlank()) { "Native tool title requires a running title." } },
            completedTitle = completedTitle.trim().also { require(it.isNotBlank()) { "Native tool title requires a completed title." } },
            owner = owner.trim().ifBlank { "unknown" },
            priority = priority,
            sequence = sequence.incrementAndGet(),
        )
        synchronized(lock) { _registrations.value = _registrations.value + registration }
        return { synchronized(lock) { _registrations.value = _registrations.value - registration } }
    }

    fun titleFor(toolName: String, running: Boolean): String? = _registrations.value
        .filter { it.toolName.equals(toolName, ignoreCase = true) }
        .maxWithOrNull(compareBy<SunshineNativeToolTitleRegistration> { it.priority }.thenBy { it.sequence })
        ?.let { if (running) it.runningTitle else it.completedTitle }

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            _registrations.value = _registrations.value.filterNot { it.owner == owner }
        }
    }
}

class SunshineNativeComponentRegistry {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val _registrations =
        MutableStateFlow<List<SunshineNativeComponentRegistration>>(emptyList())

    val registrations: StateFlow<List<SunshineNativeComponentRegistration>> =
        _registrations.asStateFlow()

    fun register(
        target: String,
        id: String,
        owner: String,
        mode: SunshineNativeComponentMode = SunshineNativeComponentMode.After,
        priority: Int = 0,
        renderer: SunshineNativeComponentRenderer? = null,
    ): () -> Unit {
        val normalizedTarget = target.trim()
        val normalizedId = id.trim()
        require(normalizedTarget.isNotBlank()) {
            "Sunshine native components require a target."
        }
        require(normalizedId.isNotBlank()) {
            "Sunshine native components require an id."
        }
        require(mode == SunshineNativeComponentMode.Hide || renderer != null) {
            "Sunshine native component $normalizedId requires a renderer for mode $mode."
        }
        val registration = SunshineNativeComponentRegistration(
            target = normalizedTarget,
            id = normalizedId,
            owner = owner.trim().ifBlank { "unknown" },
            mode = mode,
            priority = priority,
            sequence = sequence.incrementAndGet(),
            renderer = renderer,
        )
        synchronized(lock) {
            _registrations.value = _registrations.value + registration
        }
        return {
            synchronized(lock) {
                _registrations.value = _registrations.value - registration
            }
        }
    }

    fun componentsAt(target: String): List<SunshineNativeComponentRegistration> =
        _registrations.value
            .filter { it.target == target }
            .sortedWith(
                compareBy<SunshineNativeComponentRegistration> { it.priority }
                    .thenBy { it.sequence }
            )

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            _registrations.value = _registrations.value.filterNot { it.owner == owner }
        }
    }
}

interface SunshineNativeMod {
    fun onLoad(context: SunshineNativeModContext)

    fun onUnload() = Unit
}

class SunshineNativeModContext internal constructor(
    val application: Application,
    val modId: String,
    val packageRoot: File,
    val classLoader: ClassLoader,
    val kernel: SunshineModKernel,
    val diagnosticLogger: SunshineDiagnosticLogger,
) {
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    fun registerService(
        id: String,
        description: String = "",
        priority: Int = 100,
        methods: List<SunshineModServiceMethod>,
        handler: SunshineModServiceHandler,
    ): () -> Unit = track(
        kernel.services.register(
            id = id,
            owner = modId,
            description = description,
            priority = priority,
            methods = methods,
            handler = handler,
        )
    )

    fun intercept(
        operation: String,
        priority: Int = 100,
        interceptor: SunshineModOperationInterceptor,
    ): () -> Unit = track(
        kernel.operations.register(
            operation = operation,
            owner = modId,
            priority = priority,
            interceptor = interceptor,
        )
    )

    fun registerComponent(
        target: String,
        id: String,
        mode: SunshineNativeComponentMode = SunshineNativeComponentMode.After,
        priority: Int = 100,
        renderer: SunshineNativeComponentRenderer? = null,
    ): () -> Unit = track(
        kernel.components.register(
            target = target,
            id = id,
            owner = modId,
            mode = mode,
            priority = priority,
            renderer = renderer,
        )
    )

    fun registerToolTitle(
        toolName: String,
        runningTitle: String,
        completedTitle: String,
        priority: Int = 100,
    ): () -> Unit = track(
        kernel.toolTitles.register(toolName, runningTitle, completedTitle, modId, priority)
    )

    fun packageFile(relativePath: String): File =
        File(packageRoot, relativePath).canonicalFile

    fun log(
        event: String,
        details: Map<String, Any?> = emptyMap(),
        level: String = "info",
    ) {
        diagnosticLogger.event(
            category = "native_mod",
            event = event,
            level = level,
            details = details + ("mod_id" to modId),
        )
    }

    internal fun rollback() {
        cleanups.asReversed().forEach { cleanup ->
            runCatching(cleanup)
        }
        cleanups.clear()
        kernel.services.unregisterOwner(modId)
        kernel.operations.unregisterOwner(modId)
        kernel.components.unregisterOwner(modId)
        kernel.toolTitles.unregisterOwner(modId)
    }

    private fun track(cleanup: () -> Unit): () -> Unit {
        cleanups += cleanup
        return {
            if (cleanups.remove(cleanup)) {
                cleanup()
            }
        }
    }
}

data class SunshineNativeModDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val packagePath: String,
    val entrypoints: List<String>,
)

data class SunshineLoadedNativeMod(
    val id: String,
    val entrypoint: String,
)

data class SunshineNativeModFailure(
    val id: String,
    val entrypoint: String = "",
    val message: String,
)

data class SunshineNativeModState(
    val isInitializing: Boolean = false,
    val safeModeActive: Boolean = false,
    val restartRequired: Boolean = false,
    val suspectedCrashModId: String = "",
    val discovered: List<SunshineNativeModDescriptor> = emptyList(),
    val loaded: List<SunshineLoadedNativeMod> = emptyList(),
    val failures: List<SunshineNativeModFailure> = emptyList(),
)

internal data class SunshineNativeModManifest(
    val descriptor: SunshineNativeModDescriptor,
    val packageRoot: File,
    val classpath: List<File>,
    val libraryPaths: List<File>,
)

@SuppressLint("ApplySharedPref")
class SunshineNativeModManager(
    context: Context,
    private val application: Application,
    private val alpineRuntime: AlpineRuntime,
    private val piKernelBridge: PiKernelBridge,
    private val kernel: SunshineModKernel,
    private val piExtensionStateRepository: PiExtensionStateRepository,
    private val diagnosticLogger: SunshineDiagnosticLogger,
) {
    private data class LoadedEntrypoint(
        val instance: SunshineNativeMod,
        val context: SunshineNativeModContext,
        val classLoader: ClassLoader,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        NativeModPreferences,
        Context.MODE_PRIVATE,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val loadedEntrypoints = CopyOnWriteArrayList<LoadedEntrypoint>()
    private val _state = MutableStateFlow(SunshineNativeModState())
    private val lock = Any()
    private var didInitialize = false
    private var initializationCompleted = false
    private var uiStable = false

    val state: StateFlow<SunshineNativeModState> = _state.asStateFlow()

    suspend fun initialize() {
        synchronized(lock) {
            if (didInitialize) return
            didInitialize = true
        }
        val importedDiscovery = discoverNativeMods(includeInstalledPackages = false)
        val previousStartupWasInterrupted =
            preferences.getBoolean(PreferenceStartupInProgress, false)
        val suspectedModId = preferences.getString(PreferenceLastLoadingMod, "").orEmpty()
        val previousFailedModIds = preferences
            .getStringSet(PreferenceFailedModIds, emptySet())
            .orEmpty()
        val safeModeActive = preferences.getBoolean(PreferenceSafeMode, false) ||
            previousStartupWasInterrupted
        if (previousStartupWasInterrupted) {
            preferences.edit()
                .putBoolean(PreferenceSafeMode, true)
                .putBoolean(PreferenceStartupInProgress, false)
                .apply()
        }
        if (safeModeActive) {
            _state.value = SunshineNativeModState(
                safeModeActive = true,
                suspectedCrashModId = suspectedModId,
                discovered = importedDiscovery.manifests.map(SunshineNativeModManifest::descriptor),
                failures = (
                    importedDiscovery.failures +
                        previousFailedModIds.map { id ->
                            SunshineNativeModFailure(
                                id = id,
                                message = "Failed during the previous Native Mod startup.",
                            )
                        }
                    ).distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
            )
            diagnosticLogger.event(
                category = "native_mod",
                event = "safe_mode_active",
                level = "warn",
                details = mapOf(
                    "suspected_mod_id" to suspectedModId,
                    "discovered_count" to importedDiscovery.manifests.size,
                ),
            )
            val completeDiscovery = discoverNativeMods()
            _state.value = _state.value.copy(
                discovered = completeDiscovery.manifests.map(SunshineNativeModManifest::descriptor),
                failures = (
                    completeDiscovery.failures +
                        previousFailedModIds.map { id ->
                            SunshineNativeModFailure(
                                id = id,
                                message = "Failed during the previous Native Mod startup.",
                            )
                        }
                    ).distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
            )
            markInitializationCompleted()
            return
        }

        var startupGuardActive = false
        val loaded = mutableListOf<SunshineLoadedNativeMod>()
        val failures = importedDiscovery.failures.toMutableList()
        fun startLoading(manifests: List<SunshineNativeModManifest>) {
            if (manifests.isEmpty()) return
            if (!startupGuardActive) {
                synchronized(lock) {
                    initializationCompleted = false
                }
                preferences.edit()
                    .putBoolean(PreferenceStartupInProgress, true)
                    .putString(PreferenceLastLoadingMod, "")
                    .commit()
                startupGuardActive = true
            }
            _state.value = SunshineNativeModState(
                isInitializing = true,
                discovered = manifests.map(SunshineNativeModManifest::descriptor),
                loaded = loaded.toList(),
                failures = failures.toList(),
            )
        }
        fun finishLoadingPhase() {
            if (!startupGuardActive) return
            startupGuardActive = false
            markInitializationCompleted()
        }

        startLoading(importedDiscovery.manifests)
        importedDiscovery.manifests.forEach { manifest ->
            loadManifest(manifest, loaded, failures)
        }
        if (importedDiscovery.manifests.isNotEmpty()) {
            _state.value = _state.value.copy(
                loaded = loaded.toList(),
                failures = failures.toList(),
            )
            finishLoadingPhase()
        }

        val completeDiscovery = discoverNativeMods()
        val importedPaths = importedDiscovery.manifests
            .map { it.packageRoot.canonicalPath }
            .toSet()
        val additionalManifests = completeDiscovery.manifests.filter { manifest ->
            manifest.packageRoot.canonicalPath !in importedPaths
        }
        failures += completeDiscovery.failures
        if (additionalManifests.isNotEmpty()) {
            startLoading(completeDiscovery.manifests)
        }
        additionalManifests.forEach { manifest ->
            loadManifest(manifest, loaded, failures)
        }
        preferences.edit()
            .putStringSet(
                PreferenceFailedModIds,
                failures.map(SunshineNativeModFailure::id).toSet(),
            )
            .apply()
        _state.value = SunshineNativeModState(
            discovered = completeDiscovery.manifests.map(SunshineNativeModManifest::descriptor),
            loaded = loaded,
            failures = failures.distinctBy { "${it.id}:${it.entrypoint}:${it.message}" },
        )
        finishLoadingPhase()
        markInitializationCompleted()
    }

    fun notifyUiStable() {
        handler.removeCallbacksAndMessages(this)
        handler.postAtTime(
            {
                synchronized(lock) {
                    uiStable = true
                }
                maybeClearStartupGuard()
            },
            this,
            android.os.SystemClock.uptimeMillis() + NativeModUiStableDelayMillis,
        )
    }

    fun allowNativeModsOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, false)
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
        _state.value = _state.value.copy(
            safeModeActive = false,
            restartRequired = true,
            suspectedCrashModId = "",
        )
    }

    suspend fun refreshDiscovery() {
        val discovery = discoverNativeMods()
        val descriptors = discovery.manifests.map(SunshineNativeModManifest::descriptor)
        val current = _state.value
        val changed = current.discovered != descriptors
        _state.value = current.copy(
            discovered = descriptors,
            restartRequired = current.restartRequired || (didInitialize && changed),
            failures = (current.failures + discovery.failures)
                .distinctBy { failure ->
                    "${failure.id}:${failure.entrypoint}:${failure.message}"
                },
        )
    }

    fun requestDisableOnNextStart() {
        preferences.edit()
            .putBoolean(PreferenceSafeMode, true)
            .putBoolean(PreferenceStartupInProgress, false)
            .apply()
        _state.value = _state.value.copy(
            restartRequired = true,
        )
    }

    fun shutdown() {
        loadedEntrypoints.asReversed().forEach { loaded ->
            runCatching { loaded.instance.onUnload() }
                .onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "native_mod",
                        event = "unload_failed",
                        throwable = throwable,
                        details = mapOf(
                            "mod_id" to loaded.context.modId,
                        ),
                    )
                }
            loaded.context.rollback()
        }
        loadedEntrypoints.clear()
    }

    private fun loadManifest(
        manifest: SunshineNativeModManifest,
        loaded: MutableList<SunshineLoadedNativeMod>,
        failures: MutableList<SunshineNativeModFailure>,
    ) {
        val classLoader = runCatching {
            val optimizedDirectory = File(
                appContext.codeCacheDir,
                "sunshine-native-mods/${sanitizeId(manifest.descriptor.id)}",
            ).apply {
                require(mkdirs() || isDirectory) {
                    "Unable to create native mod code cache."
                }
            }
            DexClassLoader(
                manifest.classpath.joinToString(File.pathSeparator) { it.path },
                optimizedDirectory.path,
                manifest.libraryPaths
                    .takeIf(List<File>::isNotEmpty)
                    ?.joinToString(File.pathSeparator) { it.path },
                appContext.classLoader,
            )
        }.getOrElse { throwable ->
            failures += SunshineNativeModFailure(
                id = manifest.descriptor.id,
                message = throwable.message ?: throwable.javaClass.name,
            )
            diagnosticLogger.exception(
                category = "native_mod",
                event = "classloader_failed",
                throwable = throwable,
                details = mapOf("mod_id" to manifest.descriptor.id),
            )
            return
        }

        manifest.descriptor.entrypoints.forEach { entrypoint ->
            val ownerId = "${manifest.descriptor.id}:$entrypoint"
            preferences.edit()
                .putString(PreferenceLastLoadingMod, ownerId)
                .commit()
            val nativeContext = SunshineNativeModContext(
                application = application,
                modId = ownerId,
                packageRoot = manifest.packageRoot,
                classLoader = classLoader,
                kernel = kernel,
                diagnosticLogger = diagnosticLogger,
            )
            runCatching {
                val entrypointClass = Class.forName(entrypoint, true, classLoader)
                val instance = instantiateNativeMod(entrypointClass)
                require(instance is SunshineNativeMod) {
                    "$entrypoint does not implement ${SunshineNativeMod::class.java.name}."
                }
                instance.onLoad(nativeContext)
                loadedEntrypoints += LoadedEntrypoint(
                    instance = instance,
                    context = nativeContext,
                    classLoader = classLoader,
                )
                loaded += SunshineLoadedNativeMod(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                )
                diagnosticLogger.event(
                    category = "native_mod",
                    event = "loaded",
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }.onFailure { throwable ->
                nativeContext.rollback()
                failures += SunshineNativeModFailure(
                    id = manifest.descriptor.id,
                    entrypoint = entrypoint,
                    message = throwable.message ?: throwable.javaClass.name,
                )
                diagnosticLogger.exception(
                    category = "native_mod",
                    event = "load_failed",
                    throwable = throwable,
                    details = mapOf(
                        "mod_id" to manifest.descriptor.id,
                        "entrypoint" to entrypoint,
                    ),
                )
            }
        }
    }

    private fun markInitializationCompleted() {
        synchronized(lock) {
            initializationCompleted = true
        }
        maybeClearStartupGuard()
    }

    private fun maybeClearStartupGuard() {
        val shouldClear = synchronized(lock) {
            initializationCompleted && uiStable
        }
        if (!shouldClear) return
        preferences.edit()
            .putBoolean(PreferenceStartupInProgress, false)
            .putString(PreferenceLastLoadingMod, "")
            .apply()
    }

    private suspend fun discoverNativeMods(
        includeInstalledPackages: Boolean = true,
    ): NativeModDiscovery {
        val loadOptions = piExtensionStateRepository.loadOptions()
        val disabledPaths = loadOptions.disabledExtensionPaths
        val root = alpineRuntime.resolveManagedGuestPath(
            SunshineNativeExtensionGuestDirectory
        )
        val manifests = mutableListOf<SunshineNativeModManifest>()
        val failures = mutableListOf<SunshineNativeModFailure>()
        val importedPackageRoots = root
            .takeIf(File::isDirectory)
            ?.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
        val installedPackageRoots = if (includeInstalledPackages) runCatching {
            val packages = piKernelBridge.listExtensionPackages().optJSONArray("packages")
                ?: return@runCatching emptyList<File>()
            buildList {
                for (index in 0 until packages.length()) {
                    val item = packages.optJSONObject(index) ?: continue
                    val source = item.optString("source").trim()
                    if (source in loadOptions.disabledPackageSources) continue
                    if (item.optInt("native_entrypoint_count") <= 0) continue
                    val installedPath = item.optString("installed_path").trim()
                    if (installedPath.isBlank()) continue
                    val packageRoot = alpineRuntime.resolveGuestPath(installedPath)
                    if (packageRoot.isDirectory) add(packageRoot)
                }
            }
        }.onFailure { throwable ->
            diagnosticLogger.exception(
                category = "native_mod",
                event = "package_discovery_failed",
                throwable = throwable,
                level = "warn",
            )
        }.getOrDefault(emptyList()) else emptyList()
        (importedPackageRoots + installedPackageRoots)
            .distinctBy { it.canonicalPath }
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { packageRoot ->
                val guestPath = "$SunshineNativeExtensionGuestDirectory/${packageRoot.name}"
                if (guestPath in disabledPaths) return@forEach
                val manifestFile = File(packageRoot, "package.json")
                if (!manifestFile.isFile) return@forEach
                runCatching {
                    parseSunshineNativeModManifest(
                        packageRoot = packageRoot,
                        manifest = JSONObject(manifestFile.readText(Charsets.UTF_8)),
                    )
                }.onSuccess { manifest ->
                    if (manifest != null) manifests += manifest
                }.onFailure { throwable ->
                    failures += SunshineNativeModFailure(
                        id = packageRoot.name,
                        message = throwable.message ?: throwable.javaClass.name,
                    )
                    diagnosticLogger.exception(
                        category = "native_mod",
                        event = "manifest_failed",
                        throwable = throwable,
                        details = mapOf("package_path" to packageRoot.path),
                    )
                }
            }
        return NativeModDiscovery(
            manifests = manifests,
            failures = failures,
        )
    }
}

private data class NativeModDiscovery(
    val manifests: List<SunshineNativeModManifest> = emptyList(),
    val failures: List<SunshineNativeModFailure> = emptyList(),
)

internal fun parseSunshineNativeModManifest(
    packageRoot: File,
    manifest: JSONObject,
): SunshineNativeModManifest? {
    val native = manifest
        .optJSONObject("sunshine")
        ?.optJSONObject("native")
        ?: return null
    if (!native.optBoolean("enabled", true)) return null

    val packageCanonical = packageRoot.canonicalFile
    val id = manifest.optString("name").trim().ifBlank { packageCanonical.name }
    native.opt("api")
        .takeUnless { it == null || it == JSONObject.NULL }
        ?.let { configured ->
            requireSunshineApiCompatibility(
                configured = configured,
                currentVersion = SunshineNativeApiVersion,
                label = "Native mod $id",
            )
        }
    val entrypoints = native.stringList("entrypoints")
        .ifEmpty { native.stringList("entrypoint") }
    require(entrypoints.isNotEmpty()) {
        "Native mod $id must declare sunshine.native.entrypoints."
    }
    val classpath = native.stringList("classpath").map { path ->
        resolvePackagePath(packageCanonical, path).also { file ->
            require(file.isFile) {
                "Native mod classpath does not exist: $path"
            }
        }
    }
    require(classpath.isNotEmpty()) {
        "Native mod $id must declare sunshine.native.classpath."
    }
    val libraryPaths = (
        native.stringList("libraryPath") +
            native.stringList("library_path")
        )
        .distinct()
        .map { path ->
            resolvePackagePath(packageCanonical, path).also { file ->
                require(file.isDirectory) {
                    "Native mod library path does not exist: $path"
                }
            }
        }
    return SunshineNativeModManifest(
        descriptor = SunshineNativeModDescriptor(
            id = id,
            name = manifest.optString("displayName").trim().ifBlank { id },
            version = manifest.optString("version"),
            packagePath = packageCanonical.path,
            entrypoints = entrypoints,
        ),
        packageRoot = packageCanonical,
        classpath = classpath,
        libraryPaths = libraryPaths,
    )
}

internal fun requireSunshineApiCompatibility(
    configured: Any,
    currentVersion: Int,
    label: String,
) {
    val exactVersion = (configured as? Number)?.toInt()
    if (exactVersion != null) {
        require(exactVersion > 0 && exactVersion.toDouble() == configured.toDouble()) {
            "$label api must be a positive integer or an API range object."
        }
        require(exactVersion == currentVersion) {
            "$label requires API $exactVersion, but this runtime provides $currentVersion."
        }
        return
    }
    require(configured is JSONObject) {
        "$label api must be a positive integer or an API range object."
    }
    val minimum = configured.optPositiveApiVersion("min", label)
    val maximum = configured.optPositiveApiVersion("max", label)
    require(minimum != null || maximum != null) {
        "$label api must declare min, max, or both."
    }
    require(minimum == null || maximum == null || minimum <= maximum) {
        "$label api.min cannot be greater than api.max."
    }
    require(minimum == null || currentVersion >= minimum) {
        "$label requires API $minimum or newer, but this runtime provides $currentVersion."
    }
    require(
        maximum == null ||
            currentVersion <= maximum ||
            configured.optBoolean("allowNewer", false)
    ) {
        "$label supports API through $maximum, but this runtime provides $currentVersion."
    }
}

private fun JSONObject.optPositiveApiVersion(
    key: String,
    label: String,
): Int? {
    if (!has(key)) return null
    val value = opt(key)
    val number = value as? Number
    require(
        number != null &&
            number.toInt() > 0 &&
            number.toInt().toDouble() == number.toDouble()
    ) {
        "$label api.$key must be a positive integer."
    }
    return number.toInt()
}

private fun JSONObject.stringList(key: String): List<String> = when (
    val value = opt(key)
) {
    is JSONArray -> buildList {
        for (index in 0 until value.length()) {
            value.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    is String -> value.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    else -> emptyList()
}

private fun resolvePackagePath(
    packageRoot: File,
    path: String,
): File {
    val file = File(packageRoot, path).canonicalFile
    require(
        file.path == packageRoot.path ||
            file.path.startsWith(packageRoot.path + File.separator)
    ) {
        "Native mod path escaped its package: $path"
    }
    return file
}

private fun instantiateNativeMod(entrypointClass: Class<*>): Any {
    runCatching {
        entrypointClass.getField("INSTANCE").get(null)
    }.getOrNull()?.let { return it }
    return entrypointClass.getDeclaredConstructor().apply {
        isAccessible = true
    }.newInstance()
}

private fun sanitizeId(value: String): String =
    value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "native-mod" }

fun SunshineNativeModContext.cancelOperation(
    payload: JSONObject,
    reason: String = "",
): SunshineModOperationDecision = SunshineModOperationDecision(
    payload = payload,
    cancelled = true,
    reason = reason,
)
