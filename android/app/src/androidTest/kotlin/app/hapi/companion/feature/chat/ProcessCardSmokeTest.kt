package app.hapi.companion.feature.chat

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.feature.chat.blocks.previewToolCall
import app.hapi.companion.ui.markdown.LocalMarkdownRenderCache
import app.hapi.companion.ui.markdown.MarkdownRenderCache
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.data.store.ChatHistoryPagingState
import app.hapi.protocol.chat.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Native input smoke path, independent of Espresso's InputManager
 * reflection (unsupported on some newer emulator system images). No API calls. */
class ProcessCardSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var list: LazyListState? = null
    private var top = 0f
    private var left = 0f
    private var width = 0

    private fun row(key: String): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        instrumentation.runOnMainSync {
            result = list?.layoutInfo?.visibleItemsInfo?.find { it.key == key }?.let { it.offset to it.size }
        }
        return result
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) fail("Timed out: $label")
            SystemClock.sleep(100)
        }
    }

    private fun click(key: String, fraction: Float = 0.5f) {
        val bounds = row(key) ?: error("No visible row: $key")
        val x = left + width * fraction
        val y = top + bounds.first + bounds.second / 2f
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun foldedProcessRendersAndExpandsWithoutHidingTheFinalReply() {
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        context.startActivity(Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? ComponentActivity
        instrumentation.removeMonitor(monitor)
        assertNotNull("Debug test Activity did not start", activity)
        val finalText = "这是最终回复，默认直接展示。"
        val reasoning = "Retained reasoning detail"
        val toolFile = "PROCESS_STEP.kt"
        val blocks = listOf(
            UserTextBlock("u", null, 1, 1, "请检查并修复这个问题", null, null, null, null),
            AgentTextBlock("intro", null, 1, 1, text = "我会检查代码并验证修复，过程可按需查看。", meta = null),
            AgentReasoningBlock("r", null, 1, 1, text = reasoning, meta = null),
            previewToolCall("t", "Read", input = mapOf("file_path" to "src/$toolFile")),
            AgentTextBlock("answer", null, 1, 1, text = finalText, meta = null),
        )
        val cache = MarkdownRenderCache()
        cache.prepare(setOf(reasoning, finalText, "我会检查代码并验证修复，过程可按需查看。"))
        val state = ChatUiState("smoke", ChatHeaderUi("Test", active = false, thinking = false, subtitle = null),
            "codex", null, blocks, emptyMap(), false, false, false, false, false, null, 0)
        try {
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 27) {
                    activity!!.setTurnScreenOn(true)
                    activity.setShowWhenLocked(true)
                }
                activity!!.setContent {
                    list = rememberLazyListState()
                    HapiTheme(darkTheme = true) { Surface {
                        CompositionLocalProvider(LocalMarkdownRenderCache provides cache) {
                            ChatTranscript(state, ChatHistoryPagingState(), 0, false, { _, _ -> }, { _, _ -> }, {}, {}, listState = list!!,
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    val location = IntArray(2)
                                    activity.window.decorView.getLocationOnScreen(location)
                                    top = coordinates.positionInWindow().y + location[1]
                                    left = coordinates.positionInWindow().x + location[0]
                                    width = coordinates.size.width
                                })
                        }
                    } }
                }
            }
            await("collapsed card and final reply") { row("chat-process:r") != null && row("answer") != null }
            assertNull("A tool leaked out of the folded card", row("t"))
            assertNull("Reasoning leaked out of the folded card", row("r"))
            screenshot("process-card-collapsed.png")
            click("chat-process:r")
            await("expanded tool") { row("t") != null }
            val thoughtHeight = row("r")!!.second
            click("r", 0.2f)
            await("expanded reasoning") { (row("r")?.second ?: 0) > thoughtHeight }
            screenshot("process-card-expanded.png")
            click("chat-process:r:end")
            await("collapsed again") { row("t") == null && row("chat-process:r") != null && row("answer") != null }
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
