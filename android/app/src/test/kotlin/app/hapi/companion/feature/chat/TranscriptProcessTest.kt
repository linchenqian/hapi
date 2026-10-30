package app.hapi.companion.feature.chat

import app.hapi.protocol.chat.*
import org.junit.Assert.*
import org.junit.Test

class TranscriptProcessTest {
    @Test fun `desktop response collapses progress only after completion and keeps the clock start`() {
        val source = listOf(user("u"), answer("intro"), thought("r"), tool("t"), answer("final"))
        val projection = TranscriptProcessProjection()
        val live = desktopTranscriptItems(source, emptySet(), true, 123L, projection)
        assertTrue(live.any { it is TranscriptItem.Block && it.id == "intro" })
        assertEquals(123L, (live.last() as TranscriptItem.Working).startedAt)
        val done = desktopTranscriptItems(source, emptySet(), false, null, projection)
        val worked = done.filterIsInstance<TranscriptItem.Process>().single()
        assertTrue(worked.finished)
        assertEquals(listOf("intro", "r", "t"), worked.blocks.map { it.stableId })
        assertEquals("final", done.last().id)
        assertFalse(done.any { it is TranscriptItem.Working })
        // Live and completed disclosure ids intentionally differ: finish auto-collapses.
        assertFalse(live.filterIsInstance<TranscriptItem.Process>().any { it.id == worked.id })
        assertEquals(done, processDisplayRows(done, emptySet()))
        assertTrue(processDisplayRows(done, setOf(worked.id)).any { it.id == "intro" })
        assertEquals(123L, (desktopTranscriptItems(source, emptySet(), true, 123L, TranscriptProcessProjection()).last() as TranscriptItem.Working).startedAt)
    }

    @Test fun `desktop completed projection keeps media questions and all multipart final text visible`() {
        val a = answer("a").let { AgentTextBlock(it.id, "final", 2, 1, text = it.text, meta = null) }
        val b = answer("b").let { AgentTextBlock(it.id, "final", 3, 1, text = it.text, meta = null) }
        val source = listOf(user("u"), thought("r"), tool("ask", "AskUserQuestion"),
            GeneratedImageBlock("image", null, 1, 1, "image", "image.png", "image/png", null, null), a, b)
        val rows = desktopTranscriptItems(source, emptySet(), false, null, TranscriptProcessProjection())
        assertEquals(listOf("u", "ask", "image", "a", "b"), rows.filterIsInstance<TranscriptItem.Block>().map { it.id })
        assertEquals("Worked for 2m 11s", workingLabel(false, 131999))
        assertEquals("Working", workingLabel(true, null))
    }

