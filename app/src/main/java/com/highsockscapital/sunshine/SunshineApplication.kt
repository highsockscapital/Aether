package com.highsockscapital.sunshine

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.highsockscapital.sunshine.data.AgentExtensionsRepository
import com.highsockscapital.sunshine.data.AlpineChromeController
import com.highsockscapital.sunshine.data.AgentModeController
import com.highsockscapital.sunshine.data.AgentSkillManager
import com.highsockscapital.sunshine.data.SunshineAppExtensionManager
import com.highsockscapital.sunshine.data.SunshineModKernel
import com.highsockscapital.sunshine.data.SunshineDiagnosticLogger
import com.highsockscapital.sunshine.data.SunshineToolExecutor
import com.highsockscapital.sunshine.data.ChatRepository
import com.highsockscapital.sunshine.data.PiExtensionManager
import com.highsockscapital.sunshine.data.PiExtensionStateRepository
import com.highsockscapital.sunshine.data.RootSetupController
import com.highsockscapital.sunshine.data.RuntimeWorkspaceFileBridge
import com.highsockscapital.sunshine.data.ChatStateStore
import com.highsockscapital.sunshine.data.ScheduledTask
import com.highsockscapital.sunshine.data.ScheduledTaskManager
import com.highsockscapital.sunshine.data.ScheduledTaskRepository
import com.highsockscapital.sunshine.data.ScheduledTaskScheduler
import com.highsockscapital.sunshine.data.SessionExecutionManager
import com.highsockscapital.sunshine.data.SettingsRepository
import com.highsockscapital.sunshine.data.WorkspaceFileBridge
import com.highsockscapital.sunshine.data.pi.PiCompletionClient
import com.highsockscapital.sunshine.data.pi.PiAgentRunner
import com.highsockscapital.sunshine.data.pi.PiKernelBridge
import com.highsockscapital.sunshine.mod.SunshineNativeModManager
import com.highsockscapital.sunshine.runtime.AlpineRuntime
import com.highsockscapital.sunshine.runtime.RuntimeRouter
import com.highsockscapital.sunshine.runtime.TermuxRuntime
import com.highsockscapital.sunshine.termux.TermuxBashTool
import com.highsockscapital.sunshine.termux.TermuxRuntimeOperations
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SunshineApplication : Application() {
    val runtime: SunshineAppRuntime by lazy(LazyThreadSafetyMode.NONE) {
        SunshineAppRuntime(this)
    }
    @Volatile
    private var isPostHogInitialized = false

    override fun onCreate() {
        super.onCreate()
        runtime.initialize()
    }

    override fun onTerminate() {
        runtime.nativeModManager.shutdown()
        super.onTerminate()
    }

    fun initializePostHog() {
        if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
        synchronized(this) {
            if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
            val config = PostHogAndroidConfig(
                apiKey = BuildConfig.POSTHOG_API_KEY,
                host = BuildConfig.POSTHOG_HOST,
            ).apply {
                captureApplicationLifecycleEvents = true
                captureDeepLinks = true
                captureScreenViews = true
                debug = BuildConfig.DEBUG
                releaseIdentifier = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                errorTrackingConfig.autoCapture = true
                errorTrackingConfig.inAppIncludes.addAll(
                    listOf(
                        "com.highsockscapital.sunshine",
                        "com.highsockscapital.sunshine",
                    ),
                )
            }
            PostHogAndroid.setup(this, config)
            isPostHogInitialized = true
        }
    }
}

