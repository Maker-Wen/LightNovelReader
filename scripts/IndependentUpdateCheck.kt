import indi.dmzz_yyhyy.lightnovelreader.data.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.IOException

private const val APP_ID = "io.github.makerwen.lightnovelreader"
private const val APK_NAME = "LightNovelReader-1.3.0.apk"
private const val PAGE = "https://github.com/Maker-Wen/LightNovelReader/releases/tag/mw-10300012"
private const val ASSET_BASE = "https://github.com/Maker-Wen/LightNovelReader/releases/download/mw-10300012/"
private const val METADATA_URL = ASSET_BASE + "update.json"
private const val APK_SIZE = 12000000L

private val metadata = buildJsonObject {
    put("schemaVersion", 1)
    put("applicationId", APP_ID)
    put("versionCode", 10300012)
    put("versionName", "1.3.0_MakerWen (2026/10/04)")
    put("apkFile", APK_NAME)
    put("sizeBytes", APK_SIZE)
    put("sha256", "a".repeat(64))
    put("minSdk", 24)
    put("signingCertificateSha256", "b".repeat(64))
}

private fun JsonObject.changed(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(key, value) })

private fun asset(name: String, size: Long, url: String = ASSET_BASE + name, state: String = "uploaded") = buildJsonObject {
    put("name", name)
    put("size", size)
    put("state", state)
    put("browser_download_url", url)
}

private fun release(
    metadataBody: String,
    assets: JsonArray = JsonArray(listOf(asset("update.json", metadataBody.toByteArray().size.toLong()), asset(APK_NAME, APK_SIZE)))
) = buildJsonObject {
    put("tag_name", "mw-10300012")
    put("html_url", PAGE)
    put("draft", false)
    put("prerelease", false)
    put("body", "Improved independent settings.")
    put("assets", assets)
}

private data class Fixture(
    val metadataBody: String = metadata.toString(),
    val releaseBody: String = release(metadataBody).toString(),
    val releaseStatus: Int = 200,
    val metadataStatus: Int = 200
) {
    val requests = mutableListOf<String>()
    val source = IndependentGitHubUpdateSource { url ->
        requests += url
        when (url) {
            IndependentGitHubUpdateSource.LATEST_RELEASE_API -> IndependentUpdateHttpResponse(releaseStatus, releaseBody)
            METADATA_URL -> IndependentUpdateHttpResponse(metadataStatus, metadataBody)
            else -> error("Unexpected URL: $url")
        }
    }
    suspend fun check(version: Int = 10300011, sdk: Int = 37) = source.check(APP_ID, version, sdk)
}

private var checks = 0
private fun assertCase(name: String, passed: Boolean) {
    check(passed) { name }
    checks++
}

private fun isFailure(result: IndependentUpdateCheckResult, reason: IndependentUpdateFailure) =
    result is IndependentUpdateCheckResult.Failed && result.reason == reason

