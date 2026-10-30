package app.hapi.companion.update

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.test.platform.app.InstrumentationRegistry
import app.hapi.companion.BuildConfig
import app.hapi.companion.ui.theme.HapiTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only emulator test. Supply a same-signer, newer APK as probe.apk in external files.
 * No hub calls, installed-app replacement, source-permission change or silent install. */
class UpdateSmokeTest {
    /** Opt-in check against the real public release, never a mock server. */
    @Test fun downloadsPublishedUpdateFromGitHub() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publishedUpdate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("automatic", false).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val updater = AppUpdater(context, scope)
            updater.checkNow(true)
            assertNull("Live GitHub release check", updater.state.value.error)
            val candidate = updater.state.value.release
            assertNotNull("Run on a build older than the published release", candidate)
            assertTrue(candidate!!.code > BuildConfig.VERSION_CODE)
            updater.download()
            val completed = withTimeout(180_000) {
                updater.state.first { it.ready || (it.error != null && it.downloaded == null) }
            }
            assertNull("Live asset download and signature verification", completed.error)
            assertTrue(completed.ready)
            val intent = updater.installIntent(candidate.code)
            assertNotNull("Downloaded package revalidated before installer handoff", intent)
            assertEquals(candidate.size, context.contentResolver.openInputStream(intent!!.data!!)!!.use { it.readBytes().size.toLong() })
        } finally { scope.cancel() }
    }

    @Test fun verifiesRealArchiveAndRendersUpdateSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = File(context.getExternalFilesDir(null), "probe.apk")
        assumeTrue("Optional newer APK probe not supplied", probe.isFile)
        val code = BuildConfig.VERSION_CODE + 1
        val hash = sha256(probe)
        val release = UpdateRelease(code, "Smoke $code", "android-personal-$code",
            "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/android-personal-$code/$PERSONAL_APK",
            probe.length(), hash)
        val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("automatic", false).putString("release", encodeRelease(release)).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updater = AppUpdater(context, scope)
        val privateApk = File(context.filesDir, "updates/$code-$hash.apk")
        privateApk.parentFile!!.mkdirs()
        probe.copyTo(privateApk, overwrite = true)
        try {
            val intent = updater.installIntent(code)
            assertNotNull("Real APK must match installed package, expected version and signer", intent)
            assertEquals(Intent.ACTION_VIEW, intent!!.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(probe.length(), context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes().size.toLong() })

            val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
            context.startActivity(Intent(context, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as ComponentActivity
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                activity.setContent { HapiTheme { Surface { androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    UpdateSettings(updater)
                } } } }
            }
            fun find(node: AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString() == text) return true
                return (0 until node.childCount).any { find(node.getChild(it), text) }
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!find(instrumentation.uiAutomation.rootInActiveWindow, "App updates") && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("Settings update section rendered", find(instrumentation.uiAutomation.rootInActiveWindow, "App updates"))
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "update-settings.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            // The external installer, not this app, retains final installation authority.
            // Use UiAutomation to open the generated intent only when the test harness requests it.
            if (InstrumentationRegistry.getArguments().getString("openInstaller") == "true") {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(1500)
                assertTrue("Installer is foreground", instrumentation.uiAutomation.rootInActiveWindow?.packageName != context.packageName)
            }
            privateApk.appendText("tampered")
            assertNull("Rechecking cached bytes rejects post-download tampering", updater.installIntent(code))
            assertEquals(UpdateError.INVALID_APK, updater.state.value.error)
            instrumentation.runOnMainSync { activity.finish() }
        } finally {
            scope.cancel()
            privateApk.delete()
            prefs.edit().clear().commit()
        }
    }
}
