package com.highsockscapital.sunshine.runtime

import android.content.Context
import android.util.Base64
import com.highsockscapital.sunshine.termux.TermuxBashTool
import com.highsockscapital.sunshine.termux.TermuxContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * File helpers for the Termux home directory. The Sunshine app sandbox cannot
 * touch Termux storage directly, so every operation is dispatched as a managed
 * bash command through [TermuxBashTool].
 */
class TermuxGuestFiles(
    context: Context,
    private val bashTool: TermuxBashTool,
) {
    private val appContext = context.applicationContext

    private val home = TermuxContract.HomeDirectory

    suspend fun execute(command: String, workingDirectory: String = home): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(bashTool.executeCommand(command, workingDirectory))
        }

    suspend fun ensureDirectory(path: String) {
        val result = execute("mkdir -p ${shellQuote(path)}")
        require(result.optBoolean("ok")) {
            result.optString("stderr").ifBlank { "Couldn't create directory: $path" }
        }
    }

    suspend fun exists(path: String): Boolean {
        val result = execute("test -e ${shellQuote(path)} && echo YES || echo NO")
        return result.optBoolean("ok") && result.optString("stdout").trim() == "YES"
    }

    /** Writes bytes to an absolute Termux path using chunked base64 appends. */
    suspend fun writeFileBytes(path: String, bytes: ByteArray) {
        ensureDirectory(path.substringBeforeLast('/'))
        val temp = "$path.b64.part"
        execute("rm -f ${shellQuote(temp)} ${shellQuote(path)}")
        try {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + WriteChunkBytes, bytes.size)
                val chunk = Base64.encodeToString(
                    bytes.copyOfRange(offset, end),
                    Base64.NO_WRAP,
                )
                val result = execute("printf '%s' ${shellQuote(chunk)} >> ${shellQuote(temp)}")
                require(result.optBoolean("ok")) {
                    result.optString("stderr").ifBlank { "Couldn't write file: $path" }
                }
                offset = end
            }
            val decode = execute("base64 -d ${shellQuote(temp)} > ${shellQuote(path)}")
            require(decode.optBoolean("ok")) {
                decode.optString("stderr").ifBlank { "Couldn't write file: $path" }
            }
        } finally {
            execute("rm -f ${shellQuote(temp)}")
        }
    }

    suspend fun readFileBytes(path: String, byteLimit: Long = 32L * 1024 * 1024): ByteArray {
        val sizeResult = execute("wc -c < ${shellQuote(path)}")
        val size = sizeResult.optString("stdout").trim().toLongOrNull()
            ?: error("Couldn't read file size: $path")
        require(size <= byteLimit) { "File is too large: $path" }
        val b64 = execute("base64 < ${shellQuote(path)} | tr -d '\\n'")
        require(b64.optBoolean("ok")) {
            b64.optString("stderr").ifBlank { "Couldn't read file: $path" }
        }
        return Base64.decode(b64.optString("stdout").trim(), Base64.DEFAULT)
    }

    /** Copies an APK asset into the Termux home. */
    suspend fun installAsset(assetPath: String, destinationPath: String) {
        val bytes = withContext(Dispatchers.IO) {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }
        writeFileBytes(destinationPath, bytes)
    }

    suspend fun deleteRecursively(path: String) {
        execute("rm -rf ${shellQuote(path)}")
    }

    companion object {
        private const val WriteChunkBytes = 192 * 1024

        fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
