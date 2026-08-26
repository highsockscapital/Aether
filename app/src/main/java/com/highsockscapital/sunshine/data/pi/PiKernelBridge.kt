package com.highsockscapital.sunshine.data.pi

import com.highsockscapital.sunshine.data.PiExtensionLoadOptions
import com.highsockscapital.sunshine.data.SunshineDiagnosticLogger
import com.highsockscapital.sunshine.data.DiagnosticRedactor
import com.highsockscapital.sunshine.runtime.TermuxGuestFiles
import com.highsockscapital.sunshine.termux.TermuxBashTool
import com.highsockscapital.sunshine.termux.TermuxContract
import android.content.Context
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val PiBridgeAssetPath = "pi-bridge/bridge.mjs"
private val PiBridgeGuestPath = "${TermuxContract.HomeDirectory}/.sunshine/pi-bridge/bridge.mjs"
private val PiBridgeWorkingDirectory = "${TermuxContract.HomeDirectory}/.sunshine/pi-bridge"
private val PiBridgeHomeDirectory = TermuxContract.HomeDirectory
private val PiBridgeLogPath = "$PiBridgeWorkingDirectory/bridge.log"
private val PiBridgeFifoPath = "$PiBridgeWorkingDirectory/bridge.in"
private val PiBridgeExitCodePath = "$PiBridgeWorkingDirectory/bridge.exit"
private val PiBridgeNodePidPath = "$PiBridgeWorkingDirectory/node.pid"
private val PiBridgeVersionMarkerPath = "$PiBridgeWorkingDirectory/bridge.version"
private val PiBridgeStdoutOffsetPath = "$PiBridgeWorkingDirectory/stdout.offset"
private const val PiBridgeNodeMinVersion = "22.19.0"
private const val PiBridgeVersion = "2.0.0-alpha.0"
private const val PiAiVersion = "0.84.1"
private const val PiAgentCoreVersion = "0.84.1"
private const val PiCodingAgentVersion = "0.84.1"
private const val PiBridgeRequestTimeoutMillis = 10 * 60 * 1000L
private const val PiBridgeOAuthTimeoutMillis = 15 * 60 * 1000L
private const val PiBridgePingTimeoutMillis = 15_000L
private const val CancelledRequestRetentionMillis = 5 * 60 * 1000L

private data class PendingPiBridgeRequest(
    val response: CompletableDeferred<PiBridgeFrame>,
    val processGeneration: Long,
    val eventChannel: Channel<PiBridgeFrame>? = null,
    val eventJob: Job? = null,
)

private class TermuxBridgeProcess(
    val runId: String,
    val generation: Long,
) {
    @Volatile
    var exited: Boolean = false

    @Volatile
    var exitCode: Int? = null
}

