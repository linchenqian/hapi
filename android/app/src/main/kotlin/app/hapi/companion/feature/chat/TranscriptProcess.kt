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
    ) : TranscriptItem {
        override val contentKind: String get() = "process-header"
        val endId: String get() = "$id:end"
    }

    data class End(val processId: String) : TranscriptItem {
        override val id: String get() = "$processId:end"
        override val contentKind: String get() = "process-footer"
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
