package indi.dmzz_yyhyy.lightnovelreader.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

data class IndependentRelease(
    val metadata: IndependentUpdateMetadata,
    override val releaseNotes: String,
    override val downloadUrl: String,
    override val releasePageUrl: String
) : Release {
    override val version: Int get() = metadata.versionCode
    override val versionName: String get() = metadata.versionName
}

data class IndependentUpdateHttpResponse(val statusCode: Int, val body: String)

fun interface IndependentUpdateTransport {
    suspend fun get(url: String): IndependentUpdateHttpResponse
}

enum class IndependentUpdateFailure { HTTP, INVALID_METADATA, INVALID_RELEASE, NETWORK }

sealed interface IndependentUpdateCheckResult {
    data class Available(val release: IndependentRelease) : IndependentUpdateCheckResult
    data class UpToDate(val release: IndependentRelease) : IndependentUpdateCheckResult
    data object NotPublished : IndependentUpdateCheckResult
    data object Incompatible : IndependentUpdateCheckResult
    data class Failed(
        val reason: IndependentUpdateFailure,
        val httpStatus: Int? = null,
        val cause: Exception? = null
    ) : IndependentUpdateCheckResult
}

/** Uses one official release and its assets; metadata never supplies URLs. */
class IndependentGitHubUpdateSource(private val transport: IndependentUpdateTransport) {
    companion object {
        const val REPOSITORY = "Maker-Wen/LightNovelReader"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }

    suspend fun check(applicationId: String, versionCode: Int, sdkInt: Int): IndependentUpdateCheckResult {
        return try {
            val response = transport.get(LATEST_RELEASE_API)
            currentCoroutineContext().ensureActive()
            if (response.statusCode == 404) return IndependentUpdateCheckResult.NotPublished
            if (response.statusCode != 200) return httpFailure(response.statusCode)

            val release = try {
                parseRelease(response.body)
            } catch (e: IllegalArgumentException) {
                return IndependentUpdateCheckResult.Failed(IndependentUpdateFailure.INVALID_RELEASE, cause = e)
            }
            val metadataResponse = transport.get(release.metadataAsset.url)
            currentCoroutineContext().ensureActive()
            if (metadataResponse.statusCode != 200) return httpFailure(metadataResponse.statusCode)
            val metadata = try {
                require(metadataResponse.body.toByteArray(Charsets.UTF_8).size.toLong() == release.metadataAsset.size)
                IndependentUpdateMetadata.parse(metadataResponse.body)
            } catch (e: IllegalArgumentException) {
                return IndependentUpdateCheckResult.Failed(IndependentUpdateFailure.INVALID_METADATA, cause = e)
            }
            if (metadata.applicationId != applicationId || metadata.minSdk > sdkInt) {
                return IndependentUpdateCheckResult.Incompatible
            }
            val apk = try {
                release.asset(metadata.apkFile).also { require(it.size == metadata.sizeBytes) }
            } catch (e: IllegalArgumentException) {
                return IndependentUpdateCheckResult.Failed(IndependentUpdateFailure.INVALID_RELEASE, cause = e)
            }
            val available = IndependentRelease(metadata, release.notes, apk.url, release.pageUrl)
            currentCoroutineContext().ensureActive()
            if (metadata.versionCode > versionCode) IndependentUpdateCheckResult.Available(available)
            else IndependentUpdateCheckResult.UpToDate(available)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IndependentUpdateCheckResult.Failed(IndependentUpdateFailure.NETWORK, cause = e)
        }
    }

    private fun httpFailure(status: Int) =
        IndependentUpdateCheckResult.Failed(IndependentUpdateFailure.HTTP, httpStatus = status)

    private fun parseRelease(body: String): PublishedRelease {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        val value = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Release must be a JSON object")
        require(value.requiredBoolean("draft") == false && value.requiredBoolean("prerelease") == false)
        val tag = value.requiredString("tag_name").also { require(it.isNotBlank()) }
        val pageUrl = value.requiredString("html_url")
        requireRepositoryUrl(pageUrl, "/releases/tag/$tag")
        val assets = value["assets"] as? JsonArray ?: throw IllegalArgumentException("Missing release assets")
        val notes = (value["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return PublishedRelease(tag, pageUrl, notes, assets).also {
            require(it.metadataAsset.size in 1..MAX_RESPONSE_BYTES.toLong())
        }
    }

    private data class Asset(val url: String, val size: Long)

    private class PublishedRelease(
        private val tag: String,
        val pageUrl: String,
        val notes: String,
        private val assets: JsonArray
    ) {
        val metadataAsset: Asset get() = asset("update.json")

        fun asset(name: String): Asset {
            val matches = assets.filterIsInstance<JsonObject>().filter {
                (it["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content == name
            }
            require(matches.size == 1) { "Missing or ambiguous $name asset" }
            val asset = matches.single()
            require(asset.requiredString("state") == "uploaded") { "Asset is not uploaded" }
            val size = asset.requiredLong("size").also { require(it > 0) }
            val url = asset.requiredString("browser_download_url")
            requireRepositoryUrl(url, "/releases/download/$tag/$name")
            return Asset(url, size)
        }
    }
}

private fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString) { "Missing or invalid $name" }
    return value.booleanOrNull ?: throw IllegalArgumentException("Missing or invalid $name")
}

private fun requireRepositoryUrl(value: String, suffix: String) {
    val uri = try { URI(value) } catch (e: Exception) { throw IllegalArgumentException("Invalid release URL", e) }
    require(uri.scheme == "https" && uri.host?.equals("github.com", ignoreCase = true) == true &&
        uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.path == "/${IndependentGitHubUpdateSource.REPOSITORY}$suffix") { "URL belongs to a different release" }
}
