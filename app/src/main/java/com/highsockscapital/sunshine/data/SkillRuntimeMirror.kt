package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.runtime.TermuxGuestFiles
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val RuntimeSkillsRoot = "/data/data/com.termux/files/home/.sunshine/skills"

class SkillRuntimeMirror(
    private val guestFiles: TermuxGuestFiles,
) {
    private val mutex = Mutex()
    private val runtimeSignatures = mutableMapOf<LocalRuntimeId, String>()

    suspend fun sync(skills: List<InstalledSkill>): List<String> = mutex.withLock {
        val enabled = skills.filter(InstalledSkill::isEnabled).sortedBy(InstalledSkill::id)
        val signature = enabled.joinToString("|") { "${it.id}:${it.checksumSha256}:${it.updatedAtMillis}" }
        if (runtimeSignatures[LocalRuntimeId.Termux] == signature) {
            return@withLock enabled.map(::runtimeSkillPath)
        }
        val directories = enabled.associate { skill ->
            safeSkillDirectoryName(skill.id) to File(skill.skillRootPath)
        }
        if (directories.size != enabled.size) return@withLock emptyList()

        val ready = runCatching { guestFiles.exists(RuntimeSkillsRoot) }.getOrDefault(false)
        if (!ready) {
            runCatching { guestFiles.ensureDirectory(RuntimeSkillsRoot) }.getOrNull() ?: return@withLock emptyList()
        }
        val mirrored = runCatching {
            // Remove stale skills first so removed entries disappear.
            guestFiles.deleteRecursively(RuntimeSkillsRoot)
            guestFiles.ensureDirectory(RuntimeSkillsRoot)
            directories.forEach { (directoryName, hostDir) ->
                val targetRoot = "$RuntimeSkillsRoot/$directoryName"
                guestFiles.ensureDirectory(targetRoot)
                hostDir.walkTopDown().filter(File::isFile).forEach { file ->
                    val relative = file.relativeToOrNull(hostDir)?.invariantSeparatorsPath ?: return@forEach
                    guestFiles.writeFileBytes("$targetRoot/$relative", file.readBytes())
                }
            }
        }.isSuccess
        if (!mirrored) return@withLock emptyList()

        runtimeSignatures[LocalRuntimeId.Termux] = signature
        enabled.map(::runtimeSkillPath)
    }
}

fun runtimeSkillPath(skill: InstalledSkill): String = "$RuntimeSkillsRoot/${safeSkillDirectoryName(skill.id)}"

private fun safeSkillDirectoryName(id: String): String {
    val normalized = id.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
    if (normalized.isNotBlank()) return normalized
    return MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
