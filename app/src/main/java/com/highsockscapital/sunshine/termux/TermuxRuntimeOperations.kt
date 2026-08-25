package com.highsockscapital.sunshine.termux

import java.util.Base64
import org.json.JSONObject

private const val RuntimeFileChunkBytes = 64 * 1024

class TermuxRuntimeOperations(
    private val bashTool: TermuxBashTool,
) {
    suspend fun execute(
        kind: String,
        payload: JSONObject,
        inputData: ByteArray? = null,
        onChunk: suspend (sequence: Int, data: ByteArray) -> Unit,
    ): JSONObject = when (kind) {
        "access" -> access(payload)
        "readFile" -> readFile(payload, onChunk)
        "writeFile" -> writeFile(payload, inputData ?: ByteArray(0))
        "mkdir" -> mkdir(payload)
        "detectMime" -> detectMime(payload)
        "bash" -> executeBash(payload, onChunk)
        else -> error("Unsupported Termux runtime operation: $kind")
    }

    private suspend fun access(payload: JSONObject): JSONObject {
        val path = payload.requiredPath()
        val mode = payload.optString("mode")
        val predicate = if (mode == "write") "-w" else "-r"
        runScript("test $predicate ${shellQuote(path)}", TermuxContract.HomeDirectory)
        return JSONObject()
    }

    private suspend fun readFile(
        payload: JSONObject,
        onChunk: suspend (sequence: Int, data: ByteArray) -> Unit,
    ): JSONObject {
        val path = payload.requiredPath()
        var offset = 0L
        var sequence = 0
        while (true) {
            val command = buildString {
                append("set -euo pipefail\n")
                append("path=").append(shellQuote(path)).append('\n')
                append("offset=").append(offset).append('\n')
                append("size=\$(wc -c < \"\$path\" | tr -d '[:space:]')\n")
                append("printf 'size=%s\\n' \"\$size\"\n")
                append("if [ \"\$offset\" -lt \"\$size\" ]; then ")
                append("tail -c +\$((offset + 1)) -- \"\$path\" | head -c $RuntimeFileChunkBytes | base64 | tr -d '\\n'; fi\n")
            }
            val raw = runScript(command, TermuxContract.HomeDirectory)
            val newline = raw.indexOf('\n')
            check(newline >= 0 && raw.startsWith("size=")) { "Termux returned an invalid file chunk." }
            val size = raw.substring(5, newline).trim().toLong()
            val encoded = raw.substring(newline + 1).trim()
            val chunk = if (encoded.isBlank()) ByteArray(0) else Base64.getDecoder().decode(encoded)
            if (chunk.isNotEmpty()) {
                onChunk(sequence++, chunk)
                offset += chunk.size
            }
            if (offset >= size) break
            check(chunk.isNotEmpty()) { "Termux file read stopped before reaching the expected size." }
        }
        return JSONObject().put("byte_count", offset)
    }

    private suspend fun writeFile(payload: JSONObject, content: ByteArray): JSONObject {
        val path = payload.requiredPath()
        val temporaryPath = "$path.sunshine-tmp-${System.nanoTime()}"
        try {
            runScript(
                "mkdir -p -- ${shellQuote(path.substringBeforeLast('/', TermuxContract.HomeDirectory))} && : > ${shellQuote(temporaryPath)}",
                TermuxContract.HomeDirectory,
            )
            content.asList().chunked(RuntimeFileChunkBytes).forEach { values ->
                val encoded = Base64.getEncoder().encodeToString(values.toByteArray())
                runScript(
                    "printf '%s' ${shellQuote(encoded)} | base64 -d >> ${shellQuote(temporaryPath)}",
                    TermuxContract.HomeDirectory,
                )
            }
            runScript(
                "mv -f -- ${shellQuote(temporaryPath)} ${shellQuote(path)}",
                TermuxContract.HomeDirectory,
            )
        } catch (failure: Throwable) {
            runCatching {
                runScript("rm -f -- ${shellQuote(temporaryPath)}", TermuxContract.HomeDirectory)
            }
            throw failure
        }
        return JSONObject().put("byte_count", content.size)
    }

    private suspend fun mkdir(payload: JSONObject): JSONObject {
        val path = payload.requiredPath()
        runScript("mkdir -p -- ${shellQuote(path)}", TermuxContract.HomeDirectory)
        return JSONObject()
    }

    private suspend fun detectMime(payload: JSONObject): JSONObject {
        val path = payload.requiredPath()
        val output = runScript(
            "if command -v file >/dev/null 2>&1; then file -b --mime-type -- ${shellQuote(path)}; fi",
            TermuxContract.HomeDirectory,
        ).trim()
        val supported = output.takeIf {
            it in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp")
        }.orEmpty()
        return JSONObject().put("mime_type", supported)
    }

    private suspend fun executeBash(
        payload: JSONObject,
        onChunk: suspend (sequence: Int, data: ByteArray) -> Unit,
    ): JSONObject {
        val command = payload.optString("command").trim()
        require(command.isNotBlank()) { "Missing bash command." }
        val cwd = payload.optString("cwd").trim().ifBlank { TermuxContract.HomeDirectory }
        val timeout = payload.takeIf { it.has("timeout_seconds") && !it.isNull("timeout_seconds") }
            ?.optDouble("timeout_seconds")
        var sequence = 0
        val exitCode = bashTool.executeStreaming(
            command = command,
            workingDirectory = cwd,
            timeoutSeconds = timeout,
        ) { bytes ->
            onChunk(sequence++, bytes)
        }
        return JSONObject().apply {
            if (exitCode == null) put("exit_code", JSONObject.NULL) else put("exit_code", exitCode)
        }
    }

    private suspend fun runScript(command: String, workingDirectory: String): String {
        val result = JSONObject(
            bashTool.executeCommand(
                command = command,
                workingDirectory = workingDirectory,
                awaitTimeoutMillis = 60_000L,
            )
        )
        check(result.optBoolean("ok")) {
            result.optString("errmsg").ifBlank { result.optString("stderr").ifBlank { "Termux operation failed." } }
        }
        return result.optString("stdout")
    }
}

private fun JSONObject.requiredPath(): String =
    optString("path").trim().also { require(it.isNotBlank()) { "Missing runtime operation path." } }

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
