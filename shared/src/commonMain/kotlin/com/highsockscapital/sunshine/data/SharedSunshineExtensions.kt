package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.runtime.SharedPiBridgeClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SharedSunshineExtensionInfo(
    val id: String,
    val name: String,
    val path: String,
)

data class SharedSunshineExtensionSurface(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val slot: String,
    val order: Int,
    val tree: JsonElement?,
)

data class SharedSunshineExtensionComponent(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val target: String,
    val mode: String,
    val order: Int,
    val tree: JsonElement?,
)

data class SharedSunshineExtensionComposerMenuItem(
    val id: String,
    val localId: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val action: String,
    val args: JsonObject,
    val selected: Boolean,
)

data class SharedSunshineExtensionSettingsPage(
    val id: String,
    val localId: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val trailingIcon: String = "",
    val trailingAction: String = "",
    val trailingCategory: String = "",
    val trailingArgs: JsonObject = JsonObject(emptyMap()),
    val sections: List<JsonObject>,
    val categories: List<SharedSunshineExtensionSettingsCategory> = emptyList(),
)

data class SharedSunshineExtensionSettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val trailingIcon: String = "",
    val trailingAction: String = "",
    val trailingCategory: String = "",
    val trailingArgs: JsonObject = JsonObject(emptyMap()),
    val hidden: Boolean = false,
    val sections: List<JsonObject>,
)

data class SharedSunshineExtensionMessageType(
    val id: String,
    val type: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val icon: String,
)

data class SharedSunshineExtensionCustomMessage(
    val id: String,
    val type: String,
    val extensionId: String,
    val tree: JsonElement?,
)

data class SharedSunshineExtensionError(
    val path: String,
    val extensionId: String,
    val phase: String,
    val message: String,
)

data class SharedSunshineExtensionNotification(
    val message: String,
    val level: String,
)

data class SharedPiExtensionUiRequest(
    val callId: String,
    val method: String,
    val title: String,
    val message: String = "",
    val placeholder: String = "",
    val options: List<String> = emptyList(),
)

data class SharedSunshineExtensionSnapshot(
    val apiVersion: Int = 2,
    val version: Long = 0,
    val extensions: List<SharedSunshineExtensionInfo> = emptyList(),
    val surfaces: List<SharedSunshineExtensionSurface> = emptyList(),
    val components: List<SharedSunshineExtensionComponent> = emptyList(),
    val composerMenuItems: List<SharedSunshineExtensionComposerMenuItem> = emptyList(),
    val settings: List<SharedSunshineExtensionSettingsPage> = emptyList(),
    val messageTypes: List<SharedSunshineExtensionMessageType> = emptyList(),
    val customMessages: List<SharedSunshineExtensionCustomMessage> = emptyList(),
    val eventNames: Set<String> = emptySet(),
    val errors: List<SharedSunshineExtensionError> = emptyList(),
) {
    fun surfacesAt(slot: String): List<SharedSunshineExtensionSurface> =
        surfaces.filter { it.slot == slot }.sortedWith(
            compareBy<SharedSunshineExtensionSurface> { it.order }.thenBy { it.id }
        )

    fun componentsAt(target: String): List<SharedSunshineExtensionComponent> =
        components.filter { it.target == target }.sortedWith(
            compareBy<SharedSunshineExtensionComponent> { it.order }.thenBy { it.id }
        )
}