class SunshineAppRuntime(
    private val application: SunshineApplication,
) {
    val diagnosticLogger = SunshineDiagnosticLogger(application)
    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                diagnosticLogger.exception(
                    category = "coroutine",
                    event = "uncaught_exception",
                    throwable = throwable,
                )
            }
    )

    val settingsRepository = SettingsRepository(application)
    val piExtensionStateRepository = PiExtensionStateRepository(application)
    val modKernel = SunshineModKernel()
    val chatRepository = ChatRepository(application)
    val extensionsRepository = AgentExtensionsRepository(application)
    val scheduledTaskRepository = ScheduledTaskRepository(application)
    val bashTool = TermuxBashTool(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val termuxRuntime = TermuxRuntime(bashTool)
    val alpineRuntime = AlpineRuntime(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val piKernelBridge = PiKernelBridge(
        alpineRuntime = alpineRuntime,
        diagnosticLogger = diagnosticLogger,
    )
    val nativeModManager = SunshineNativeModManager(
        context = application,
        application = application,
        alpineRuntime = alpineRuntime,
        piKernelBridge = piKernelBridge,
        kernel = modKernel,
        piExtensionStateRepository = piExtensionStateRepository,
        diagnosticLogger = diagnosticLogger,
    )
    val piCompletionClient = PiCompletionClient(
        bridge = piKernelBridge,
        settingsRepository = settingsRepository,
    )
    val runtimeRouter = RuntimeRouter(
        termuxRuntime = termuxRuntime,
        alpineRuntime = alpineRuntime,
    )
    val rootSetupController = RootSetupController(
        context = application,
        bashTool = bashTool,
        diagnosticLogger = diagnosticLogger,
    )
    val workspaceFileBridge = WorkspaceFileBridge(
        context = application,
        bashTool = bashTool,
    )
    val runtimeWorkspaceFileBridge = RuntimeWorkspaceFileBridge(
        context = application,
        runtimeRouter = runtimeRouter,
        alpineRuntime = alpineRuntime,
        termuxFileBridge = workspaceFileBridge,
    )
    val agentModeController = AgentModeController(
        context = application,
        bashTool = bashTool,
        runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
        diagnosticLogger = diagnosticLogger,
    )
    val alpineChromeController = AlpineChromeController(
        context = application,
        alpineRuntime = alpineRuntime,
        diagnosticLogger = diagnosticLogger,
    )
    val skillManager = AgentSkillManager(
        context = application,
        extensionsRepository = extensionsRepository,
    )
    val piExtensionManager = PiExtensionManager(
        context = application,
        alpineRuntime = alpineRuntime,
        piKernelBridge = piKernelBridge,
        skillManager = skillManager,
        stateRepository = piExtensionStateRepository,
    )
    val sunshineAppExtensionManager = SunshineAppExtensionManager(
        bridge = piKernelBridge,
        scope = appScope,
        diagnosticLogger = diagnosticLogger,
        modKernel = modKernel,
        loadOptionsProvider = piExtensionStateRepository::loadOptions,
    )
    val piAgentRunner = PiAgentRunner(
        bridge = piKernelBridge,
        settingsRepository = settingsRepository,
        piExtensionStateRepository = piExtensionStateRepository,
        appExtensionManager = sunshineAppExtensionManager,
        alpineChromeController = alpineChromeController,
        termuxRuntimeOperations = TermuxRuntimeOperations(bashTool),
        diagnosticLogger = diagnosticLogger,
        toolExecutor = SunshineToolExecutor(
            runtimeRouter = runtimeRouter,
            agentModeController = agentModeController,
        ),
    )
    val appForegroundTracker = AppForegroundTracker()
    val notificationController = SunshineNotificationController(application)
    val scheduledTaskScheduler = ScheduledTaskScheduler(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val scheduledTaskManager = ScheduledTaskManager(
        repository = scheduledTaskRepository,
        scheduler = scheduledTaskScheduler,
    )
    val chatStateStore = ChatStateStore(
        scope = appScope,
        chatRepository = chatRepository,
    )
    val sessionExecutionManager = SessionExecutionManager(
        application = application,
        scope = appScope,
        settingsRepository = settingsRepository,
        extensionsRepository = extensionsRepository,
        chatStateStore = chatStateStore,
        chatRepository = chatRepository,
        bashTool = bashTool,
        runtimeRouter = runtimeRouter,
        workspaceFileBridge = workspaceFileBridge,
        rootSetupController = rootSetupController,
        agentModeController = agentModeController,
        skillManager = skillManager,
        scheduledTaskManager = scheduledTaskManager,
        notificationController = notificationController,
        appForegroundTracker = appForegroundTracker,
        diagnosticLogger = diagnosticLogger,
        piCompletionClient = piCompletionClient,
        piKernelBridge = piKernelBridge,
        piAgentRunner = piAgentRunner,
    )

    fun initialize() {
        diagnosticLogger.installUncaughtExceptionHandler()
        diagnosticLogger.event(
            category = "app",
            event = "startup",
            details = mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "debug" to BuildConfig.DEBUG,
            ),
        )
        notificationController.ensureChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundTracker)
        appScope.launch {
            nativeModManager.initialize()
        }
        appScope.launch {
            alpineRuntime.refreshApkRepositoriesForCurrentNetwork()
        }
        appScope.launch {
            settingsRepository.migrateLegacyProvidersToPi()
        }
        appScope.launch {
            if (settingsRepository.settings.first().privacyPolicyAccepted) {
                initializePostHog()
            }
        }
        appScope.launch {
            scheduledTaskManager.rescheduleAll()
        }
    }

    fun initializePostHog() {
        application.initializePostHog()
    }

    fun handleScheduledTaskAlarm(
        taskId: String,
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        diagnosticLogger.event(
            category = "scheduled_task",
            event = "alarm_received",
            details = mapOf("task_id" to taskId),
        )
        appScope.launch {
            try {
                val task = scheduledTaskManager.markTriggeredAndScheduleNext(taskId)
                if (task == null) {
                    diagnosticLogger.event(
                        category = "scheduled_task",
                        event = "trigger_missing_task",
                        level = "warn",
                        details = mapOf("task_id" to taskId),
                    )
                    return@launch
                }
                val started = startScheduledTaskFromAlarm(task)
                diagnosticLogger.event(
                    category = "scheduled_task",
                    event = if (started) "trigger_started" else "trigger_skipped",
                    level = if (started) "info" else "warn",
                    details = mapOf("task_id" to taskId),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun startScheduledTaskFromAlarm(task: ScheduledTask): Boolean {
        return try {
            if (settingsRepository.settings.first().keepTasksRunningInBackground) {
                runCatching {
                    SunshineForegroundService.ensureRunning(application)
                }.onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "scheduled_task",
                        event = "foreground_service_start_failed",
                        throwable = throwable,
                        details = mapOf("task_id" to task.id),
                    )
                }
            }
            sessionExecutionManager.startScheduledTask(task)
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "scheduled_task",
                event = "trigger_start_failed",
                throwable = throwable,
                details = mapOf("task_id" to task.id),
            )
            false
        }
    }

    fun rescheduleScheduledTasks(
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        appScope.launch {
            try {
                scheduledTaskManager.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class AppForegroundTracker : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}

val Context.sunshineRuntime: SunshineAppRuntime
    get() = (applicationContext as SunshineApplication).runtime
