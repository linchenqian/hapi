package app.hapi.companion.feature.chat

import app.hapi.companion.feature.chat.blocks.isPlanProposalTool
import app.hapi.protocol.chat.*

/** Presentation only: the protocol/inspection history is never removed or regrouped. */
internal sealed interface TranscriptItem {
    val id: String
    val contentKind: String

    data class Block(val block: VisibleChatBlock, val processId: String? = null) : TranscriptItem {
        override val id: String get() = block.stableId
        override val contentKind: String get() = block.contentKind
    }

    data class Process(
        override val id: String,
        val blocks: List<VisibleChatBlock>,
        val summary: ProcessSummary,
        val finished: Boolean = false,
        val durationMs: Long? = null,
    ) : TranscriptItem {
        override val contentKind: String get() = "process-header"
        val endId: String get() = "$id:end"
    }

    data class End(val processId: String) : TranscriptItem {
        override val id: String get() = "$processId:end"
        override val contentKind: String get() = "process-footer"
    }

    data class Working(val startedAt: Long?) : TranscriptItem {
        override val id: String get() = "chat-working-status"
        override val contentKind: String get() = "working-status"
    }
}

internal data class ProcessSummary(val operations: Int, val thoughts: Int, val errors: Int)

private fun ToolCallBlock.requiresVisibleAction(): Boolean =
    tool.permission?.status == "pending" || isPlanProposalTool(tool.name) ||
        isAskUserQuestionToolName(tool.name) || isRequestUserInputToolName(tool.name) ||
        tool.name == "CodexPermission" || isSubagentToolName(tool.name) || tool.name == "CodexAgent" ||
        children.any { it is ToolCallBlock && it.requiresVisibleAction() }

internal fun isProcessStep(block: VisibleChatBlock): Boolean = when (block) {
    is AgentReasoningBlock -> true
    is CliOutputBlock -> block.source != "user"
    is ToolCallBlock -> !block.requiresVisibleAction()
    is ToolGroupBlock -> block.tools.none { it.requiresVisibleAction() }
    // Never infer that prose is interim: unknown/final answers stay visible.
    else -> false
}

private fun summarizeProcess(blocks: List<VisibleChatBlock>): ProcessSummary {
    var operations = 0
    var thoughts = 0
    var errors = 0
    for (block in blocks) when (block) {
        is AgentReasoningBlock -> thoughts++
        is CliOutputBlock -> operations++
        is ToolCallBlock -> {
            operations++
            if (block.tool.state == ToolState.ERROR) errors++
        }
        is ToolGroupBlock -> { operations += block.summary.totalTools; errors += block.summary.errorCount }
        else -> Unit
    }
    return ProcessSummary(operations, thoughts, errors)
}

/** Reuse an overlapping group's identity across streaming, prepend, split and trim.
 * A historical id can only be claimed once; no index/count-based row keys. */
internal class TranscriptProcessProjection {
    private var previous = emptyList<TranscriptItem.Process>()

    fun project(blocks: List<VisibleChatBlock>, protectedIds: Set<String> = emptySet()): List<TranscriptItem> {
        val rawIds = blocks.mapTo(mutableSetOf()) { it.stableId }
        val reserved = rawIds.toMutableSet().apply {
            previous.forEach { add(it.id); add(it.endId) }
        }
        val byMember = buildMap<String, TranscriptItem.Process> {
            previous.forEach { group -> group.blocks.forEach { put(it.stableId, group) } }
        }
        val claimed = mutableSetOf<String>()
        val result = mutableListOf<TranscriptItem>()
        val pending = mutableListOf<VisibleChatBlock>()
        fun flush() {
            if (pending.isEmpty()) return
            val candidates = pending.mapNotNull { byMember[it.stableId] }
                .filter { it.id !in claimed && it.id !in rawIds && it.endId !in rawIds }
            val reused = candidates.groupingBy { it.id }.eachCount().maxByOrNull { it.value }?.key
            val id = reused ?: run {
                val stem = "chat-process:${pending.first().stableId}"
                var next = stem
                var suffix = 1
                while (next in reserved || "$next:end" in reserved) next = "$stem:${suffix++}"
                next
            }
            claimed += id; reserved += id; reserved += "$id:end"
            result += TranscriptItem.Process(id, pending.toList(), summarizeProcess(pending))
            pending.clear()
        }
        for (block in blocks) {
            if (block.stableId !in protectedIds && isProcessStep(block)) pending += block
            else { flush(); result += TranscriptItem.Block(block) }
        }
        flush()
        previous = result.filterIsInstance<TranscriptItem.Process>()
        return result
    }
}

