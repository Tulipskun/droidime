package com.tulipskun.droidime

import android.content.ClipData
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuBridge(private val preferences: SharedPreferences) {
    private val lock = Any()

    fun preferences(): SharedPreferences = preferences

    fun checkConnection(): String {
        ensureReady()

        val output = runCommand("echo Droid-MCP").stdout
            .toString(StandardCharsets.UTF_8)
            .trim()

        if (output != "Droid-MCP") {
            throw IllegalStateException(
                "Shizuku shell test returned unexpected output"
            )
        }

        return "Shizuku connected"
    }

    private fun ensureReady() {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku is not running")
        }

        if (
            Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Droid-MCP is not authorized in Shizuku"
            )
        }
    }

    private fun runCommand(command: String): CommandResult {
        ensureReady()

        synchronized(lock) {
            val process = Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
                .invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val streamsClosed = CountDownLatch(2)

            val stdoutThread = Thread {
                try {
                    process.inputStream.use { it.copyTo(stdout) }
                } finally {
                    streamsClosed.countDown()
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.use { it.copyTo(stderr) }
                } finally {
                    streamsClosed.countDown()
                }
            }

            stdoutThread.start()
            stderrThread.start()

            try {
                if (
                    !streamsClosed.await(
                        COMMAND_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                ) {
                    process.destroy()
                    throw IllegalStateException(
                        "Shizuku command timed out"
                    )
                }

                val exitCode = try {
                    process.waitFor()
                    process.exitValue()
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        error.message ?: "Unable to get Shizuku process exit code",
                        error
                    )
                }

                val stdoutBytes = stdout.toByteArray()
                val stderrBytes = stderr.toByteArray()

                if (exitCode != 0) {
                    val message = String(
                        stderrBytes,
                        StandardCharsets.UTF_8
                    ).trim()

                    throw IllegalStateException(
                        if (message.isNotEmpty()) {
                            message
                        } else {
                            "Shizuku command failed with exit code " + exitCode
                        }
                    )
                }

                return CommandResult(stdoutBytes, stderrBytes)
            } finally {
                try {
                    process.destroy()
                } catch (_: Throwable) {
                }
                try {
                    stdoutThread.join(COMMAND_STREAM_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                try {
                    stderrThread.join(COMMAND_STREAM_JOIN_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    fun tap(x: String, y: String) {
        val size = screenSize()
        val px = percentToPixel(x, size.width)
        val py = percentToPixel(y, size.height)
        runCommand("input tap $px $py")
    }

    fun longPress(x: String, y: String, durationMs: Long) {
        require(durationMs >= 200) { "duration_ms must be >= 200" }
        val size = screenSize()
        val px = percentToPixel(x, size.width)
        val py = percentToPixel(y, size.height)
        val downTime = SystemClock.uptimeMillis()
        injectMotionEvent(createMotionEvent(downTime, MotionEvent.ACTION_DOWN, intArrayOf(0), arrayOf(intArrayOf(px, py))))
        try {
            Thread.sleep(durationMs)
        } finally {
            injectMotionEvent(createMotionEvent(downTime, MotionEvent.ACTION_UP, intArrayOf(0), arrayOf(intArrayOf(px, py))))
        }
    }

    fun swipe(
        x1: String,
        y1: String,
        x2: String,
        y2: String,
        durationMs: Long
    ) {
        require(durationMs >= 0) {
            "duration_ms must be >= 0"
        }

        val size = screenSize()
        val px1 = percentToPixel(x1, size.width)
        val py1 = percentToPixel(y1, size.height)
        val px2 = percentToPixel(x2, size.width)
        val py2 = percentToPixel(y2, size.height)
        runCommand("input swipe $px1 $py1 $px2 $py2 $durationMs")
    }

    fun gesture(points: JSONArray) {
        require(points.length() >= 2) {
            "gesture requires at least 2 points"
        }

        val size = screenSize()
        val command = StringBuilder()

        for (i in 0 until points.length()) {
            val point = points.getJSONObject(i)
            val x = point.getString("x")
            val y = point.getString("y")
            val delay = point.getLong("delay_ms")

            require(delay >= 0) {
                "delay_ms must be >= 0"
            }

            if (i == 0) {
                command.append(
                    "input motionevent DOWN " +
                        percentToPixel(x, size.width) + " " + percentToPixel(y, size.height)
                )
            } else {
                command.append(
                    "; sleep " + delay / 1000.0
                )
                command.append(
                    "; input motionevent MOVE " +
                        percentToPixel(x, size.width) + " " + percentToPixel(y, size.height)
                )
            }
        }

        val last = points.getJSONObject(points.length() - 1)

        command.append(
            "; input motionevent UP " +
                percentToPixel(last.getString("x"), size.width) + " " +
                percentToPixel(last.getString("y"), size.height)
        )

        runCommand(command.toString())
    }

    fun multiTouch(pointers: JSONArray) {
        require(pointers.length() > 0) {
            "pointers must not be empty"
        }

        synchronized(lock) {
            ensureReady()

            val count = pointers.length()
            val pointerIds = IntArray(count)
            val pointArrays = arrayOfNulls<JSONArray>(count)

            for (i in 0 until count) {
                val pointer = pointers.getJSONObject(i)
                val id = pointer.getInt("id")
                require(id >= 0) {
                    "pointer id must be >= 0"
                }

                val points = pointer.getJSONArray("points")
                require(points.length() > 0) {
                    "pointer points must not be empty"
                }

                pointerIds[i] = id
                pointArrays[i] = points
            }

            val size = screenSize()
            val positions = Array(count) { IntArray(2) }

            for (i in 0 until count) {
                val point = pointArrays[i]!!.getJSONObject(0)
                positions[i][0] = percentToPixel(point.getString("x"), size.width)
                positions[i][1] = percentToPixel(point.getString("y"), size.height)
            }

            val downTime = SystemClock.uptimeMillis()

            injectMotionEvent(
                createMotionEvent(
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    pointerIds.copyOfRange(0, 1),
                    positions.copyOfRange(0, 1)
                )
            )

            for (i in 1 until count) {
                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_POINTER_DOWN or
                            (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        pointerIds.copyOfRange(0, i + 1),
                        positions.copyOfRange(0, i + 1)
                    )
                )
            }

            val maxPoints = (0 until count).maxOf {
                pointArrays[it]!!.length()
            }

            for (step in 1 until maxPoints) {
                var delayMs = 0L

                for (i in 0 until count) {
                    val points = pointArrays[i]!!
                    if (step >= points.length()) {
                        continue
                    }

                    val point = points.getJSONObject(step)
                    val delay = point.getLong("delay_ms")
                    require(delay >= 0) {
                        "delay_ms must be >= 0"
                    }

                    delayMs = maxOf(delayMs, delay)
                    positions[i][0] = percentToPixel(point.getString("x"), size.width)
                    positions[i][1] = percentToPixel(point.getString("y"), size.height)
                }

                if (delayMs > 0) {
                    Thread.sleep(delayMs)
                }

                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_MOVE,
                        pointerIds,
                        positions
                    )
                )
            }

            for (i in count - 1 downTo 1) {
                injectMotionEvent(
                    createMotionEvent(
                        downTime,
                        MotionEvent.ACTION_POINTER_UP or
                            (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        pointerIds,
                        positions
                    )
                )
            }

            injectMotionEvent(
                createMotionEvent(
                    downTime,
                    MotionEvent.ACTION_UP,
                    pointerIds.copyOfRange(0, 1),
                    positions.copyOfRange(0, 1)
                )
            )
        }
    }

    private data class ScreenSize(val width: Int, val height: Int)

    private fun screenSize(): ScreenSize {
        val output = runCommand("wm size").stdout.toString(StandardCharsets.UTF_8)
        val match = Regex("[0-9]+x[0-9]+").findAll(output).lastOrNull()
            ?: throw IllegalStateException("Unable to determine screen size")
        val parts = match.value.split("x")
        return ScreenSize(parts[0].toInt(), parts[1].toInt())
    }

    private fun percentToPixel(value: String, size: Int): Int {
        val match = Regex("^ *([0-9]+([.][0-9]+)?)% *$").matchEntire(value)
            ?: throw IllegalArgumentException("Coordinate must be a percentage such as 50%")
        val percent = match.groupValues[1].toDouble()
        require(percent in 0.0..100.0) { "Coordinate percentage must be between 0% and 100%" }
        return kotlin.math.round(size * percent / 100.0).toInt().coerceIn(0, size - 1)
    }

    private fun createMotionEvent(
        downTime: Long,
        action: Int,
        pointerIds: IntArray,
        positions: Array<IntArray>
    ): MotionEvent {
        val properties = Array(pointerIds.size) {
            MotionEvent.PointerProperties().apply {
                id = pointerIds[it]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val coords = Array(pointerIds.size) {
            MotionEvent.PointerCoords().apply {
                x = positions[it][0].toFloat()
                y = positions[it][1].toFloat()
                pressure = 1f
                size = 1f
            }
        }

        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            pointerIds.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private fun injectKeyEvent(event: KeyEvent) {
        try {
            val service = SystemServiceHelper.getSystemService("input")
                ?: throw IllegalStateException("Android input service is unavailable")

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod(
                "asInterface",
                android.os.IBinder::class.java
            )
            asInterface.isAccessible = true

            val inputManager = asInterface.invoke(
                null,
                ShizukuBinderWrapper(service)
            ) ?: throw IllegalStateException("Unable to create IInputManager proxy")

            val inject = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            inject.isAccessible = true

            val result = inject.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            ) as? Boolean ?: false

            if (!result) {
                throw IllegalStateException("Android rejected key event injection")
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            throw IllegalStateException(
                "IInputManager.injectInputEvent(key) failed: " +
                    (cause.message ?: cause.javaClass.name),
                cause
            )
        }
    }

    private fun injectMotionEvent(event: MotionEvent) {
        try {
            val service = SystemServiceHelper.getSystemService("input")
                ?: throw IllegalStateException(
                    "Android input service is unavailable"
                )

            val stubClass = Class.forName(
                "android.hardware.input.IInputManager\$Stub"
            )
            val asInterface = stubClass.getDeclaredMethod(
                "asInterface",
                android.os.IBinder::class.java
            )
            asInterface.isAccessible = true

            val inputManager = asInterface.invoke(
                null,
                ShizukuBinderWrapper(service)
            ) ?: throw IllegalStateException(
                "Unable to create IInputManager proxy"
            )

            val inject = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            inject.isAccessible = true

            val result = inject.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            ) as? Boolean ?: false

            if (!result) {
                throw IllegalStateException(
                    "Android rejected input event injection"
                )
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            throw IllegalStateException(
                "IInputManager.injectInputEvent failed: " +
                    (cause.message ?: cause.javaClass.name),
                cause
            )
        } finally {
            event.recycle()
        }
    }

    fun keyEvent(keycode: Int) {
        keyChord(intArrayOf(keycode), 0L)
    }

    fun keyChord(keycodes: IntArray, holdMs: Long) {
        require(keycodes.isNotEmpty()) {
            "keycodes must not be empty"
        }
        require(holdMs >= 0) {
            "hold_ms must be >= 0"
        }

        synchronized(lock) {
            ensureReady()

            val downTime = SystemClock.uptimeMillis()
            var metaState = 0

            for (keycode in keycodes) {
                metaState = metaState or modifierMetaForKey(keycode)
                injectKeyEvent(
                    createKeyEvent(
                        downTime,
                        KeyEvent.ACTION_DOWN,
                        keycode,
                        metaState
                    )
                )
            }

            try {
                if (holdMs > 0) {
                    Thread.sleep(holdMs)
                }
            } finally {
                for (index in keycodes.indices.reversed()) {
                    val keycode = keycodes[index]
                    injectKeyEvent(
                        createKeyEvent(
                            downTime,
                            KeyEvent.ACTION_UP,
                            keycode,
                            metaState
                        )
                    )
                    metaState = metaState and modifierMetaForKey(keycode).inv()
                }
            }
        }
    }

    private fun modifierMetaForKey(keycode: Int): Int =
        when (keycode) {
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
            else -> 0
        }

    private fun createKeyEvent(
        downTime: Long,
        action: Int,
        keycode: Int,
        metaState: Int
    ): KeyEvent =
        KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            keycode,
            0,
            metaState,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD
        )

    fun inputText(text: String) {
        val escaped = shellQuote(text)

        if (text.all { it.code < 128 }) {
            runCommand("input text " + escaped)
            return
        }

        setClipboardText(text)
        runCommand("input keyevent 279")
    }

    private fun setClipboardText(text: String) {
        val service = SystemServiceHelper.getSystemService("clipboard")
            ?: throw IllegalStateException("Android clipboard service is unavailable")

        val stubClass = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stubClass.getDeclaredMethod(
            "asInterface",
            android.os.IBinder::class.java
        )
        asInterface.isAccessible = true

        val clipboard = asInterface.invoke(
            null,
            ShizukuBinderWrapper(service)
        ) ?: throw IllegalStateException("Unable to create IClipboard proxy")

        val clipboardInterface = Class.forName("android.content.IClipboard")
        val setPrimaryClip = clipboardInterface.declaredMethods.firstOrNull {
            it.name == "setPrimaryClip"
        } ?: throw IllegalStateException("IClipboard.setPrimaryClip is unavailable")

        val clip = ClipData.newPlainText("Droid-MCP", text)
        val parameterCount = setPrimaryClip.parameterTypes.size

        try {
            when (parameterCount) {
                5 -> setPrimaryClip.invoke(
                    clipboard,
                    clip,
                    "com.android.shell",
                    null,
                    currentUserId(),
                    0
                )
                4 -> setPrimaryClip.invoke(
                    clipboard,
                    clip,
                    "com.android.shell",
                    null,
                    currentUserId()
                )
                3 -> setPrimaryClip.invoke(
                    clipboard,
                    clip,
                    "com.android.shell",
                    currentUserId()
                )
                2 -> setPrimaryClip.invoke(
                    clipboard,
                    clip,
                    "com.android.shell"
                )
                else -> throw IllegalStateException(
                    "Unsupported IClipboard.setPrimaryClip signature"
                )
            }
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            throw IllegalStateException(
                "Unable to set clipboard text: " +
                    (cause.message ?: cause.javaClass.name),
                cause
            )
        }
    }

    private fun currentUserId(): Int =
        try {
            val method = android.os.UserHandle::class.java.getDeclaredMethod("myUserId")
            method.isAccessible = true
            method.invoke(null) as Int
        } catch (_: Throwable) {
            0
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    fun screenshot(): ByteArray {
        val png = runCommand("screencap -p").stdout
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalStateException("Unable to decode screenshot")

        if (source.width <= MAX_SCREENSHOT_EDGE &&
            source.height <= MAX_SCREENSHOT_EDGE
        ) {
            return png
        }

        val scale = MAX_SCREENSHOT_EDGE.toFloat() /
            maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(source, width, height, true)

        return try {
            ByteArrayOutputStream().use { output ->
                if (!resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException("Unable to encode resized screenshot")
                }
                output.toByteArray()
            }
        } finally {
            if (resized !== source) {
                resized.recycle()
            }
            source.recycle()
        }
    }


    fun inspectUi(): JSONArray {
        val xml = runCommand("uiautomator dump /sdcard/droidime_window.xml >/dev/null 2>&1 && cat /sdcard/droidime_window.xml").stdout
            .toString(StandardCharsets.UTF_8)
        if (!xml.contains("<hierarchy")) {
            throw IllegalStateException("Unable to read Android UI hierarchy")
        }

        val result = JSONArray()
        val nodePattern = Regex("<node\\s+([^>]*?)/?>")
        val attributePattern = Regex("([A-Za-z_-]+)=\"([^\"]*)\"")
        val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

        for (match in nodePattern.findAll(xml).take(250)) {
            val attrs = attributePattern.findAll(match.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            val text = attrs["text"].orEmpty()
            val desc = attrs["content-desc"].orEmpty()
            val resource = attrs["resource-id"].orEmpty()
            val bounds = attrs["bounds"].orEmpty()
            if (text.isBlank() && desc.isBlank() && resource.isBlank()) continue
            result.put(
                JSONObject()
                    .put("text", text)
                    .put("contentDescription", desc)
                    .put("resourceId", resource)
                    .put("className", attrs["class"].orEmpty())
                    .put("clickable", attrs["clickable"] == "true")
                    .put("enabled", attrs["enabled"] != "false")
                    .put("bounds", bounds)
            )
        }
        return result
    }

    fun findAndTapUi(query: String): JSONObject {
        require(query.isNotBlank()) { "query must not be empty" }
        val xml = runCommand("uiautomator dump /sdcard/droidime_window.xml >/dev/null 2>&1 && cat /sdcard/droidime_window.xml").stdout
            .toString(StandardCharsets.UTF_8)
        if (!xml.contains("<hierarchy")) {
            throw IllegalStateException("Unable to read Android UI hierarchy")
        }

        val nodePattern = Regex("<node\\s+([^>]*?)/?>")
        val attributePattern = Regex("([A-Za-z_-]+)=\"([^\"]*)\"")
        val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
        val target = query.trim().lowercase()
        val candidates = nodePattern.findAll(xml).mapNotNull { match ->
            val attrs = attributePattern.findAll(match.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            val text = attrs["text"].orEmpty()
            val desc = attrs["content-desc"].orEmpty()
            val resource = attrs["resource-id"].orEmpty()
            val matchValue = listOf(text, desc, resource).joinToString(" ").lowercase()
            if (!matchValue.contains(target) || attrs["enabled"] == "false") return@mapNotNull null
            val bounds = boundsPattern.find(attrs["bounds"].orEmpty()) ?: return@mapNotNull null
            val left = bounds.groupValues[1].toInt()
            val top = bounds.groupValues[2].toInt()
            val right = bounds.groupValues[3].toInt()
            val bottom = bounds.groupValues[4].toInt()
            if (right <= left || bottom <= top) return@mapNotNull null
            val score = (if (attrs["clickable"] == "true") 4 else 0) +
                (if (text.equals(query, true) || desc.equals(query, true) || resource.endsWith("/$query")) 8 else 0)
            Triple(attrs, intArrayOf(left, top, right, bottom), score)
        }.sortedByDescending { it.third }.firstOrNull()
            ?: throw IllegalArgumentException("No visible UI element matched: $query")

        val size = screenSize()
        val bounds = candidates.second
        val centerX = (bounds[0] + bounds[2]) / 2.0
        val centerY = (bounds[1] + bounds[3]) / 2.0
        val x = (centerX * 100.0 / size.width).coerceIn(0.0, 100.0)
        val y = (centerY * 100.0 / size.height).coerceIn(0.0, 100.0)
        tap("$x%", "$y%")

        return JSONObject()
            .put("matchedText", candidates.first["text"].orEmpty())
            .put("contentDescription", candidates.first["content-desc"].orEmpty())
            .put("resourceId", candidates.first["resource-id"].orEmpty())
            .put("bounds", candidates.first["bounds"].orEmpty())
            .put("tapX", "$x%")
            .put("tapY", "$y%")
    }

    fun waitForUiText(query: String, timeoutMs: Long): JSONObject {
        require(timeoutMs in 0L..30000L) { "timeout_ms must be between 0 and 30000" }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val nodes = inspectUi()
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val value = (node.optString("text") + " " + node.optString("contentDescription") + " " + node.optString("resourceId"))
                if (value.contains(query, ignoreCase = true)) {
                    return JSONObject().put("found", true).put("element", node)
                }
            }
            if (SystemClock.elapsedRealtime() < deadline) Thread.sleep(400)
        } while (SystemClock.elapsedRealtime() < deadline)
        return JSONObject().put("found", false).put("query", query)
    }

    fun close() {
    }

    private data class CommandResult(
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    companion object {
        private const val MAX_SCREENSHOT_EDGE = 1500
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
        private const val COMMAND_TIMEOUT_SECONDS = 15L
        private const val COMMAND_STREAM_JOIN_MILLIS = 1000L
    }
}