fun main() = runBlocking {
    val availableFixture = Fixture()
    val available = availableFixture.check() as IndependentUpdateCheckResult.Available
    assertCase("higher integer version is available", available.release.version == 10300012)
    assertCase("links and notes are bound to the same release", available.release.releasePageUrl == PAGE &&
        available.release.downloadUrl == ASSET_BASE + APK_NAME && available.release.releaseNotes == "Improved independent settings.")
    assertCase("only own latest endpoint and bound metadata asset are requested", availableFixture.requests ==
        listOf(IndependentGitHubUpdateSource.LATEST_RELEASE_API, METADATA_URL))
    assertCase("equal version is up to date", Fixture().check(10300012) is IndependentUpdateCheckResult.UpToDate)
    assertCase("older release is up to date", Fixture().check(10300013) is IndependentUpdateCheckResult.UpToDate)

    val noRelease = Fixture(releaseStatus = 404)
    assertCase("latest 404 means no published release", noRelease.check() == IndependentUpdateCheckResult.NotPublished)
    assertCase("no metadata request after latest 404", noRelease.requests.size == 1)
    for (status in listOf(401, 403, 429, 500)) {
        val result = Fixture(releaseStatus = status).check()
        assertCase("latest HTTP $status remains a failure", isFailure(result, IndependentUpdateFailure.HTTP) &&
            (result as IndependentUpdateCheckResult.Failed).httpStatus == status)
    }
    for (status in listOf(404, 500)) {
        assertCase("metadata HTTP $status is not no-release/up-to-date", isFailure(Fixture(metadataStatus = status).check(), IndependentUpdateFailure.HTTP))
    }
    val offline = IndependentGitHubUpdateSource { throw IOException("offline") }.check(APP_ID, 10300011, 37)
    assertCase("network failure is not up-to-date", isFailure(offline, IndependentUpdateFailure.NETWORK))

    for (flag in listOf("draft", "prerelease")) {
        assertCase("$flag is excluded", isFailure(Fixture(releaseBody = release(metadata.toString()).changed(flag, JsonPrimitive(true)).toString()).check(),
            IndependentUpdateFailure.INVALID_RELEASE))
    }
    assertCase("invalid release JSON is rejected", isFailure(Fixture(releaseBody = "{}").check(), IndependentUpdateFailure.INVALID_RELEASE))
    for (url in listOf(
        PAGE.replace("Maker-Wen", "other"),
        PAGE.replace("mw-10300012", "different-tag"),
        PAGE.replace("https:", "http:"),
        "$PAGE?asset=1", "$PAGE#notes", PAGE.replace("github.com", "github.com.evil.example"),
        PAGE.replace("github.com", "user@github.com")
    )) {
        assertCase("unbound release page is rejected: $url", isFailure(Fixture(releaseBody = release(metadata.toString()).changed("html_url", JsonPrimitive(url)).toString()).check(),
            IndependentUpdateFailure.INVALID_RELEASE))
    }

    val validMetadataAsset = asset("update.json", metadata.toString().toByteArray().size.toLong())
    val validApkAsset = asset(APK_NAME, APK_SIZE)
    val invalidAssetLists = listOf(
        listOf(validApkAsset),
        listOf(validMetadataAsset, validMetadataAsset, validApkAsset),
        listOf(validMetadataAsset),
        listOf(validMetadataAsset, validApkAsset, validApkAsset),
        listOf(validMetadataAsset, asset("other.apk", APK_SIZE)),
        listOf(validMetadataAsset, asset(APK_NAME, APK_SIZE - 1)),
        listOf(validMetadataAsset, asset(APK_NAME, APK_SIZE, state = "new")),
        listOf(validMetadataAsset, asset(APK_NAME, APK_SIZE, ASSET_BASE.replace("Maker-Wen", "other") + APK_NAME)),
        listOf(validMetadataAsset, asset(APK_NAME, APK_SIZE, ASSET_BASE.replace("mw-10300012", "different-tag") + APK_NAME)),
        listOf(asset("update.json", 1048577), validApkAsset)
    )
    for ((index, assets) in invalidAssetLists.withIndex()) {
        assertCase("missing, ambiguous, unbound, or mismatched assets $index fail", isFailure(Fixture(releaseBody =
            release(metadata.toString(), JsonArray(assets)).toString()).check(), IndependentUpdateFailure.INVALID_RELEASE))
    }
    val invalidMetadata = listOf(
        "not JSON", "[]", "{}",
        JsonObject(metadata.toMutableMap().apply { remove("sha256") }).toString(),
        metadata.changed("schemaVersion", JsonPrimitive(2)).toString(),
        metadata.changed("versionCode", JsonPrimitive("10300012")).toString(),
        metadata.changed("versionCode", JsonPrimitive(2147483648L)).toString(),
        metadata.changed("versionCode", JsonPrimitive(-1)).toString(),
        metadata.changed("versionName", JsonPrimitive("")).toString(),
        metadata.changed("apkFile", JsonPrimitive("../independent.apk")).toString(),
        metadata.changed("apkFile", JsonPrimitive("dir\\independent.apk")).toString(),
        metadata.changed("sizeBytes", JsonPrimitive(0)).toString(),
        metadata.changed("minSdk", JsonPrimitive(0)).toString(),
        metadata.changed("sha256", JsonPrimitive("bad")).toString(),
        metadata.changed("signingCertificateSha256", JsonPrimitive("z".repeat(64))).toString()
    )
    for ((index, body) in invalidMetadata.withIndex()) {
        assertCase("malformed or incomplete metadata $index fails", isFailure(Fixture(metadataBody = body).check(), IndependentUpdateFailure.INVALID_METADATA))
    }
    assertCase("metadata asset declared size must match received bytes", isFailure(Fixture(releaseBody =
        release(metadata.toString(), JsonArray(listOf(asset("update.json", 1), validApkAsset))).toString()).check(), IndependentUpdateFailure.INVALID_METADATA))
    assertCase("wrong application ID is incompatible", Fixture(metadataBody = metadata.changed("applicationId", JsonPrimitive("other.application.id")).toString()).check() ==
        IndependentUpdateCheckResult.Incompatible)
    assertCase("unsupported device SDK is incompatible", Fixture().check(sdk = 23) == IndependentUpdateCheckResult.Incompatible)

    var propagated = false
    try {
        IndependentGitHubUpdateSource { throw CancellationException("cancelled") }.check(APP_ID, 10300011, 37)
    } catch (_: CancellationException) { propagated = true }
    assertCase("transport cancellation is propagated", propagated)
    for (cancelAfterMetadata in listOf(false, true)) {
        val deferred = async {
            IndependentGitHubUpdateSource { url ->
                if (url == IndependentGitHubUpdateSource.LATEST_RELEASE_API) {
                    if (!cancelAfterMetadata) currentCoroutineContext().cancel()
                    IndependentUpdateHttpResponse(if (cancelAfterMetadata) 200 else 404, release(metadata.toString()).toString())
                } else {
                    currentCoroutineContext().cancel()
                    IndependentUpdateHttpResponse(200, metadata.toString())
                }
            }.check(APP_ID, 10300011, 37)
        }
        propagated = false
        try { deferred.await() } catch (_: CancellationException) { propagated = true }
        assertCase("cancelled context is checked after ${if (cancelAfterMetadata) "metadata" else "latest"} response", propagated)
    }
    println("Independent update checks passed ($checks assertions; deterministic HTTP responses, no network or APK installation).")
}