class SharedSunshineExtensionManager(
    private val bridge: SharedPiBridgeClient,
    private val hostHandler: suspend (String, JsonObject) -> JsonObject,
) {
    private val mutex = Mutex()
    private val uiRequestMutex = Mutex()
    private val pendingUiRequests = ArrayDeque<SharedPiExtensionUiRequest>()
    private val _notifications = MutableSharedFlow<SharedSunshineExtensionNotification>(
        extraBufferCapacity = 8,
    )
    private val _piUiRequest = MutableStateFlow<SharedPiExtensionUiRequest?>(null)
    var snapshot: SharedSunshineExtensionSnapshot = SharedSunshineExtensionSnapshot()
        private set
    var error: String = ""
        private set

    val notifications: SharedFlow<SharedSunshineExtensionNotification> = _notifications.asSharedFlow()
    val piUiRequest: StateFlow<SharedPiExtensionUiRequest?> = _piUiRequest.asStateFlow()

    suspend fun refresh(
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedSunshineExtensionSnapshot = mutex.withLock {
        val response = bridge.getSunshineExtensions(context, ::handleEvent)
        parseResponse(response)
    }

    suspend fun reload(
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedSunshineExtensionSnapshot = mutex.withLock {
        val response = bridge.reloadSunshineExtensions(context, ::handleEvent)
        parseResponse(response)
    }

    suspend fun invokeAction(
        extensionId: String,
        action: String,
        args: JsonObject = JsonObject(emptyMap()),
        context: JsonObject = JsonObject(emptyMap()),
    ): SharedSunshineExtensionSnapshot = mutex.withLock {
        val response = bridge.invokeSunshineExtensionAction(
            extensionId = extensionId,
            action = action,
            args = args,
            context = context,
            onEvent = ::handleEvent,
        )
        parseResponse(response)
    }

    suspend fun dispatchEvent(
        event: String,
        data: JsonObject = JsonObject(emptyMap()),
        context: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        if (event !in snapshot.eventNames) return JsonObject(emptyMap())
        return mutex.withLock {
            val response = bridge.dispatchSunshineExtensionEvent(
                event = event,
                data = data,
                context = context,
                onEvent = ::handleEvent,
            )
            response.objectOrNull("snapshot")?.let {
                snapshot = parseSharedSunshineExtensionSnapshot(it)
            }
            response
        }
    }

    suspend fun subscribe(
        onInvalidated: suspend () -> Unit,
    ) {
        bridge.subscribeSunshineExtensions { event, payload ->
            handleEvent(event, payload)
            if (event == "sunshine_invalidated") onInvalidated()
        }
    }

    suspend fun respondToPiExtensionUiRequest(
        callId: String,
        value: JsonElement?,
    ) {
        val request = uiRequestMutex.withLock {
            val current = _piUiRequest.value
            if (current?.callId != callId) return
            _piUiRequest.value = pendingUiRequests.removeFirstOrNull()
            current
        }
        bridge.sendSunshineHostResult(
            callId = request.callId,
            result = JsonObject(mapOf("value" to (value ?: JsonNull))),
        )
    }

    private suspend fun handleEvent(event: String, payload: JsonObject) {
        when (event) {
            "sunshine_notification" -> _notifications.emit(
                SharedSunshineExtensionNotification(
                    message = payload.string("message"),
                    level = payload.string("level").ifBlank { "info" },
                )
            )
            "sunshine_host_call" -> {
                val callId = payload.string("call_id")
                val method = payload.string("method")
                val args = payload.objectOrNull("args") ?: JsonObject(emptyMap())
                if (method == "pi_extension_notify") {
                    _notifications.emit(
                        SharedSunshineExtensionNotification(
                            message = args.string("message"),
                            level = args.string("type").ifBlank { "info" },
                        )
                    )
                    bridge.sendSunshineHostResult(
                        callId = callId,
                        result = JsonObject(mapOf("notified" to JsonPrimitive(true))),
                    )
                    return
                }
                if (method in SharedPiExtensionInteractiveUiMethods) {
                    val request = SharedPiExtensionUiRequest(
                        callId = callId,
                        method = method,
                        title = args.string("title"),
                        message = args.string("message"),
                        placeholder = args.string("placeholder"),
                        options = (args["options"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { option ->
                                (option as? JsonPrimitive)
                                    ?.contentOrNull
                                    ?.takeIf(String::isNotBlank)
                            },
                    )
                    uiRequestMutex.withLock {
                        if (_piUiRequest.value == null) {
                            _piUiRequest.value = request
                        } else {
                            pendingUiRequests.addLast(request)
                        }
                    }
                    return
                }
                val result = runCatching { hostHandler(method, args) }
                bridge.sendSunshineHostResult(
                    callId = callId,
                    result = result.getOrDefault(JsonObject(emptyMap())),
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    private fun parseResponse(response: JsonObject): SharedSunshineExtensionSnapshot {
        snapshot = parseSharedSunshineExtensionSnapshot(response.objectOrNull("snapshot"))
        error = (response["errors"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("error")?.takeIf(String::isNotBlank) }
            .take(3)
            .joinToString("; ")
        return snapshot
    }
}

private val SharedPiExtensionInteractiveUiMethods = setOf(
    "pi_extension_select",
    "pi_extension_confirm",
    "pi_extension_input",
)

internal fun parseSharedSunshineExtensionSnapshot(
    json: JsonObject?,
): SharedSunshineExtensionSnapshot {
    if (json == null) return SharedSunshineExtensionSnapshot()
    return SharedSunshineExtensionSnapshot(
        apiVersion = json.int("api_version") ?: 2,
        version = json.long("version") ?: 0,
        extensions = json.objects("extensions").map { item ->
            SharedSunshineExtensionInfo(
                id = item.string("id"),
                name = item.string("name"),
                path = item.string("path"),
            )
        },
        surfaces = json.objects("surfaces").map { item ->
            SharedSunshineExtensionSurface(
                id = item.string("id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                slot = item.string("slot"),
                order = item.int("order") ?: 0,
                tree = item["tree"],
            )
        },
        components = json.objects("components").map { item ->
            SharedSunshineExtensionComponent(
                id = item.string("id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                target = item.string("target"),
                mode = item.string("mode").ifBlank { "wrap" },
                order = item.int("order") ?: 0,
                tree = item["tree"],
            )
        },
        composerMenuItems = json.objects("composer_menu_items").map { item ->
            SharedSunshineExtensionComposerMenuItem(
                id = item.string("id"),
                localId = item.string("local_id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                subtitle = item.string("subtitle"),
                icon = item.string("icon").ifBlank { "extension" },
                order = item.int("order") ?: 0,
                action = item.string("action"),
                args = item["args"] as? JsonObject ?: JsonObject(emptyMap()),
                selected = item.boolean("selected"),
            )
        },
        settings = json.objects("settings").map { item ->
            SharedSunshineExtensionSettingsPage(
                id = item.string("id"),
                localId = item.string("local_id"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                subtitle = item.string("subtitle"),
                icon = item.string("icon").ifBlank { "settings" },
                order = item.int("order") ?: 0,
                trailingIcon = item.string("trailing_icon").ifBlank { item.string("trailingIcon") },
                trailingAction = item.string("trailing_action").ifBlank { item.string("trailingAction") },
                trailingCategory = item.string("trailing_category").ifBlank { item.string("trailingCategory") },
                trailingArgs = item["trailing_args"] as? JsonObject
                    ?: item["trailingArgs"] as? JsonObject ?: JsonObject(emptyMap()),
                sections = item.objects("sections"),
                categories = item.objects("categories").map { category ->
                    SharedSunshineExtensionSettingsCategory(
                        id = category.string("id"),
                        title = category.string("title"),
                        subtitle = category.string("subtitle"),
                        icon = category.string("icon").ifBlank { "settings" },
                        order = category.int("order") ?: 0,
                        trailingIcon = category.string("trailing_icon").ifBlank { category.string("trailingIcon") },
                        trailingAction = category.string("trailing_action").ifBlank { category.string("trailingAction") },
                        trailingCategory = category.string("trailing_category").ifBlank { category.string("trailingCategory") },
                        trailingArgs = category["trailing_args"] as? JsonObject
                            ?: category["trailingArgs"] as? JsonObject ?: JsonObject(emptyMap()),
                        hidden = category["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                        sections = category.objects("sections"),
                    )
                }.sortedWith(compareBy<SharedSunshineExtensionSettingsCategory> { it.order }.thenBy { it.id }),
            )
        },
        messageTypes = json.objects("message_types").map { item ->
            SharedSunshineExtensionMessageType(
                id = item.string("id"),
                type = item.string("type"),
                extensionId = item.string("extension_id"),
                extensionName = item.string("extension_name"),
                title = item.string("title"),
                icon = item.string("icon").ifBlank { "extension" },
            )
        },
        customMessages = json.objects("custom_messages").map { item ->
            SharedSunshineExtensionCustomMessage(
                id = item.string("id"),
                type = item.string("type"),
                extensionId = item.string("extension_id"),
                tree = item["tree"],
            )
        },
        eventNames = (json["event_names"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet(),
        errors = json.objects("errors").map { item ->
            SharedSunshineExtensionError(
                path = item.string("path"),
                extensionId = item.string("extension_id"),
                phase = item.string("phase"),
                message = item.string("error"),
            )
        },
    )
}

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? =
    get(name)?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.objects(name: String): List<JsonObject> =
    (get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
