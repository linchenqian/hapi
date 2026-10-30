package app.hapi.companion.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.work.*
import app.hapi.companion.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class UpdateCheck { IDLE, CHECKING, CURRENT, NO_RELEASE, FAILED }
// Distinct component identity from the chat attachment provider.
class UpdateFileProvider : FileProvider()

enum class UpdateError { CHECK, DOWNLOAD, INVALID_APK, INSTALL, PERMISSION }
data class UpdateState(
    val automatic: Boolean = true,
    val check: UpdateCheck = UpdateCheck.IDLE,
    val release: UpdateRelease? = null,
    val downloaded: Long? = null,
    val ready: Boolean = false,
    val error: UpdateError? = null,
    val prompt: Boolean = false,
)

/** Personal-channel updater. Its HTTP client is deliberately independent of all paired hubs. */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository = BuildConfig.UPDATE_REPOSITORY
    private val directory = File(context.filesDir, "updates")
    private fun apk(release: UpdateRelease) = File(directory, "${release.code}-${release.sha256}.apk")
    private val cached = prefs.getString("release", null)?.let { decodeRelease(it, repository) }
        ?.takeIf { it.code > BuildConfig.VERSION_CODE }
    private val mutable = MutableStateFlow(UpdateState(
        automatic = prefs.getBoolean("automatic", true), release = cached,
        ready = cached?.let { apk(it).isFile } == true,
        prompt = cached != null && prefs.getInt("dismissed", 0) < cached.code,
    ))
    val state = mutable.asStateFlow()
    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).build()

    fun start() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        schedule()
    }

    private fun schedule() {
        val work = WorkManager.getInstance(context)
        if (state.value.automatic && BuildConfig.SELF_UPDATE_ENABLED) {
            work.enqueueUniquePeriodicWork("personal-app-update", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        } else work.cancelUniqueWork("personal-app-update")
    }

    fun setAutomatic(value: Boolean) {
        prefs.edit().putBoolean("automatic", value).apply()
        mutable.update { it.copy(automatic = value) }
        schedule()
        if (value) check()
    }
    fun onForeground() { if (BuildConfig.SELF_UPDATE_ENABLED) scope.launch { checkNow(false) } }
    fun check() { scope.launch { checkNow(true) } }

    suspend fun checkNow(manual: Boolean) {
        if (!BuildConfig.SELF_UPDATE_ENABLED || downloadJob?.isActive == true || !checkLock.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val last = prefs.getLong("lastAttempt", 0)
            if (!manual && (!state.value.automatic || (now >= last && now - last < TimeUnit.HOURS.toMillis(6)))) return
            prefs.edit().putLong("lastAttempt", now).apply()
            mutable.update { it.copy(check = UpdateCheck.CHECKING, error = null) }
            val release = withContext(Dispatchers.IO) {
                response("https://api.github.com/repos/$repository/releases?per_page=30", metadata = true).use { res ->
                    // A repo without published releases returns []; an inaccessible repo is an error.
                    if (!res.isSuccessful) throw IOException("Release check failed")
                    val body = res.body ?: throw IOException("Empty response")
                    if (body.contentLength() > 2 * 1024 * 1024) throw IOException("Release response too large")
                    val bytes = body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 2 * 1024 * 1024) throw IOException("Release response too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    latestPersonalRelease(bytes.toString(Charsets.UTF_8), repository)
                }
            }
            val candidate = release?.takeIf { it.code > BuildConfig.VERSION_CODE }
            // A slower CDN response must not downgrade a previously verified candidate.
            val best = listOfNotNull(candidate, state.value.release).maxByOrNull { it.code }
            if (best != null) prefs.edit().putString("release", encodeRelease(best)).apply()
            mutable.update { current ->
                current.copy(check = if (release == null) UpdateCheck.NO_RELEASE else UpdateCheck.CURRENT,
                    release = best, ready = best?.let { apk(it).isFile } == true,
                    prompt = best != null && prefs.getInt("dismissed", 0) < best.code,
                )
            }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(check = UpdateCheck.IDLE) }
            throw cancelled
        } catch (_: Exception) {
            mutable.update { it.copy(check = UpdateCheck.FAILED, error = UpdateError.CHECK) }
        } finally { checkLock.unlock() }
    }

    fun dismiss() {
        state.value.release?.let { prefs.edit().putInt("dismissed", it.code).apply() }
        mutable.update { it.copy(prompt = false) }
    }
    fun error(reason: UpdateError) { mutable.update { it.copy(error = reason) } }
    fun cancelDownload() { downloadJob?.cancel() }

    fun download() {
        if (!BuildConfig.SELF_UPDATE_ENABLED || downloadJob?.isActive == true) return
        val release = state.value.release ?: return
        downloadJob = scope.launch(Dispatchers.IO) {
            mutable.update { it.copy(downloaded = 0, ready = false, error = null) }
            directory.mkdirs()
            val part = File(directory, "download.part")
            try {
                response(release.url).use { res ->
                    if (!res.isSuccessful) throw IOException("Download failed")
                    val body = res.body ?: throw IOException("Empty response")
                    if (body.contentLength() != -1L && body.contentLength() != release.size)
                        throw IOException("Unexpected size")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > release.size || total > MAX_APK_BYTES) throw IOException("Oversized APK")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                mutable.update { it.copy(downloaded = total) }
                            }
                            output.fd.sync()
                        }
                    }
                    if (total != release.size || hex(digest.digest()) != release.sha256)
                        throw InvalidApk()
                }
                ensureActive()
                verifyArchive(part, release)
                if (!part.renameTo(apk(release))) throw IOException("Cannot save APK")
                directory.listFiles()?.filter { it != apk(release) }?.forEach { it.delete() }
                mutable.update { it.copy(ready = it.release == release, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidApk) {
                error(UpdateError.INVALID_APK)
            } catch (_: Exception) {
                error(UpdateError.DOWNLOAD)
            } finally {
                part.delete()
                mutable.update { it.copy(downloaded = null) }
            }
        }
    }

    /** Recheck the persisted bytes and signer immediately before granting the installer read access. */
    suspend fun installIntent(expectedCode: Int): Intent? = withContext(Dispatchers.IO) {
        try {
            check(BuildConfig.SELF_UPDATE_ENABLED)
            val release = state.value.release?.takeIf { it.code == expectedCode } ?: throw InvalidApk()
            val file = apk(release)
            if (file.length() != release.size || sha256(file) != release.sha256) throw InvalidApk()
            verifyArchive(file, release)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("HAPI update", uri) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.update { it.copy(ready = false, error = UpdateError.INVALID_APK) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw InvalidApk()
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        if (!compatibleUpdate(context.packageName, version(installed), signers(installed),
                archive.packageName, version(archive), signers(archive), release.code,
                archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, Build.VERSION.SDK_INT)) throw InvalidApk()
    }

    private fun response(initial: String, metadata: Boolean = false): Response {
        var url = initial
        repeat(4) {
            if (!metadata && !allowedDownloadUrl(url)) throw IOException("Untrusted download host")
            val request = Request.Builder().url(url).header("User-Agent", "HAPI-Android-Updater")
                .header("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream").build()
            val res = http.newCall(request).execute()
            if (res.code !in setOf(301, 302, 303, 307, 308)) return res
            val next = res.header("Location")?.let { res.request.url.resolve(it)?.toString() }
            res.close()
            if (metadata || next == null) throw IOException("Unexpected redirect")
            url = next
        }
        throw IOException("Too many redirects")
    }

    private class InvalidApk : IOException()
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
internal fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    hex(digest.digest())
}

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as app.hapi.companion.HapiApp).appGraph
        graph.updater.checkNow(false)
        return Result.success()
    }
}
