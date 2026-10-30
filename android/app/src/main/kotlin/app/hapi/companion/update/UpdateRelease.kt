package app.hapi.companion.update

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val MAX_APK_BYTES = 128L * 1024 * 1024
const val PERSONAL_APK = "hapi-android-personal.apk"
private val updateJson = Json { ignoreUnknownKeys = true }

@Serializable
data class UpdateRelease(
    val code: Int,
    val name: String,
    val tag: String,
    val url: String,
    val size: Long,
    val sha256: String,
) {
    fun validFor(repository: String): Boolean =
        code > 0 && tag == "android-personal-$code" && size in 1..MAX_APK_BYTES &&
            sha256.matches(Regex("[a-f0-9]{64}")) &&
            url == "https://github.com/$repository/releases/download/$tag/$PERSONAL_APK"
}

/** Ignore CLI/upstream releases and incomplete uploads. Never infer versions from labels. */
fun latestPersonalRelease(body: String, repository: String): UpdateRelease? =
    updateJson.parseToJsonElement(body).jsonArray.mapNotNull { element ->
        runCatching {
            val release = element.jsonObject
            if (release["draft"]?.jsonPrimitive?.booleanOrNull != false) return@runCatching null
            val tag = release["tag_name"]!!.jsonPrimitive.content
            val code = Regex("android-personal-([1-9][0-9]*)").matchEntire(tag)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching null
            val asset = release["assets"]!!.jsonArray.singleOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.content == PERSONAL_APK
            }?.jsonObject ?: return@runCatching null
            if (asset["state"]?.jsonPrimitive?.content != "uploaded") return@runCatching null
            val digest = asset["digest"]!!.jsonPrimitive.content
            if (!digest.startsWith("sha256:")) return@runCatching null
            UpdateRelease(
                code, release["name"]?.jsonPrimitive?.contentOrNull?.take(120) ?: tag,
                tag, asset["browser_download_url"]!!.jsonPrimitive.content,
                asset["size"]!!.jsonPrimitive.long, digest.removePrefix("sha256:").lowercase(),
            ).takeIf { it.validFor(repository) }
        }.getOrNull()
    }.maxByOrNull { it.code }

fun encodeRelease(release: UpdateRelease): String = updateJson.encodeToString(release)
fun decodeRelease(value: String, repository: String): UpdateRelease? =
    runCatching { updateJson.decodeFromString<UpdateRelease>(value).takeIf { it.validFor(repository) } }.getOrNull()

/** Redirects use a fresh, credential-free request; reject all non-GitHub destinations. */
fun allowedDownloadUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
        uri.port in listOf(-1, 443) &&
        uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}.getOrDefault(false)

/** Exact signer continuity: intentionally do not migrate to an unrelated upstream/Play key. */
fun compatibleUpdate(
    installedPackage: String, installedCode: Long, installedSigners: Set<String>,
    archivePackage: String, archiveCode: Long, archiveSigners: Set<String>,
    expectedCode: Int, minSdk: Int, deviceSdk: Int,
): Boolean = archivePackage == installedPackage && archiveCode == expectedCode.toLong() &&
    archiveCode > installedCode && minSdk <= deviceSdk &&
    installedSigners.isNotEmpty() && archiveSigners == installedSigners
