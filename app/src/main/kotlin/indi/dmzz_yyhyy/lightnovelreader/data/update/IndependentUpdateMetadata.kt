package indi.dmzz_yyhyy.lightnovelreader.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Metadata generated from the signed independent APK and attached to its release. */
data class IndependentUpdateMetadata(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val apkFile: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    val signingCertificateSha256: String
) {
    companion object {
        private val digestPattern = Regex("[a-fA-F0-9]{64}")
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun parse(body: String): IndependentUpdateMetadata {
            val value = Json.parseToJsonElement(body) as? JsonObject
                ?: throw IllegalArgumentException("Metadata must be a JSON object")
            return IndependentUpdateMetadata(
                schemaVersion = value.requiredInt("schemaVersion"),
                applicationId = value.requiredString("applicationId"),
                versionCode = value.requiredInt("versionCode"),
                versionName = value.requiredString("versionName"),
                apkFile = value.requiredString("apkFile"),
                sizeBytes = value.requiredLong("sizeBytes"),
                sha256 = value.requiredString("sha256"),
                minSdk = value.requiredInt("minSdk"),
                signingCertificateSha256 = value.requiredString("signingCertificateSha256")
            ).also { it.validate() }
        }
    }

    private fun validate() {
        require(schemaVersion == 1) { "Unsupported metadata schema" }
        require(packagePattern.matches(applicationId)) { "Invalid application ID" }
        require(versionCode > 0) { "Invalid version code" }
        require(versionName.isNotBlank() && versionName.none(Char::isISOControl)) { "Invalid version name" }
        require(apkFile.isNotBlank() && apkFile == apkFile.trim() && apkFile.endsWith(".apk") &&
            apkFile.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid APK filename" }
        require(sizeBytes > 0) { "Invalid APK size" }
        require(minSdk > 0) { "Invalid minimum SDK" }
        require(digestPattern.matches(sha256)) { "Invalid APK SHA-256" }
        require(digestPattern.matches(signingCertificateSha256)) { "Invalid signing certificate SHA-256" }
    }
}

internal fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value?.isString == true) { "Missing or invalid $name" }
    return value.content
}

internal fun JsonObject.requiredInt(name: String): Int {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString) { "Missing or invalid $name" }
    return value.intOrNull ?: throw IllegalArgumentException("Missing or invalid $name")
}

internal fun JsonObject.requiredLong(name: String): Long {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString) { "Missing or invalid $name" }
    return value.longOrNull ?: throw IllegalArgumentException("Missing or invalid $name")
}