    @Test fun `status events cannot create duplicate worked row keys within one response`() {
        val event = AgentEventBlock("event", 2, 1, event = AgentEvent.of(kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("ready"))
        }), meta = null)
        val rows = desktopTranscriptItems(listOf(user("u"), thought("a"), event, thought("b"), answer("final")),
            emptySet(), false, null, TranscriptProcessProjection())
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }
    private fun thought(id: String) = AgentReasoningBlock(id, null, 1, 1, text = "Thinking $id", meta = null)
    private fun answer(id: String) = AgentTextBlock(id, null, 1, 1, text = "Final $id", meta = null)
    private fun user(id: String) = UserTextBlock(id, null, 1, 1, "Question $id", null, null, null, null)
    private fun tool(id: String, name: String = "Read", state: String = ToolState.COMPLETED, permission: ToolPermission? = null) =
        ToolCallBlock(id, null, 1, 1, tool = ChatToolCall(id, name, state, input = null, createdAt = 1, description = null, permission = permission), children = emptyList(), meta = null)

    @Test fun `the screenshot sequence becomes one folded row and the answer stays visible`() {
        val steps = listOf(tool("terminal-1", "CodexBash"), tool("failed", "Bash", ToolState.ERROR), thought("r1"), tool("terminal-2", "CodexBash"), thought("r2"), tool("last"))
        val source = listOf(user("user"), answer("intro")) + steps + answer("answer")
        val sections = TranscriptProcessProjection().project(source)
        assertEquals(4, sections.size)
        val process = sections[2] as TranscriptItem.Process
        assertEquals(steps, process.blocks)
        assertEquals(ProcessSummary(4, 2, 1), process.summary)
        assertEquals(sections, processDisplayRows(sections, emptySet()))
        assertSame(source.last(), (sections.last() as TranscriptItem.Block).block)
        assertEquals(source.size, source.map { it.stableId }.distinct().size)
    }

    @Test fun `expanding shows each retained step in chronological order as a separate virtualized row`() {
        val source = listOf(thought("r"), tool("t"))
        val sections = TranscriptProcessProjection().project(source)
        val group = sections.single() as TranscriptItem.Process
        val expanded = processDisplayRows(sections, setOf(group.id))
        assertSame(group, expanded.first())
        assertEquals(source, expanded.filterIsInstance<TranscriptItem.Block>().map { it.block })
        assertEquals(group.endId, expanded.last().id)
        assertEquals(sections, processDisplayRows(sections, emptySet()))
    }

    @Test fun `prose user messages images system events reviews and user terminal input are never folded`() {
        val boundary = listOf<VisibleChatBlock>(
            user("u"), answer("a"), CliOutputBlock("cli", null, 1, 1, text = "user command", source = "user", meta = null),
            GeneratedImageBlock("image", null, 1, 1, "image", "image.png", "image/png", null, null),
            AgentEventBlock("warning", 1, 1, event = AgentEvent.of(kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("error")) }), meta = null),
        )
        for (block in boundary) {
            val result = TranscriptProcessProjection().project(listOf(thought("r1"), block, thought("r2")))
            assertEquals(3, result.size); assertSame(block, (result[1] as TranscriptItem.Block).block)
        }
    }

    @Test fun `questions proposals subagent processes and pending approvals remain directly visible`() {
        for (name in listOf("AskUserQuestion", "ask_user_question", "request_user_input", "CodexPermission", "ExitPlanMode", "exit_plan_mode", "CodexAgent", "Task", "Agent")) {
            val block = tool("interactive", name, ToolState.RUNNING)
            assertFalse("Must not fold $name", isProcessStep(block))
        }
        val pending = tool("approval", "Bash", ToolState.PENDING, ToolPermission(id = "p", status = "pending", presence = setOf("id", "status")))
        assertFalse(isProcessStep(pending))
        val nested = tool("parent").let { ToolCallBlock(it.id, null, 1, 1, tool = it.tool, children = listOf(pending), meta = null) }
        assertFalse(isProcessStep(nested))
        val stripped = tool("has-child-process")
        assertTrue(TranscriptProcessProjection().project(listOf(stripped), setOf(stripped.id)).single() is TranscriptItem.Block)
    }

    @Test fun `prepending and streaming retain the folded group identity`() {
        val projection = TranscriptProcessProjection()
        val initial = projection.project(listOf(thought("r1"), tool("t1"))).single() as TranscriptItem.Process
        val older = projection.project(listOf(tool("older"), thought("r1"), tool("t1"))).single() as TranscriptItem.Process
        val grown = projection.project(listOf(tool("older"), thought("r1"), tool("t1"), thought("r2"), tool("t2"))).single() as TranscriptItem.Process
        assertEquals(initial.id, older.id); assertEquals(initial.id, grown.id)
        assertEquals(5, grown.blocks.size)
    }

    @Test fun `splits cannot give two groups the same key and preserve every step`() {
        val projection = TranscriptProcessProjection()
        val initial = listOf(tool("a"), thought("b"), tool("c"))
        projection.project(initial)
        val split = projection.project(listOf(initial[0], answer("boundary"), initial[1], initial[2]))
        val groups = split.filterIsInstance<TranscriptItem.Process>()
        assertEquals(2, groups.map { it.id }.distinct().size)
        assertEquals(initial, groups.flatMap { it.blocks })
        val display = processDisplayRows(split, groups.mapTo(mutableSetOf()) { it.id })
        assertEquals(display.size, display.map { it.id }.distinct().size)
        val repeated = projection.project(listOf(initial[0], answer("boundary"), initial[1], initial[2]))
        assertEquals(groups.map { it.id }, repeated.filterIsInstance<TranscriptItem.Process>().map { it.id })
    }

    @Test fun `summary ids do not collide with ordinary message or footer ids`() {
        val result = TranscriptProcessProjection().project(listOf(answer("chat-process:r"), answer("chat-process:r:1:end"), thought("r")))
        val group = result.last() as TranscriptItem.Process
        val expanded = processDisplayRows(result, setOf(group.id))
        assertEquals(expanded.size, expanded.map { it.id }.distinct().size)
    }

    @Test fun `large folded runs mount only one row but retain all data for explicit expansion`() {
        val source = (1..10_000).map { tool("t$it") }
        val result = TranscriptProcessProjection().project(source)
        val group = result.single() as TranscriptItem.Process
        assertEquals(ProcessSummary(10_000, 0, 0), group.summary)
        assertEquals(1, processDisplayRows(result, emptySet()).size)
        assertEquals(10_002, processDisplayRows(result, setOf(group.id)).size)
        assertTrue(processRetainedKeys(result).containsAll(source.map { it.id }))
    }

    @Test fun `folded pagination growth is progress even when header key and height stay unchanged`() {
        val projection = TranscriptProcessProjection()
        val before = projection.project(listOf(thought("r1"), tool("t")))
        val after = projection.project(listOf(thought("older"), thought("r1"), tool("t")))
        assertEquals(before.first().id, after.first().id)
        assertTrue(processProgressKeys(after).any { it !in processProgressKeys(before) })
    }

    @Test fun `projected tool summaries count all tools without retaining their heavy payloads`() {
        val group = buildVisibleChatBlocks((1..7).map { tool("g$it") }, ToolGroupingOptions(false)).filterIsInstance<ToolGroupBlock>().single()
        val stripped = TranscriptProjection().project(listOf(group)).single() as ToolGroupBlock
        assertTrue(stripped.tools.isEmpty())
        val folded = TranscriptProcessProjection().project(listOf(thought("r"), stripped)).single() as TranscriptItem.Process
        assertEquals(ProcessSummary(7, 1, 0), folded.summary)
    }
}