class PiKernelBridge(
    context: Context,
    private val bashTool: TermuxBashTool,
    private val diagnosticLogger: SunshineDiagnosticLogger = SunshineDiagnosticLogger.NoOp,
) {
    private val guestFiles = TermuxGuestFiles(context.applicationContext, bashTool)
    private val writerMutex = Mutex()
    private val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, PendingPiBridgeRequest>()
    private val cancelledRequestIds = ConcurrentHashMap<String, Long>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processStateLock = Any()
    private val nextProcessGeneration = AtomicLong(0L)
    @Volatile
    private var activeProcess: TermuxBridgeProcess? = null

    suspend fun ping(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
    ): JSONObject =
        request(
            type = "ping",
            timeoutMillis = PiBridgePingTimeoutMillis,
            onSetupProgress = onSetupProgress,
        )

    suspend fun listProviders(startIfNeeded: Boolean = true): JSONObject =
        request(
            type = "list_providers",
            timeoutMillis = PiBridgePingTimeoutMillis,
            startIfNeeded = startIfNeeded,
        )

    suspend fun loginProvider(
        providerConfigId: String,
        providerId: String,
        authMethod: String,
        oauthFlow: String = "",
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "login_provider",
            payload = JSONObject()
                .put("provider_config_id", providerConfigId)
                .put("provider_id", providerId)
                .put("auth_method", authMethod)
                .put("oauth_flow", oauthFlow),
            timeoutMillis = PiBridgeOAuthTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = true,
        )

    suspend fun clearProviderCredential(providerConfigId: String): JSONObject =
        request(
            type = "clear_provider_credential",
            payload = JSONObject().put("provider_config_id", providerConfigId),
            timeoutMillis = PiBridgePingTimeoutMillis,
        )

    suspend fun submitAuthPrompt(
        promptId: String,
        value: String,
        cancelled: Boolean = false,
    ): JSONObject =
        request(
            type = "auth_prompt_result",
            payload = JSONObject()
                .put("prompt_id", promptId)
                .put("value", value)
                .put("cancelled", cancelled),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun completeOnce(
        payload: JSONObject,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): JSONObject =
        request(
            type = "complete_once",
            payload = payload,
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
        )

    suspend fun runTurn(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "run_turn",
            payload = payload,
            timeoutMillis = null,
            onEvent = onEvent,
        )

    suspend fun steer(
        sessionId: String,
        message: JSONObject,
    ): JSONObject =
        request(
            type = "steer",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("message", message),
            timeoutMillis = PiBridgePingTimeoutMillis,
        )

    suspend fun followUp(
        sessionId: String,
        message: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "follow_up",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("message", message),
            timeoutMillis = null,
            onEvent = onEvent,
        )

    suspend fun getSessionState(sessionId: String): JSONObject =
        request(
            type = "get_session_state",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun compactSession(
        sessionId: String,
        customInstructions: String = "",
        sessionPayload: JSONObject = JSONObject(),
    ): JSONObject = request(
        type = "compact_session",
        payload = JSONObject(sessionPayload.toString()).apply {
            put("session_id", sessionId)
            if (customInstructions.isNotBlank()) put("custom_instructions", customInstructions)
        },
        timeoutMillis = null,
        abortOnCancellation = false,
    )

    suspend fun navigateSession(
        sessionId: String,
        entryId: String,
        reset: Boolean = false,
        summarize: Boolean = false,
        customInstructions: String = "",
        sessionPayload: JSONObject = JSONObject(),
    ): JSONObject = request(
        type = "navigate_session",
        payload = JSONObject(sessionPayload.toString()).apply {
            put("session_id", sessionId)
            put("entry_id", entryId)
            put("reset", reset)
            put("summarize", summarize)
            if (customInstructions.isNotBlank()) put("custom_instructions", customInstructions)
        },
        timeoutMillis = null,
        abortOnCancellation = false,
    )

    suspend fun reloadSession(sessionId: String): JSONObject = request(
        type = "reload_session",
        payload = JSONObject().put("session_id", sessionId),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun exportSessionJsonl(sessionId: String): JSONObject = request(
        type = "export_session_jsonl",
        payload = JSONObject().put("session_id", sessionId),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun importSessionJsonl(sessionId: String, jsonl: String): JSONObject = request(
        type = "import_session_jsonl",
        payload = JSONObject().put("session_id", sessionId).put("jsonl", jsonl),
        timeoutMillis = PiBridgePingTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun closeSession(
        sessionId: String,
        sessionFile: String = "",
        deleteFile: Boolean = false,
    ): JSONObject =
        request(
            type = "close_session",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("session_file", sessionFile)
                .put("delete_file", deleteFile),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
            startIfNeeded = deleteFile,
        )

    suspend fun listExtensions(sessionId: String): JSONObject =
        request(
            type = "list_extensions",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadExtensions(sessionId: String): JSONObject =
        request(
            type = "reload_extensions",
            payload = JSONObject().put("session_id", sessionId),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun invokeExtensionCommand(
        sessionId: String,
        command: String,
        args: String = "",
    ): JSONObject =
        request(
            type = "invoke_extension_command",
            payload = JSONObject()
                .put("session_id", sessionId)
                .put("command", command)
                .put("args", args),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun listExtensionPackages(): JSONObject =
        request(
            type = "list_extension_packages",
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun listDiscoveredSkills(
        workspaceDirectory: String = TermuxContract.HomeDirectory + "/.sunshine/workspace",
    ): JSONObject = request(
        type = "list_discovered_skills",
        payload = JSONObject()
            .put("workspace_directory", workspaceDirectory)
            .put("workspace_trusted", true),
        timeoutMillis = PiBridgeRequestTimeoutMillis,
        abortOnCancellation = false,
    )

    suspend fun installExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "install_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun removeExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "remove_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun updateExtensionPackage(
        source: String,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "update_extension_package",
            payload = extensionLoadOptionsPayload(loadOptions).put("source", source),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadAllExtensions(
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
    ): JSONObject =
        request(
            type = "reload_all_extensions",
            payload = extensionLoadOptionsPayload(loadOptions),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            abortOnCancellation = false,
        )

    suspend fun reloadSunshineExtensions(
        context: JSONObject,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "reload_sunshine_extensions",
            payload = extensionLoadOptionsPayload(loadOptions).put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun getSunshineExtensions(
        context: JSONObject,
        loadOptions: PiExtensionLoadOptions = PiExtensionLoadOptions(),
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "get_sunshine_extensions",
            payload = extensionLoadOptionsPayload(loadOptions).put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun invokeSunshineExtensionAction(
        extensionId: String,
        action: String,
        args: JSONObject,
        context: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "invoke_sunshine_extension_action",
            payload = JSONObject()
                .put("extension_id", extensionId)
                .put("action", action)
                .put("args", args)
                .put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun dispatchSunshineExtensionEvent(
        event: String,
        data: JSONObject,
        context: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "dispatch_sunshine_extension_event",
            payload = JSONObject()
                .put("event", event)
                .put("data", data)
                .put("context", context),
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
            abortOnCancellation = false,
        )

    suspend fun subscribeSunshineExtensions(
        onEvent: suspend (String, JSONObject) -> Unit,
    ) {
        request(
            type = "subscribe_sunshine_extensions",
            timeoutMillis = null,
            onEvent = onEvent,
            abortOnCancellation = false,
        )
    }

    suspend fun sendSunshineHostResult(
        callId: String,
        result: JSONObject = JSONObject(),
        error: String = "",
    ): JSONObject =
        request(
            type = "sunshine_host_result",
            payload = JSONObject()
                .put("call_id", callId)
                .put("ok", error.isBlank())
                .put("result", result)
                .put("error", error),
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )

    private fun extensionLoadOptionsPayload(
        loadOptions: PiExtensionLoadOptions,
    ): JSONObject = JSONObject().apply {
        put("disabled_extension_paths", org.json.JSONArray(loadOptions.disabledExtensionPaths.toList()))
        put("disabled_package_sources", org.json.JSONArray(loadOptions.disabledPackageSources.toList()))
    }

    suspend fun sendHostToolResult(payload: JSONObject) {
        request(
            type = "host_tool_result",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendHostToolProgress(payload: JSONObject) {
        request(
            type = "host_tool_progress",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationChunk(payload: JSONObject) {
        request(
            type = "runtime_op_chunk",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationResult(payload: JSONObject) {
        request(
            type = "runtime_op_result",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun sendRuntimeOperationCancel(payload: JSONObject) {
        request(
            type = "runtime_op_cancel",
            payload = payload,
            timeoutMillis = PiBridgePingTimeoutMillis,
            abortOnCancellation = false,
        )
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            eventScope.coroutineContext.cancelChildren()
            pendingRequests.values.forEach { pending ->
                pending.response.completeExceptionally(PiBridgeException("Pi bridge stopped."))
                pending.eventChannel?.close()
            }
            pendingRequests.clear()
            cancelledRequestIds.clear()
            val stoppedProcess = synchronized(processStateLock) {
                activeProcess.also { activeProcess = null }
            }
            stoppedProcess?.let { process ->
                runCatching { killBridgeProcess(process) }
            }
        }
    }

    private suspend fun request(
        type: String,
        payload: JSONObject = JSONObject(),
        timeoutMillis: Long?,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
        abortOnCancellation: Boolean = type == "run_turn" || type == "complete_once" || type == "follow_up",
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
        startIfNeeded: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val id = nextRequestId(type)
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "request_queued",
            requestId = id,
            details = requestDiagnosticDetails(type, payload) + mapOf(
                "timeout_millis" to timeoutMillis,
                "start_if_needed" to startIfNeeded,
            ),
        )
        val response = CompletableDeferred<PiBridgeFrame>()
        val eventChannel = onEvent?.let { Channel<PiBridgeFrame>(Channel.UNLIMITED) }
        val eventJob = if (onEvent != null && eventChannel != null) {
            eventScope.launch {
                for (frame in eventChannel) {
                    if (frame.type == "event") {
                        try {
                            onEvent(frame.event, frame.payload)
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            diagnosticLogger.exception(
                                category = "pi_bridge",
                                event = "event_handler_failed",
                                throwable = throwable,
                                details = mapOf(
                                    "request_id" to frame.id,
                                    "event" to frame.event,
                                ),
                            )
                            response.completeExceptionally(throwable)
                            break
                        }
                    } else {
                        response.complete(frame)
                        break
                    }
                }
            }
        } else {
            null
        }
        val diagnosticDetails = requestDiagnosticDetails(type, payload)
        try {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_start",
                requestId = id,
                details = diagnosticDetails,
            )
            val requestProcess = if (startIfNeeded) {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "ensure_started_begin",
                    requestId = id,
                )
                val process = ensureStartedLocked(onSetupProgress)
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "ensure_started_end",
                    requestId = id,
                    details = mapOf(
                        "process_generation" to process.generation,
                        "process_alive" to !process.exited,
                    ),
                )
                process
            } else {
                currentLiveProcess() ?: return@withContext JSONObject().put("closed", false)
            }
            pendingRequests[id] = PendingPiBridgeRequest(
                response = response,
                processGeneration = requestProcess.generation,
                eventChannel = eventChannel,
                eventJob = eventJob,
            )
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.VerifyingBridge))
            val request = PiBridgeRequest(id = id, type = type, payload = payload)
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_write_start",
                requestId = id,
                details = mapOf(
                    "process_generation" to requestProcess.generation,
                ),
            )
            writeLine(requestProcess, request.toJsonLine())
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_write_end",
                requestId = id,
            )
            val frame = if (timeoutMillis == null) {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "request_await_start",
                    requestId = id,
                    details = mapOf("timeout" to "none"),
                )
                response.await()
            } else {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "request_await_start",
                    requestId = id,
                    details = mapOf("timeout_millis" to timeoutMillis),
                )
                withTimeout(timeoutMillis) { response.await() }
            }
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_response_received",
                requestId = id,
                details = mapOf(
                    "frame_type" to frame.type,
                    "frame_ok" to frame.ok,
                ),
            )
            if (!frame.ok || frame.type == "error") {
                val error = frame.error
                throw PiBridgeException(
                    message = error?.message?.ifBlank { "Pi bridge request failed." }
                        ?: "Pi bridge request failed.",
                    code = error?.code?.ifBlank { "pi_bridge_error" } ?: "pi_bridge_error",
                )
            }
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_end",
                requestId = id,
                details = diagnosticDetails + mapOf("frame_type" to frame.type),
            )
            frame.payload
        } catch (cancellationException: CancellationException) {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "request_cancelled",
                level = "warn",
                requestId = id,
                details = diagnosticDetails,
            )
            if (abortOnCancellation) {
                markRequestCancelled(id)
                withContext(NonCancellable) {
                    runCatching {
                        request(
                            type = "abort",
                            payload = JSONObject()
                                .put("request_id", id)
                                .put("session_id", payload.optString("session_id")),
                            timeoutMillis = PiBridgePingTimeoutMillis,
                            abortOnCancellation = false,
                        )
                    }
                }
            }
            throw cancellationException
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "pi_bridge",
                event = "request_failed",
                throwable = throwable,
                requestId = id,
                details = diagnosticDetails,
            )
            throw throwable
        } finally {
            pendingRequests.remove(id)
            eventChannel?.close()
            eventJob?.cancelAndJoin()
        }
    }

    private suspend fun ensureStartedLocked(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit = {},
    ): TermuxBridgeProcess {
        mutex.withLock {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_started_locked_enter",
                details = mapOf(
                    "current_process_alive" to (currentLiveProcess() != null),
                ),
            )
            currentLiveProcess()?.let {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "ensure_started_locked_reuse",
                    details = mapOf("process_generation" to it.generation),
                )
                return it
            }
            val staleProcess = synchronized(processStateLock) {
                activeProcess.also { activeProcess = null }
            }
            staleProcess?.let { runCatching { killBridgeProcess(it) } }

            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_node_available_start",
            )
            ensureNodeAvailable(onSetupProgress)
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "ensure_node_available_end",
            )
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.PreparingBridge))
            installBridgeIfNeeded()
            onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.StartingBridge))
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "start_managed_process_begin",
                details = mapOf(
                    "command" to "node ${shellQuote(PiBridgeGuestPath)}",
                    "working_directory" to PiBridgeWorkingDirectory,
                ),
            )
            val runId = startTermuxBridgeProcess()
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "start_managed_process_end",
                details = mapOf(
                    "run_id" to runId,
                ),
            )
            val startedProcess = TermuxBridgeProcess(
                runId = runId,
                generation = nextProcessGeneration.incrementAndGet(),
            )
            startLogPoller(startedProcess)
            synchronized(processStateLock) {
                activeProcess = startedProcess
            }
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "process_started",
                details = mapOf(
                    "guest_path" to PiBridgeGuestPath,
                    "bridge_version" to PiBridgeVersion,
                    "pi_ai_version" to PiAiVersion,
                    "pi_agent_core_version" to PiAgentCoreVersion,
                    "pi_coding_agent_version" to PiCodingAgentVersion,
                    "node_version" to (readNodeVersion() ?: "unknown"),
                    "process_generation" to startedProcess.generation,
                ),
            )
            return startedProcess
        }
    }

    private fun currentLiveProcess(): TermuxBridgeProcess? =
        synchronized(processStateLock) {
            activeProcess?.takeIf { !it.exited }
        }

    private suspend fun ensureNodeAvailable(
        onSetupProgress: (PiCoreSetupUpdate) -> Unit,
    ) {
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingRuntime))
        // Termux must already be set up; never install packages silently here.
        val setup = bashTool.inspectSetup()
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "termux_setup_inspected",
            details = mapOf(
                "is_ready" to (setup.issue == com.highsockscapital.sunshine.termux.TermuxSetupIssue.Ready),
                "detail" to setup.detail,
            ),
        )
        if (setup.issue != com.highsockscapital.sunshine.termux.TermuxSetupIssue.Ready) {
            throw PiBridgeException(
                setup.detail.ifBlank {
                    "Set up Termux before starting the agent runtime."
                },
                code = "termux_not_ready",
            )
        }
        onSetupProgress(PiCoreSetupUpdate(PiCoreSetupPhase.CheckingNode))
        val version = readNodeVersion()
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "node_version_checked",
            details = mapOf(
                "version" to version.orEmpty(),
                "min_version" to PiBridgeNodeMinVersion,
            ),
        )
        if (version == null || compareSemver(version, PiBridgeNodeMinVersion) < 0) {
            throw PiBridgeException(
                "Pi bridge requires Node.js >= $PiBridgeNodeMinVersion in Termux. " +
                    "Install it with: pkg install nodejs",
                code = "node_missing_or_too_old",
            )
        }
    }

    private suspend fun readNodeVersion(): String? {
        val raw = guestFiles.execute("node --version", PiBridgeHomeDirectory)
        if (!raw.optBoolean("ok")) return null
        return raw.optString("stdout")
            .lineSequence()
            .firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            ?.removePrefix("v")
    }

    private suspend fun installBridgeIfNeeded() {
        guestFiles.ensureDirectory(PiBridgeWorkingDirectory)
        val currentVersion = runCatching {
            guestFiles.readFileBytes(PiBridgeVersionMarkerPath).decodeToString().trim()
        }.getOrNull()
        val bridgeInstalled = guestFiles.exists(PiBridgeGuestPath)
        if (bridgeInstalled && currentVersion == PiBridgeVersion) return
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "bridge_asset_install_start",
            details = mapOf(
                "installed" to bridgeInstalled,
                "current_version" to currentVersion.orEmpty(),
                "target_version" to PiBridgeVersion,
            ),
        )
        guestFiles.installAsset(PiBridgeAssetPath, PiBridgeGuestPath)
        guestFiles.writeFileBytes(
            PiBridgeVersionMarkerPath,
            PiBridgeVersion.toByteArray(Charsets.UTF_8),
        )
    }

    /** Launches node inside Termux as a managed background run with a FIFO stdin. */
    private suspend fun startTermuxBridgeProcess(): String {
        val launchResult = guestFiles.execute(
            command = buildString {
                append("cd ${TermuxGuestFiles.shellQuote(PiBridgeWorkingDirectory)} && ")
                append("rm -f ${q("bridge.log")} ${q("bridge.in")} ${q("bridge.exit")} ")
                append("${q("node.pid")} ${q("stdout.offset")} && ")
                append("mkfifo ${q("bridge.in")} && ")
                append("( echo $$ > ${q("holder.pid")}; exec sleep infinity ) ")
                append("> ${q("bridge.in")} 2>/dev/null & ")
                append("nohup node ${q(PiBridgeGuestPath)} < ${q("bridge.in")} ")
                append(">> ${q("bridge.log")} 2>&1 & ")
                append("echo $! > ${q("node.pid")} && ")
                append("echo BRIDGE_LAUNCHED")
            },
            workingDirectory = PiBridgeWorkingDirectory,
        )
        require(launchResult.optBoolean("ok")) {
            launchResult.optString("stderr").ifBlank { "Failed to launch the agent runtime." }
        }
        require(launchResult.optString("stdout").contains("BRIDGE_LAUNCHED")) {
            launchResult.optString("stderr").ifBlank { "Failed to launch the agent runtime." }
        }
        // Managed-run id is not strictly needed for polling (we poll files), but
        // registering one lets the user kill the run from the UI if it hangs.
        val runId = "pi-bridge-${System.currentTimeMillis()}"
        return runId
    }

    private suspend fun writeLine(process: TermuxBridgeProcess, line: String) {
        writerMutex.withLock {
            val result = guestFiles.execute(
                command = "printf '%s\\n' ${shellQuote(line)} > ${shellQuote(PiBridgeFifoPath)}",
                workingDirectory = PiBridgeWorkingDirectory,
            )
            if (!result.optBoolean("ok")) {
                failPendingRequests(
                    exitedProcess = process,
                    message = result.optString("stderr").ifBlank {
                        "Couldn't send the request to the agent runtime."
                    },
                )
                throw PiBridgeException(
                    result.optString("stderr").ifBlank { "Couldn't reach the agent runtime." },
                    code = "bridge_write_failed",
                )
            }
        }
    }

    private suspend fun killBridgeProcess(process: TermuxBridgeProcess) {
        process.exited = true
        guestFiles.execute(
            command = "test -f ${q("node.pid")} && kill \$(cat ${q("node.pid")}) 2>/dev/null; " +
                "test -f ${q("holder.pid")} && kill \$(cat ${q("holder.pid")}) 2>/dev/null; true",
            workingDirectory = PiBridgeWorkingDirectory,
        )
    }

    /**
     * Polls the bridge log file for new bytes, feeding complete JSONL frames to
     * [PiJsonlParser]. Detects process exit via the bridge.exit marker file.
     */
    private fun startLogPoller(startedProcess: TermuxBridgeProcess) {
        eventScope.launch {
            var offset = 0L
            val parser = PiJsonlParser(
                onFrame = { frame ->
                    handleFrameFromReader(frame, startedProcess.generation)
                },
                onInvalidLine = { line, throwable ->
                    diagnosticLogger.exception(
                        category = "pi_bridge",
                        event = "invalid_stdout_json",
                        throwable = throwable,
                        details = mapOf(
                            "line" to DiagnosticRedactor.sanitizeString(line.take(700)),
                        ),
                    )
                },
            )
            while (true) {
                try {
                    val stat = guestFiles.execute(
                        command = "wc -c < ${q("bridge.log")} 2>/dev/null; " +
                            "test -f ${q("bridge.exit")} && echo __EXITED__ || true",
                        workingDirectory = PiBridgeWorkingDirectory,
                    )
                    val stdoutLines = stat.optString("stdout").trim().lines()
                    val logSize = stdoutLines.firstOrNull()?.toLongOrNull() ?: 0L
                    val didExit = stdoutLines.any { it.trim() == "__EXITED__" }
                    if (logSize > offset) {
                        val chunkResult = guestFiles.execute(
                            command = "tail -c +${offset + 1} ${q("bridge.log")} | base64 | tr -d '\\n'",
                            workingDirectory = PiBridgeWorkingDirectory,
                        )
                        if (chunkResult.optBoolean("ok")) {
                            val decoded = runCatching {
                                android.util.Base64.decode(
                                    chunkResult.optString("stdout").trim(),
                                    android.util.Base64.DEFAULT,
                                )
                            }.getOrNull()
                            if (decoded != null) {
                                offset += decoded.size.toLong()
                                parser.accept(String(decoded, Charsets.UTF_8))
                                parser.flush()
                            }
                        }
                    } else {
                        parser.flush()
                    }
                    if (didExit) {
                        startedProcess.exited = true
                        val exitCode = guestFiles.execute(
                            command = "cat ${q("bridge.exit")} 2>/dev/null || true",
                            workingDirectory = PiBridgeWorkingDirectory,
                        ).optString("stdout").trim().toIntOrNull()
                        startedProcess.exitCode = exitCode
                        diagnosticLogger.event(
                            category = "pi_bridge",
                            event = "process_exited",
                            level = "warn",
                            details = mapOf(
                                "exit_code" to exitCode,
                                "process_generation" to startedProcess.generation,
                            ),
                        )
                        synchronized(processStateLock) {
                            if (activeProcess?.generation == startedProcess.generation) {
                                activeProcess = null
                            }
                        }
                        failPendingRequests(
                            exitedProcess = startedProcess,
                            message = "Pi bridge process exited.",
                        )
                        break
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    diagnosticLogger.exception(
                        category = "pi_bridge",
                        event = "log_poller_failed",
                        throwable = throwable,
                        details = mapOf("process_generation" to startedProcess.generation),
                    )
                }
                delay(120L)
            }
        }
    }

    private fun handleFrameFromReader(
        frame: PiBridgeFrame,
        processGeneration: Long,
    ) {
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "frame_received",
            requestId = frame.id,
            details = mapOf(
                "frame_type" to frame.type,
                "frame_event" to frame.event,
                "process_generation" to processGeneration,
                "has_pending_request" to (pendingRequests[frame.id] != null),
            ),
        )
        val pending = pendingRequests[frame.id]
        if (pending != null && pending.processGeneration != processGeneration) {
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "frame_generation_mismatch",
                level = "warn",
                requestId = frame.id,
                details = mapOf(
                    "frame_generation" to processGeneration,
                    "request_generation" to pending.processGeneration,
                ),
            )
            return
        }
        when {
            pending != null && pending.eventChannel != null -> {
                if (pending.eventChannel.trySend(frame).isFailure) {
                    diagnosticLogger.event(
                        category = "pi_bridge",
                        event = "event_channel_send_failed",
                        level = "warn",
                        requestId = frame.id,
                        details = mapOf(
                            "frame_type" to frame.type,
                            "frame_event" to frame.event,
                        ),
                    )
                    pending.response.completeExceptionally(
                        PiBridgeException("Pi bridge event queue was closed.", code = "event_queue_closed")
                    )
                }
            }

            pending != null && (frame.type == "response" || frame.type == "error") ->
                pending.response.complete(frame)

            pending != null && frame.type == "event" -> Unit

            pending == null && isRecentlyCancelledRequest(frame.id) -> Unit

            else -> diagnosticLogger.event(
                category = "pi_bridge",
                event = "unknown_frame",
                level = "warn",
                details = mapOf(
                    "frame_type" to frame.type,
                    "request_id" to frame.id,
                ),
            )
        }
    }

    private fun markRequestCancelled(requestId: String) {
        val now = System.currentTimeMillis()
        cancelledRequestIds[requestId] = now
        cancelledRequestIds.forEach { (candidateId, cancelledAt) ->
            if (now - cancelledAt > CancelledRequestRetentionMillis) {
                cancelledRequestIds.remove(candidateId, cancelledAt)
            }
        }
    }

    private fun isRecentlyCancelledRequest(requestId: String): Boolean {
        val cancelledAt = cancelledRequestIds[requestId] ?: return false
        if (System.currentTimeMillis() - cancelledAt <= CancelledRequestRetentionMillis) {
            return true
        }
        cancelledRequestIds.remove(requestId, cancelledAt)
        return false
    }

    private suspend fun failPendingRequests(
        exitedProcess: TermuxBridgeProcess,
        message: String,
    ) {
        diagnosticLogger.event(
            category = "pi_bridge",
            event = "fail_pending_requests_start",
            level = "warn",
            details = mapOf(
                "message" to message,
                "exited_process_generation" to exitedProcess.generation,
                "pending_count" to pendingRequests.size,
            ),
        )
        mutex.withLock {
            val isCurrentProcess = synchronized(processStateLock) {
                activeProcess === exitedProcess
            }
            if (!isCurrentProcess) {
                diagnosticLogger.event(
                    category = "pi_bridge",
                    event = "fail_pending_requests_skip",
                    level = "warn",
                    details = mapOf(
                        "reason" to "not_current_process",
                        "exited_process_generation" to exitedProcess.generation,
                    ),
                )
                return
            }
            pendingRequests.forEach { (requestId, pending) ->
                if (pending.processGeneration != exitedProcess.generation) return@forEach
                if (!pendingRequests.remove(requestId, pending)) return@forEach
                pending.response.completeExceptionally(PiBridgeException(message, code = "bridge_exited"))
                pending.eventChannel?.close()
            }
            synchronized(processStateLock) {
                activeProcess = null
            }
        }
    }

    private fun q(name: String): String =
        TermuxGuestFiles.shellQuote("$PiBridgeWorkingDirectory/$name")

    private fun nextRequestId(type: String): String =
        "${type}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"

    private fun requestDiagnosticDetails(
        type: String,
        payload: JSONObject,
    ): Map<String, Any?> {
        val modelConfig = payload.optJSONObject("model_config")
        return mapOf(
            "request_type" to type,
            "session_id" to payload.optString("session_id"),
            "provider" to modelConfig?.optString("pi_provider_id").orEmpty(),
            "model" to modelConfig?.optString("model_id").orEmpty(),
        ).filterValues(String::isNotBlank)
    }
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun compareSemver(left: String, right: String): Int {
    val leftParts = left.split('.', '-').mapNotNull { it.toIntOrNull() }
    val rightParts = right.split('.', '-').mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(leftParts.size, rightParts.size, 3)
    for (index in 0 until maxSize) {
        val leftValue = leftParts.getOrNull(index) ?: 0
        val rightValue = rightParts.getOrNull(index) ?: 0
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }
    return 0
}