/** Expanded steps remain ordinary LazyColumn rows: never mount a giant nested Column. */
internal fun processDisplayRows(items: List<TranscriptItem>, expanded: Set<String>): List<TranscriptItem> = buildList {
    for (item in items) {
        add(item)
        if (item is TranscriptItem.Process && item.id in expanded) {
            item.blocks.forEach { add(TranscriptItem.Block(it, item.id)) }
            add(TranscriptItem.End(item.id))
        }
    }
}

/** Only finished progress prose folds; final text, media and actions stay visible. */
internal fun desktopTranscriptItems(blocks: List<VisibleChatBlock>, protectedIds: Set<String>, thinking: Boolean,
    activeTurnStartedAt: Long?, projection: TranscriptProcessProjection): List<TranscriptItem> {
    val result = mutableListOf<TranscriptItem>()
    val pending = mutableListOf<VisibleChatBlock>()
    var start: Long? = null
    fun flush(finished: Boolean) {
        if (pending.isEmpty()) return
        if (!finished) result += projection.project(pending.toList(), protectedIds)
        else {
            var finalStart = pending.indexOfLast { it is AgentTextBlock }
            val finalLocalId = (pending.getOrNull(finalStart) as? AgentTextBlock)?.localId
            while (finalLocalId != null && finalStart > 0 && (pending[finalStart - 1] as? AgentTextBlock)?.localId == finalLocalId) finalStart--
            val folded = pending.filterIndexed { index, block -> block.stableId !in protectedIds &&
                (isProcessStep(block) || (block is AgentTextBlock && index < finalStart)) }
            val foldedIds = folded.mapTo(mutableSetOf()) { it.stableId }
            val end = pending.mapNotNull { it.processTimestamp() }.maxOrNull()
            val duration = start?.let { begin -> end?.takeIf { it >= begin }?.minus(begin) }
                ?: pending.mapNotNull { (it as? AgentTextBlock)?.durationMs?.toLong() }.lastOrNull()
            val process = folded.takeIf { it.isNotEmpty() }?.let {
                TranscriptItem.Process("chat-worked:${pending.first().stableId}", it,
                    summarizeProcess(it), finished = true, durationMs = duration)
            }
            var inserted = false
            for (block in pending) {
                if (block.stableId in foldedIds) {
                    if (!inserted && process != null) { result += process; inserted = true }
                } else result += TranscriptItem.Block(block)
            }
        }
        pending.clear()
    }
    for (block in blocks) {
        val boundary = block is UserTextBlock || block is AgentEventBlock || (block is CliOutputBlock && block.source == "user")
        if (boundary) {
            flush(true); result += TranscriptItem.Block(block)
            if (block is UserTextBlock) start = block.invokedAt ?: block.createdAt
        } else { if (start == null) start = block.processTimestamp(); pending += block }
    }
    flush(!thinking)
    if (thinking) result += TranscriptItem.Working(activeTurnStartedAt ?: start)
    return result
}

private fun VisibleChatBlock.processTimestamp(): Long? = when (this) {
    is ChatBlock -> createdAt
    is ToolGroupBlock -> createdAt
}

internal fun workingLabel(working: Boolean, durationMs: Long?): String {
    val prefix = if (working) "Working" else "Worked"
    if (durationMs == null || durationMs < 0) return prefix
    val seconds = durationMs / 1000
    return "$prefix for ${seconds / 60}m ${seconds % 60}s"
}

internal fun processRetainedKeys(items: List<TranscriptItem>): Set<String> = buildSet {
    for (item in items) {
        add(item.id)
        if (item is TranscriptItem.Process) {
            add(item.endId)
            item.blocks.forEach { add(it.stableId) }
        }
    }
}

/** A growing folded summary is real display progress even when its height is unchanged. */
internal fun processProgressKeys(items: List<TranscriptItem>): Set<String> = processRetainedKeys(items).toMutableSet().apply {
    items.filterIsInstance<TranscriptItem.Process>().forEach { add("${it.id}:summary:${it.summary}") }
}
