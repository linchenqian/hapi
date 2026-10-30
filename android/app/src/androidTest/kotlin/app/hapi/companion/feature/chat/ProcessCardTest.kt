package app.hapi.companion.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.R
import app.hapi.companion.feature.chat.blocks.previewToolCall
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.store.ChatHistoryPagingState
import app.hapi.protocol.chat.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProcessCardTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(uiState(emptyList()))
    private val markdown = MarkdownRenderCache()
    private lateinit var list: LazyListState

    private fun thought(id: String) = AgentReasoningBlock(id, null, 1, 1, text = "Retained thought $id", meta = null)
    private fun prose(id: String, text: String) = AgentTextBlock(id, null, 1, 1, text = text, meta = null)
    private fun user() = UserTextBlock("user", null, 1, 1, "请检查并修复这个问题", null, null, null, null)
    private fun tool(id: String) = previewToolCall(id, "Read", input = mapOf("file_path" to "src/$id.kt"))
    private fun uiState(blocks: List<VisibleChatBlock>) = ChatUiState(
        sessionId = "process-card-test", header = ChatHeaderUi("Test", active = false, thinking = false, subtitle = null),
        flavor = "codex", basePath = null, blocks = blocks, permissionOverrides = emptyMap(),
        hasMore = false, isLoadingOlder = false, isSyncingTail = false, isInitialLoading = false,
        loadFailed = false, warning = null, tailRevision = 0,
    )

    private fun mount(blocks: List<VisibleChatBlock>, restoration: StateRestorationTester? = null) {
        markdown.prepare(blocks.mapNotNull { (it as? AgentTextBlock)?.text ?: (it as? AgentReasoningBlock)?.text }.toSet())
        state.value = uiState(blocks)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            list = rememberLazyListState()
            HapiTheme(darkTheme = true) {
                Surface {
                    CompositionLocalProvider(LocalMarkdownRenderCache provides markdown) {
                        ChatTranscript(state.value, ChatHistoryPagingState(), 0, false, { _, _ -> }, { _, _ -> }, {}, {}, listState = list)
                    }
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun processIsCollapsedByDefaultWhileProseAndFinalReplyStayVisible() {
        val steps = listOf(thought("r1"), tool("t1"), thought("r2"), tool("t2"), thought("r3"), tool("t3"))
        mount(listOf(user(), prose("intro", "我会检查代码并验证修复，过程可按需查看。")) + steps + prose("final", "这是最终回复，默认直接展示。"))
        compose.onNodeWithTag("chat-process-toggle-chat-process:r1").assertIsDisplayed()
        for (step in steps) compose.onNodeWithTag("chat-row-${step.stableId}").assertDoesNotExist()
        compose.onNodeWithText("这是最终回复，默认直接展示。").assertIsDisplayed()
        screenshot("process-card-collapsed.png")
    }

    @Test fun expansionAndNestedReasoningSurviveActivityRecreationThenCollapseFromFooter() {
        val restoration = StateRestorationTester(compose)
        mount(listOf(user(), thought("r1"), tool("t1"), prose("final", "Final answer stays outside")), restoration)
        val toggle = "chat-process-toggle-chat-process:r1"
        compose.onNodeWithTag(toggle).performClick()
        compose.onNodeWithTag("chat-row-t1").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.chat_reasoning_collapsed)).performClick()
        compose.onNodeWithText("Retained thought r1").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chat-row-t1").assertIsDisplayed()
        compose.onNodeWithText("Retained thought r1").assertIsDisplayed()
        screenshot("process-card-expanded.png")
        compose.onNodeWithTag("chat-process-close-chat-process:r1").performClick()
        compose.onNodeWithTag(toggle).assertIsDisplayed()
        compose.onNodeWithTag("chat-row-t1").assertDoesNotExist()
        compose.onNodeWithText("Final answer stays outside").assertIsDisplayed()
        // A manual parent fold does not discard the child's small saved state.
        compose.onNodeWithTag(toggle).performClick()
        compose.onNodeWithText("Retained thought r1").assertIsDisplayed()
    }

    @Test fun streamingErrorsUpdateTheFoldedSummaryWithoutAutomaticallyOpeningIt() {
        mount(listOf(thought("r1"), tool("t1")))
        compose.runOnIdle {
            val failed = tool("failed")
            failed.tool = failed.tool.copy(state = ToolState.ERROR)
            state.value = state.value.copy(blocks = state.value.blocks + failed, messagesVersion = 1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-row-t1").assertDoesNotExist()
        compose.onNodeWithTag("chat-row-failed").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.chat_process_errors, 1)).assertIsDisplayed()
    }

    @Test fun expandedLongProcessesRemainLazyInsteadOfComposingAllOperations() {
        mount((1..1000).map { tool("large-$it") })
        val member = SemanticsMatcher("process member") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chat-row-large-") == true
        }
        assertEquals(0, compose.onAllNodes(member).fetchSemanticsNodes().size)
        compose.onNodeWithTag("chat-process-toggle-chat-process:large-1").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1003, list.layoutInfo.totalItemsCount) }
        val composed = compose.onAllNodes(member).fetchSemanticsNodes().size
        assertTrue("No process steps were displayed", composed > 0)
        assertTrue("Mounted $composed steps instead of a viewport", composed < 30)
    }
}
