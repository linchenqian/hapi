package app.hapi.companion.update

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val repo = "linchenqian/hapi"
    private val hash = "ab".repeat(32)
    private fun release(code: Int = 4, draft: Boolean = false, digest: String? = "sha256:$hash",
        url: String = "https://github.com/$repo/releases/download/android-personal-$code/$PERSONAL_APK",
        size: Long = 1234, tag: String = "android-personal-$code", assetName: String = PERSONAL_APK,
        assetState: String = "uploaded") = buildJsonObject {
        put("tag_name", tag); put("name", "Personal $code"); put("draft", draft); put("prerelease", true)
        putJsonArray("assets") {
            add(buildJsonObject {
                put("name", assetName); put("state", assetState); put("size", size)
                if (digest != null) put("digest", digest)
                put("browser_download_url", url)
            })
        }
    }
    private fun parse(vararg values: JsonElement) = latestPersonalRelease(JsonArray(values.toList()).toString(), repo)

    @Test fun selectsHighestPersonalVersionRegardlessOfOrdering() {
        assertEquals(6, parse(release(4), release(6), release(5))?.code)
    }
    @Test fun ignoresUpstreamCliReleases() {
        assertEquals(4, parse(release(99, tag = "v0.34.0"), release(4))?.code)
    }
    @Test fun ignoresDraftsAndIncompleteAssets() {
        assertEquals(4, parse(release(6, draft = true), release(5, assetState = "new"), release(4))?.code)
    }
    @Test fun requiresSha256Digest() {
        for (digest in listOf(null, "", "md5:$hash", "sha256:bad")) assertNull(parse(release(digest = digest)))
    }
    @Test fun requiresBoundedPositiveSize() {
        for (size in listOf(-1, 0, MAX_APK_BYTES + 1)) assertNull(parse(release(size = size)))
        assertNotNull(parse(release(size = MAX_APK_BYTES)))
    }
    @Test fun requiresExactAssetNameAndOwnRepositoryUrl() {
        assertNull(parse(release(assetName = "hapi-cli.apk")))
        for (url in listOf("http://github.com/$repo/update.apk", "https://evil.example/update.apk",
            "https://github.com/other/hapi/releases/download/android-personal-4/$PERSONAL_APK",
            "https://github.com/$repo/releases/download/android-personal-4/$PERSONAL_APK?token=secret")) {
            assertNull(parse(release(url = url)))
        }
    }
    @Test fun ignoresMalformedVersionAndMalformedAdjacentRelease() {
        for (tag in listOf("android-personal-0", "android-personal-01", "android-personal-2147483648", "android-personal--1")) {
            assertNull(parse(release(tag = tag)))
        }
        assertEquals(4, parse(JsonNull, JsonPrimitive("bad"), release(4))?.code)
    }
    @Test fun noReleaseIsNotAParseError() { assertNull(latestPersonalRelease("[]", repo)) }
    @Test fun invalidApiResponseDoesNotLookLikeNoUpdate() {
        assertTrue(runCatching { latestPersonalRelease("""{"message":"rate limited"}""", repo) }.isFailure)
    }
    @Test fun cachedCandidateMustPassSamePolicy() {
        val selected = parse(release())!!
        assertEquals(selected, decodeRelease(encodeRelease(selected), repo))
        assertNull(decodeRelease(encodeRelease(selected), "other/repo"))
        assertNull(decodeRelease("invalid", repo))
    }
    @Test fun redirectsOnlyAllowGithubAssetHostsOverHttps() {
        for (url in listOf("https://github.com/asset", "https://release-assets.githubusercontent.com/asset?sig=abc",
            "https://objects.githubusercontent.com/asset")) assertTrue(allowedDownloadUrl(url))
        for (url in listOf("http://github.com/file", "https://github.com.evil.example/file",
            "https://user:password@github.com/file", "https://github.com:8443/file", "https://evil.example/file",
            "file:///private/file.apk", "https://github.com/file#fragment")) assertFalse(allowedDownloadUrl(url))
    }
    private fun compatible(pkg: String = "run.hapi.companion", code: Long = 4,
        signers: Set<String> = setOf("signer"), expected: Int = 4, min: Int = 26, sdk: Int = 36) =
        compatibleUpdate("run.hapi.companion", 3, setOf("signer"), pkg, code, signers, expected, min, sdk)
    @Test fun acceptsOnlySamePackageNewerExpectedVersionAndExactSigner() {
        assertTrue(compatible())
        assertFalse(compatible(pkg = "evil.app")); assertFalse(compatible(code = 3)); assertFalse(compatible(code = 2))
        assertFalse(compatible(expected = 5)); assertFalse(compatible(signers = emptySet()))
        assertFalse(compatible(signers = setOf("other"))); assertFalse(compatible(signers = setOf("signer", "extra")))
    }
    @Test fun rejectsUnsupportedAndroidVersion() {
        assertTrue(compatible(min = 26, sdk = 26)); assertFalse(compatible(min = 27, sdk = 26))
    }
    @Test fun computesKnownHashForDownloadedBytes() {
        val file = Files.createTempFile("hapi-update-test", ".apk").toFile()
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
            file.appendText("tamper")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
        } finally { file.delete() }
    }
}
