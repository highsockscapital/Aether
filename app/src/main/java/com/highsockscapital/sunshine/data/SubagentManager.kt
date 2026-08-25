package com.highsockscapital.sunshine.data

import java.io.File

/**
 * Registry of subagents built into the Sunshine app.
 *
 * Built-in agents are written into the pi global agents directory
 * (`~/.pi/agent/agents`) on sync. Files carry an ownership marker comment so
 * the manager only ever removes files it generated itself. Users can override
 * a built-in by editing the file; the marker line is preserved.
 */
object BuiltInSubagents {

    const val OwnershipMarkerPrefix = "<!-- sunshine-builtin:"

    data class Definition(
        val name: String,
        val displayName: String,
        val description: String,
        val markdown: String,
    )

    val all: List<Definition> get() = listOf(buildWatcher)

    fun find(name: String): Definition? = all.firstOrNull { it.name == name }

    val buildWatcher = Definition(
        name = "build-watcher",
        displayName = "Build Watcher",
        description = "Triggers GitHub Actions workflow runs, polls them to completion, " +
            "downloads artifacts and surfaces failure logs.",
        markdown = """# Subagent: Build Watcher

<!-- sunshine-builtin: build-watcher -->

## Role

You are Build Watcher, a lightweight subagent spawned to handle GitHub Actions
workflow monitoring so the main session isn't blocked waiting on long-running
builds. You do not have a persona. You are fast, literal, and silent except for
structured status reports.

You run inside Sunshine/Termux on Android. Use the bash tool for all `gh` commands.

## Scope

You handle exactly these tasks:

- Trigger a workflow run (`gh workflow run ...`)
- Poll run status until it completes or a timeout is reached (`gh run list`, `gh run view`, `gh run watch`)
- Download artifacts on success (`gh run download`)
- Surface failure logs on failure (`gh run view --log-failed`)

You do not make code changes, commit, push, or run ADB commands. If asked to do
anything outside this scope, decline and hand back with a one-line reason.

## Operating Rules

1. **No persona, no flourishes.** Plain text only.
2. **No em dashes.** Commas, periods, or hyphens only.
3. **Report structure.** Every report follows this exact shape:

   ```
   STATUS: queued | in_progress | success | failure | cancelled | timeout
   RUN: <workflow name> #<run number>
   URL: <run URL>
   DURATION: <elapsed time, if known>
   NEXT: <artifact path, failure summary, or "none">
   ```

4. **Timeouts.** If a run exceeds 20 minutes without completing, stop polling,
   report `STATUS: timeout`, and hand control back rather than polling forever.
5. **Failures.** On failure, pull the failed step's log with
   `gh run view --log-failed`, extract only the error lines (not the full log),
   and include a short verbatim excerpt under `NEXT`.
6. **No silent retries.** If a run fails, do not automatically re-trigger it.
7. **No destructive actions.** Never delete runs, cancel other users'
   workflows, or touch repo settings.
8. **Checkpoint everything.** Follow the Session Resume Protocol below
   without exception; it is what lets you survive context compaction
   mid-poll.

## Session Resume Protocol

Your context can be compacted mid-task. A checkpoint file is your memory.

Checkpoint directory: `~/.pi/agent/checkpoints/` (create it if missing).
One JSON file per run: `build-watcher-<run-id>.json`, or
`build-watcher-pending.json` before you know the run id.

On start:

1. List the checkpoint directory. If a checkpoint exists whose task matches
   this spawn, read it fully and resume from the recorded state instead of
   re-triggering anything. Do not re-trigger a run that already has a run id.
2. Otherwise create a pending checkpoint before triggering the workflow, and
   rename the file with the real run id once known.

After every state change (workflow triggered, each poll result, artifact
downloaded, failure log fetched):

- Rewrite the whole checkpoint file atomically (write to
  `<name>.json.tmp`, then `mv` over the target). Fields:
  `{"task","workflow","run_id","run_url","status","started_at_unix",
  "last_checked_at_unix","poll_count","artifact_path","notes"}`.
- Keep `notes` short but concrete (commands already run, errors seen).

If you notice gaps in your context (compaction happened): stop, re-read the
checkpoint file, and continue from `last_checked_at_unix` and
`poll_count`. Never guess the run id from memory when the file has it.

At a terminal state (success, failure, cancelled, timeout): deliver the
structured report first, then delete that run's checkpoint file.

## Handoff Protocol

- On completion (success, failure, cancelled, or timeout), return the
  structured report and stop.
- If you hit an auth error, permission error, or missing `gh` context, report
  it verbatim and stop immediately rather than attempting workarounds.
""",
    )
}

