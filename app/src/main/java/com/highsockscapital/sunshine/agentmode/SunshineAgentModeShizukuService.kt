package com.highsockscapital.sunshine.agentmode

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

private const val VirtualDisplayFlagPublic = 1 shl 0
private const val VirtualDisplayFlagOwnContentOnly = 1 shl 3
private const val VirtualDisplayFlagSupportsTouch = 1 shl 6
private const val VirtualDisplayFlagDestroyContentOnRemoval = 1 shl 8
private const val VirtualDisplayFlagTrusted = 1 shl 10
private const val VirtualDisplayFlagTouchFeedbackDisabled = 1 shl 13
private const val VirtualDisplayFlagOwnFocus = 1 shl 14
private const val VirtualDisplayFlagStealTopFocusDisabled = 1 shl 16

private const val InjectInputEventModeWaitForFinish = 2
private const val TapDurationMillis = 60L
private const val KeyPressDurationMillis = 30L
private const val ShellPackageName = "com.android.shell"
private const val SystemPackageName = "android"

class SunshineAgentModeShizukuService @Keep constructor(
    private val context: Context,
) : ISunshineAgentModeService.Stub() {
    constructor() : this(resolveLegacyUserServiceContext())

    private val privilegedContext: Context by lazy { contextForCurrentProcess(context) }
    private val displayManager: DisplayManager by lazy {
        privilegedContext.getSystemService<DisplayManager>()!!
    }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val imageReaders = ConcurrentHashMap<Int, ImageReader>()
    private val previewSurfaces = ConcurrentHashMap<Int, Surface>()
    private val displayLocks = ConcurrentHashMap<Int, Any>()
    private val displaysWithLaunchedContent = ConcurrentHashMap.newKeySet<Int>()

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): Int {
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        displayLocks[displayId] = Any()
        return displayId
    }

    override fun createOwnedDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
    ): Int {
        val reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2,
        )
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            reader.surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        imageReaders[displayId] = reader
        displayLocks[displayId] = Any()
        return displayId
    }

    override fun attachPreviewSurface(displayId: Int, surface: Surface) {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Sunshine Agent Mode.")
        synchronized(displayLock(displayId)) {
            previewSurfaces[displayId] = surface
            display.setSurface(surface)
        }
    }

    override fun detachPreviewSurface(displayId: Int) {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Sunshine Agent Mode.")
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            display.setSurface(imageReaders[displayId]?.surface)
        }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            displays.remove(displayId)?.release()
            imageReaders.remove(displayId)?.close()
            displaysWithLaunchedContent.remove(displayId)
            displayLocks.remove(displayId)
        }
    }

    override fun destroy() {
        displays.keys.toList().forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
        }
        previewSurfaces.clear()
        imageReaders.clear()
        displaysWithLaunchedContent.clear()
        displayLocks.clear()
        System.exit(0)
    }

    override fun launchPackage(packageName: String, displayId: Int) {
        val intent = privilegedContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launchable activity for $packageName.")
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.launchDisplayId = displayId
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        val targetDisplay = displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val displayContext = privilegedContext.createDisplayContext(targetDisplay)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getActivity(
            displayContext,
            intent.hashCode(),
            intent,
            flags,
        ).send(
            privilegedContext,
            0,
            null,
            null,
            null,
            null,
            options.toBundle(),
        )
        displaysWithLaunchedContent.add(displayId)
    }

    override fun runInputCommand(command: String) {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(output.ifBlank { "Input command failed with exit code $exitCode." })
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int) {
        ensureManagedDisplay(displayId)
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        SystemClock.sleep(TapDurationMillis)
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat(),
        )
    }

    override fun swipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ) {
        ensureManagedDisplay(displayId)
        val duration = durationMs.coerceIn(50, 10_000)
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())

        val steps = (duration / 16).coerceIn(3, 80)
        for (step in 1 until steps) {
            val progress = step.toFloat() / steps.toFloat()
            val x = x1 + ((x2 - x1) * progress)
            val y = y1 + ((y2 - y1) * progress)
            SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1L))
            injectMotionEvent(
                displayId,
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                x,
                y,
            )
        }

        SystemClock.sleep((duration / steps).toLong().coerceAtLeast(1L))
        injectMotionEvent(
            displayId,
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x2.toFloat(),
            y2.toFloat(),
        )
    }

    override fun key(displayId: Int, keyCode: String) {
        ensureManagedDisplay(displayId)
        val code = parseKeyCode(keyCode)
        val downTime = SystemClock.uptimeMillis()
        injectKeyEvent(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, code, 0)
        SystemClock.sleep(KeyPressDurationMillis)
        injectKeyEvent(displayId, downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0)
    }

    override fun text(displayId: Int, text: String) {
        ensureManagedDisplay(displayId)
        if (text.isEmpty()) return
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyMap.getEvents(text.toCharArray())
            ?: error("Unable to convert text to keyboard events for this display.")
        var downTime = SystemClock.uptimeMillis()
        events.forEach { sourceEvent ->
            val now = SystemClock.uptimeMillis()
            if (sourceEvent.action == KeyEvent.ACTION_DOWN) {
                downTime = now
            }
            injectKeyEvent(
                displayId = displayId,
                downTime = downTime,
                eventTime = now,
                action = sourceEvent.action,
                keyCode = sourceEvent.keyCode,
                metaState = sourceEvent.metaState,
                scanCode = sourceEvent.scanCode,
                flags = sourceEvent.flags or KeyEvent.FLAG_SOFT_KEYBOARD,
            )
            if (sourceEvent.action == KeyEvent.ACTION_UP) {
                SystemClock.sleep(4L)
            }
        }
    }

    override fun captureImageToFd(
        displayId: Int,
        output: ParcelFileDescriptor,
        maxEdge: Int,
        quality: Int,
    ) {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Sunshine Agent Mode.")
        val reader = imageReaders[displayId]
            ?: error("Display $displayId does not have an internal capture surface.")
        val boundedMaxEdge = maxEdge.coerceIn(320, 4096)
        val boundedQuality = quality.coerceIn(40, 95)
        synchronized(displayLock(displayId)) {
            drainImageReader(reader)
            val previewSurface = previewSurfaces[displayId]?.takeIf { it.isValid }
            display.setSurface(reader.surface)
            try {
                val image = awaitLatestImage(reader)
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    if (image != null) {
                        try {
                            imageToJpegStream(
                                image = image,
                                output = stream,
                                maxEdge = boundedMaxEdge,
                                quality = boundedQuality,
                            )
                        } finally {
                            image.close()
                        }
                    } else if (!displaysWithLaunchedContent.contains(displayId)) {
                        blankImageToJpegStream(
                            width = reader.width,
                            height = reader.height,
                            output = stream,
                            maxEdge = boundedMaxEdge,
                            quality = boundedQuality,
                        )
                    } else {
                        error("Timed out while capturing display $displayId.")
                    }
                }
            } finally {
                if (previewSurface != null && displays[displayId] === display) {
                    runCatching { display.setSurface(previewSurface) }
                }
            }
        }
    }

    private fun displayLock(displayId: Int): Any =
        displayLocks.computeIfAbsent(displayId) { Any() }

    private fun drainImageReader(reader: ImageReader) {
        while (true) {
            val image = reader.acquireLatestImage() ?: return
            image.close()
        }
    }

    private fun awaitLatestImage(reader: ImageReader): Image? {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline) {
            reader.acquireLatestImage()?.let { return it }
            SystemClock.sleep(16L)
        }
        return null
    }

    private fun blankImageToJpegStream(
        width: Int,
        height: Int,
        output: OutputStream,
        maxEdge: Int,
        quality: Int,
    ) {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            bitmap.eraseColor(android.graphics.Color.BLACK)
            val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxEdge)
            try {
                if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    error("Unable to encode empty Agent Mode screenshot.")
                }
                output.flush()
            } finally {
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageToJpegStream(
        image: Image,
        output: OutputStream,
        maxEdge: Int,
        quality: Int,
    ) {
        val plane = image.planes.firstOrNull()
            ?: error("Captured display image had no pixel planes.")
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride.coerceAtLeast(width * pixelStride)
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        try {
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (paddedWidth == width) {
                paddedBitmap
            } else {
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            }
            try {
                val scaledBitmap = scaleBitmapIfNeeded(bitmap, maxEdge)
                try {
                    if (!scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        error("Unable to encode Agent Mode screenshot.")
                    }
                    output.flush()
                } finally {
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                }
            } finally {
                if (bitmap !== paddedBitmap) bitmap.recycle()
            }
        } finally {
            paddedBitmap.recycle()
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largestEdge = maxOf(bitmap.width, bitmap.height)
        if (largestEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / largestEdge.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    override fun listDisplaysJson(): String =
        JSONArray().apply {
            displayManager.displays.forEach { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getSize(size)
                put(
                    JSONObject().apply {
                        put("display_id", display.displayId)
                        put("name", display.name.orEmpty())
                        put("width", display.mode?.physicalWidth ?: size.x)
                        put("height", display.mode?.physicalHeight ?: size.y)
                        put("is_sunshine_display", displays.containsKey(display.displayId))
                    }
                )
            }
        }.toString()

    @Suppress("DEPRECATION")
    override fun listInstalledAppsJson(): String {
        val packageManager = privilegedContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val uniqueApps = linkedMapOf<String, JSONObject>()
        launchables
            .sortedWith(
                compareBy(
                    { it.loadLabel(packageManager).toString().lowercase() },
                    { it.activityInfo.packageName },
                )
            )
            .forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val applicationInfo = activityInfo.applicationInfo ?: return@forEach
                val packageName = activityInfo.packageName.orEmpty()
                if (packageName.isBlank() || uniqueApps.containsKey(packageName)) return@forEach
                uniqueApps[packageName] = JSONObject().apply {
                    put("package_name", packageName)
                    put("app_name", info.loadLabel(packageManager).toString())
                    put("activity_name", activityInfo.name.orEmpty())
                    put("enabled", activityInfo.enabled && applicationInfo.enabled)
                    put("system", applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    put("launchable", true)
                }
            }
        return JSONArray(uniqueApps.values).toString()
    }

    private fun contextForCurrentProcess(baseContext: Context): Context {
        val packageName = packageNameForCurrentProcess(baseContext.packageName)
        if (packageName == baseContext.packageName) return baseContext
        return baseContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun packageNameForCurrentProcess(defaultPackageName: String): String =
        when (Process.myUid()) {
            Process.SHELL_UID -> ShellPackageName
            Process.SYSTEM_UID -> SystemPackageName
            else -> defaultPackageName
        }

    private fun agentVirtualDisplayFlags(): Int =
        VirtualDisplayFlagPublic or
            VirtualDisplayFlagOwnContentOnly or
            VirtualDisplayFlagSupportsTouch or
            VirtualDisplayFlagDestroyContentOnRemoval or
            VirtualDisplayFlagTrusted or
            VirtualDisplayFlagTouchFeedbackDisabled or
            VirtualDisplayFlagOwnFocus or
            VirtualDisplayFlagStealTopFocusDisabled

    private fun ensureManagedDisplay(displayId: Int) {
        if (!displays.containsKey(displayId)) {
            error("Display $displayId is not managed by Sunshine Agent Mode.")
        }
    }

    private fun injectMotionEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectKeyEvent(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int,
        scanCode: Int = 0,
        flags: Int = 0,
    ) {
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            scanCode,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        )
        injectInputEventOnDisplay(displayId, event)
    }

    private fun injectInputEventOnDisplay(displayId: Int, event: InputEvent) {
        try {
            setInputEventDisplayId(event, displayId)
            val inputManager = inputManagerInstance()
            val method = inputManager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            val injected = method.invoke(inputManager, event, InjectInputEventModeWaitForFinish) as Boolean
            if (!injected) {
                error("Input event was rejected by Android input manager for display $displayId.")
            }
        } finally {
            if (event is MotionEvent) {
                event.recycle()
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun setInputEventDisplayId(event: InputEvent, displayId: Int) {
        val method = InputEvent::class.java.getDeclaredMethod(
            "setDisplayId",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(event, displayId)
    }

    private fun inputManagerInstance(): Any {
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val getInstance = inputManagerClass.getDeclaredMethod("getInstance")
        getInstance.isAccessible = true
        return getInstance.invoke(null)
            ?: error("Android input manager was not available.")
    }

    private fun parseKeyCode(rawValue: String): Int {
        val normalized = rawValue.trim()
        normalized.toIntOrNull()?.let { return it }
        val direct = KeyEvent.keyCodeFromString(normalized)
        if (direct != KeyEvent.KEYCODE_UNKNOWN) return direct
        val prefixed = KeyEvent.keyCodeFromString("KEYCODE_${normalized.uppercase()}")
        if (prefixed != KeyEvent.KEYCODE_UNKNOWN) return prefixed
        error("Unsupported key code '$rawValue'.")
    }

    companion object {
        private fun resolveLegacyUserServiceContext(): Context {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val currentApplication = activityThreadClass
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
            if (currentApplication != null) return currentApplication
            if (currentActivityThread != null) {
                val systemContext = activityThreadClass
                    .getDeclaredMethod("getSystemContext")
                    .apply { isAccessible = true }
                    .invoke(currentActivityThread) as? Context
                if (systemContext != null) return systemContext
            }
            error("Unable to create an Android context for Shizuku Agent Mode service.")
        }
    }
}