data class AgentFileInfo(
    val name: String,
    val file: File,
    val isBuiltIn: Boolean,
    val modelId: String,
)

/**
 * Pure helpers for agent frontmatter manipulation. Kept side-effect free for
 * testability.
 */
object AgentFrontmatter {

    private val FrontmatterRegex = Regex("\\A---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|\\z)", RegexOption.DOT_MATCHES_ALL)
    private val ModelLineRegex = Regex("(?m)^model:\\s*.*$")

    fun parseModelId(markdown: String): String {
        val block = FrontmatterRegex.find(markdown)?.groupValues?.get(1) ?: return ""
        return block.lineSequence()
            .firstOrNull { it.trimStart().startsWith("model:") }
            ?.substringAfter("model:")
            ?.trim()
            ?.trim('"', '\'')
            .orEmpty()
    }

    /** Returns [markdown] with the frontmatter `model` key set to [modelId]. */
    fun withModelId(markdown: String, modelId: String): String {
        val trimmed = modelId.trim()
        val match = FrontmatterRegex.find(markdown)
        if (match == null) {
            if (trimmed.isEmpty()) return markdown
            return "---\nmodel: $trimmed\n---\n\n$markdown"
        }
        var block = match.groupValues[1]
        block = if (trimmed.isEmpty()) {
            ModelLineRegex.replace(block, "")
        } else if (ModelLineRegex.containsMatchIn(block)) {
            ModelLineRegex.replace(block, "model: $trimmed")
        } else {
            "$block\nmodel: $trimmed"
        }
        return "---\n$block\n---" + markdown.substring(match.range.last + 1)
    }

    fun ownershipMarker(markdown: String): String? =
        markdown.lineSequence().map(String::trim).firstOrNull {
            it.startsWith(BuiltInSubagents.OwnershipMarkerPrefix)
        }?.removePrefix(BuiltInSubagents.OwnershipMarkerPrefix)?.trimEnd('-', '>', ' ')?.trim()
}

/**
 * Syncs built-in subagents and applies user configuration to the on-device pi
 * agents directories.
 */
class SubagentManager(
    private val homeDirectory: String,
    private val globalAgentsDirectoryOverride: File? = null,
) {

    fun globalAgentsDirectory(): File =
        globalAgentsDirectoryOverride ?: File(homeDirectory, ".pi/agent/agents")

    fun listAgents(workspaceAgentsDirectory: File?): List<AgentFileInfo> {
        val dirs = buildList {
            add(globalAgentsDirectory())
            if (workspaceAgentsDirectory != null) add(workspaceAgentsDirectory)
        }
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension == "md" }
                .orEmpty()
                .map { file ->
                    val markdown = runCatching { file.readText() }.getOrDefault("")
                    val marker = AgentFrontmatter.ownershipMarker(markdown)
                    AgentFileInfo(
                        name = file.nameWithoutExtension,
                        file = file,
                        isBuiltIn = marker != null && BuiltInSubagents.find(marker) != null,
                        modelId = AgentFrontmatter.parseModelId(markdown),
                    )
                }
        }.sortedBy { it.name }
    }

    /**
     * Writes enabled built-in agents (with configured model ids applied) into
     * the global agents directory and removes the files of disabled ones.
     * Returns the list of changed file names.
     */
    fun syncBuiltIns(configs: Map<String, SubagentConfig>): List<String> {
        val changed = mutableListOf<String>()
        val dir = globalAgentsDirectory().apply { mkdirs() }
        for (definition in BuiltInSubagents.all) {
            val config = configs[definition.name] ?: SubagentConfig()
            val file = File(dir, "${definition.name}.md")
            val enabled = config.enabled
            if (!enabled) {
                if (file.exists() &&
                    AgentFrontmatter.ownershipMarker(runCatching { file.readText() }.getOrDefault("")) == definition.name
                ) {
                    file.delete()
                    changed += definition.name
                }
                continue
            }
            val desired = AgentFrontmatter.withModelId(definition.markdown, config.modelId).trim() + "\n"
            val current = runCatching { file.readText() }.getOrNull()
            if (current != desired) {
                file.writeText(desired)
                changed += definition.name
            }
        }
        return changed
    }
}
