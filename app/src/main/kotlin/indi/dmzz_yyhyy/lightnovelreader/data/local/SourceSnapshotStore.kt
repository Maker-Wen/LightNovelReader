package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Source snapshots and the file half of a database/file transaction.
 *
 * The caller serializes operations and commits [COMMIT_MARKER_PATH] to Room in the same transaction
 * as the imported data, after [PendingChange.apply]. Recovery keeps the staged files only when that
 * marker matches the journal. The immutable before directory makes rollback repeatable even if the
 * process stops while recovering.
 */
@OptIn(ExperimentalSerializationApi::class)
class SourceSnapshotStore(val directory: File) {
    val transactionDirectory = File(requireNotNull(directory.absoluteFile.parentFile), "${directory.name}.transaction")

    @Synchronized
    fun readAll(excludeSourceIds: Set<Identifier> = emptySet()): Map<Identifier, LocalData> =
        readSnapshots(excludeSourceIds.map(::normalizeSourceId).toSet())

    @Synchronized
    fun read(sourceId: Identifier): LocalData? {
        val normalized = normalizeSourceId(sourceId)
        return readSnapshots(emptySet(), normalized)[normalized]
    }

    private fun readSnapshots(
        excludedIds: Set<Identifier>,
        onlySourceId: Identifier? = null
    ): Map<Identifier, LocalData> {
        if (!directory.exists()) return emptyMap()
        check(directory.isDirectory) { "Source snapshot path is not a directory: $directory" }
        val files = directory.listFiles() ?: error("Cannot list source snapshots: $directory")
        val snapshots = files.sortedBy { it.name }.mapNotNull { file ->
            val sourceId = if (onlySourceId == null) sourceIdFromFilename(file.name) else {
                // A targeted read does not depend on unrelated invalid snapshots. Full exports
                // and merged directory replacements still fail explicitly on those files.
                runCatching { sourceIdFromFilename(file.name) }.getOrNull() ?: return@mapNotNull null
            }
            if (sourceId in excludedIds || (onlySourceId != null && sourceId != onlySourceId)) {
                return@mapNotNull null
            }
            check(file.isFile) { "Invalid source snapshot: $file" }
            check(file.canonicalFile.parentFile == directory.canonicalFile) {
                "Source snapshot points outside its directory: $file"
            }
            val data = file.inputStream().use { decodeLocalData(it) }
            val payloadId = normalizeSourceId(requireNotNull(data.webBookDataSourceId) {
                "Source snapshot has no source ID: $file"
            })
            check(payloadId == sourceId) { "Source snapshot ID differs from its filename: $file" }
            Triple(sourceId, file.name == sourceId.toString(), data.copy(webBookDataSourceId = sourceId))
        }
        val result = linkedMapOf<Identifier, LocalData>()
        // Canonical snapshots are authoritative for conflicts, but legacy-only entities survive.
        snapshots.sortedByDescending { it.second }.forEach { (id, _, data) ->
            result[id] = result[id]?.let { mergeLocalData(it, data) } ?: data
        }
        return result
    }

    /** Stage a complete replacement, without changing the live directory. */
    @Synchronized
    fun prepare(
        replacements: Map<Identifier, LocalData>,
        replaceAll: Boolean = false
    ): PendingChange {
        check(!transactionDirectory.exists()) { "A source snapshot transaction needs recovery" }
        val normalized = linkedMapOf<Identifier, LocalData>()
        replacements.forEach { (rawId, data) ->
            val id = normalizeSourceId(rawId)
            val payloadId = normalizeSourceId(requireNotNull(data.webBookDataSourceId) {
                "Source snapshot has no source ID"
            })
            require(id == payloadId) { "Replacement source ID differs from its payload" }
            require(!normalized.containsKey(id)) { "Duplicate replacement source ID: $id" }
            normalized[id] = data.copy(webBookDataSourceId = id)
        }
        val after = if (replaceAll) linkedMapOf() else LinkedHashMap(readAll(normalized.keys))
        after.putAll(normalized)
        val id = UUID.randomUUID().toString()
        val existed = directory.exists()
        try {
            ensureDirectory(transactionDirectory)
            val before = File(transactionDirectory, BEFORE)
            ensureDirectory(before)
            if (existed) copyDirectory(directory, before)
            val staged = File(transactionDirectory, AFTER)
            ensureDirectory(staged)
            after.forEach { (sourceId, data) ->
                writeLocalData(File(staged, sourceId.toString()), data)
            }
            // Until this manifest is published, staging has not changed the live directory.
            writeManifest(id, existed)
        } catch (failure: Throwable) {
            transactionDirectory.deleteRecursively()
            throw failure
        }
        return PendingChange(id)
    }

    /** Resolve an interrupted transaction before any database/source reads. */
    @Synchronized
    fun recover(committedToken: String?) {
        if (!transactionDirectory.exists()) return
        val manifest = File(transactionDirectory, MANIFEST)
        if (!manifest.exists()) {
            deleteDirectory(transactionDirectory)
            return
        }
        val journal = readManifest()
        if (committedToken == journal.id) {
            // apply() must have returned before Room could commit this token.
            check(directory.isDirectory && !File(transactionDirectory, AFTER).exists()) {
                "Committed source snapshots are missing"
            }
            removeJournal()
        } else {
            restoreBefore(journal)
            removeJournal()
        }
    }

    inner class PendingChange internal constructor(val id: String) {
        /** Install the staged directory before committing the database transaction. */
        fun apply() = synchronized(this@SourceSnapshotStore) {
            requireJournal(id)
            val staged = File(transactionDirectory, AFTER)
            if (!staged.exists()) {
                check(directory.isDirectory) { "Applied source snapshots are missing" }
                return@synchronized
            }
            val displaced = File(transactionDirectory, DISPLACED)
            check(!displaced.exists()) { "Interrupted source snapshot apply needs recovery" }
            if (directory.exists()) rename(directory, displaced)
            rename(staged, directory)
        }

        /** Call only after the Room transaction committed its matching marker. */
        fun finish() = synchronized(this@SourceSnapshotStore) {
            requireJournal(id)
            check(!File(transactionDirectory, AFTER).exists() && directory.isDirectory) {
                "Source snapshots have not been applied"
            }
            removeJournal()
        }

        fun rollback() = synchronized(this@SourceSnapshotStore) {
            if (!transactionDirectory.exists()) return@synchronized
            val journal = requireJournal(id)
            restoreBefore(journal)
            removeJournal()
        }
    }

    private data class Journal(val id: String, val directoryExisted: Boolean)

    private fun requireJournal(id: String): Journal = readManifest().also {
        check(it.id == id) { "Source snapshot transaction changed" }
    }

    private fun writeManifest(id: String, directoryExisted: Boolean) {
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("id", id)
            setProperty("directoryExisted", directoryExisted.toString())
        }
        val temporary = File(transactionDirectory, "$MANIFEST.tmp")
        FileOutputStream(temporary).use {
            properties.store(it, null)
            it.fd.sync()
        }
        rename(temporary, File(transactionDirectory, MANIFEST))
    }

    private fun readManifest(): Journal {
        val properties = Properties().apply {
            File(transactionDirectory, MANIFEST).inputStream().use(::load)
        }
        check(properties.getProperty("version") == "1") { "Unsupported source snapshot journal" }
        val id = requireNotNull(properties.getProperty("id")) { "Source snapshot journal has no ID" }
        check(UUID.fromString(id).toString() == id) { "Invalid source snapshot journal ID" }
        val existed = when (properties.getProperty("directoryExisted")) {
            "true" -> true
            "false" -> false
            else -> error("Invalid source snapshot journal state")
        }
        return Journal(id, existed)
    }

    private fun restoreBefore(journal: Journal) {
        val before = File(transactionDirectory, BEFORE)
        check(before.isDirectory) { "Original source snapshots are missing" }
        val restore = File(transactionDirectory, RESTORE)
        if (restore.exists()) deleteDirectory(restore)
        if (journal.directoryExisted) {
            ensureDirectory(restore)
            copyDirectory(before, restore)
        }
        val discarded = File(transactionDirectory, DISCARDED)
        if (discarded.exists()) deleteDirectory(discarded)
        if (directory.exists()) rename(directory, discarded)
        if (journal.directoryExisted) rename(restore, directory)
        // Keep the before copy until all renames succeed, so interrupted rollback can be retried.
    }

    private fun removeJournal() {
        // Mark recovery complete before deleting any backups. If cleanup is interrupted, a missing
        // manifest tells the next launch that the live directory already holds the final state.
        val manifest = File(transactionDirectory, MANIFEST)
        check(!manifest.exists() || manifest.delete()) { "Cannot finish source snapshot journal" }
        deleteDirectory(transactionDirectory)
    }

    companion object {
        const val COMMIT_MARKER_PATH = "internal.local_data_commit"
        private const val MANIFEST = "manifest"
        private const val BEFORE = "before"
        private const val AFTER = "after"
        private const val DISPLACED = "displaced"
        private const val RESTORE = "restore"
        private const val DISCARDED = "discarded"

        fun normalizeSourceId(id: Identifier): Identifier {
            val normalized = requireNotNull(normalizeLocalDataSourceId(id))
            validatePart(normalized.namespace, allowColon = false)
            validatePart(normalized.id, allowColon = true)
            return normalized
        }

        private fun sourceIdFromFilename(name: String): Identifier {
            val parts = name.split(':', limit = 2)
            val id = if (parts.size == 2) Identifier(parts[0], parts[1])
            else Identifier("lightnovelreader", name)
            return normalizeSourceId(id)
        }

        private fun validatePart(value: String, allowColon: Boolean) {
            require(value.isNotBlank() && value != "." && value != ".." &&
                    value.none { it == '/' || it == '\\' || it == '\u0000' || (!allowColon && it == ':') }) {
                "Unsafe source snapshot ID"
            }
        }

        /** Both historical bare CBOR and the ZIP/data format are accepted. */
        fun readBackup(input: InputStream): AppLocalData {
            val data = Cbor.decodeFromByteArray<AppLocalData>(decodeContainer(input.readBytes()))
            require(data.version == 0) { "Unsupported backup version: ${data.version}" }
            return data
        }

        private fun decodeLocalData(input: InputStream): LocalData =
            Cbor.decodeFromByteArray(decodeContainer(input.readBytes()))

        private fun decodeContainer(bytes: ByteArray): ByteArray {
            require(bytes.isNotEmpty()) { "Empty backup" }
            if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return bytes
            validateZipDirectory(bytes)
            return ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val entry = requireNotNull(zip.nextEntry) { "Backup ZIP has no data entry" }
                require(entry.name == "data" && !entry.isDirectory) { "Invalid backup ZIP entry" }
                val data = zip.readBytes()
                zip.closeEntry() // also validates the stored CRC
                require(zip.nextEntry == null && data.isNotEmpty()) { "Invalid backup ZIP contents" }
                data
            }
        }

        // ZipInputStream alone accepts a missing central directory, which would hide truncated files.
        private fun validateZipDirectory(bytes: ByteArray) {
            fun u16(offset: Int): Int = (bytes[offset].toInt() and 255) or
                    ((bytes[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int): Long = (0..3).fold(0L) { value, index ->
                value or ((bytes[offset + index].toLong() and 255) shl (index * 8))
            }
            val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)).firstOrNull {
                bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                        bytes[it + 2] == 0x05.toByte() && bytes[it + 3] == 0x06.toByte() &&
                        it + 22 + u16(it + 20) == bytes.size
            } ?: error("Truncated or invalid backup ZIP")
            require(u16(end + 4) == 0 && u16(end + 6) == 0 &&
                    u16(end + 8) == 1 && u16(end + 10) == 1) { "Invalid backup ZIP directory" }
            val size = u32(end + 12)
            val offset = u32(end + 16)
            require(size >= 46 && offset + size == end.toLong() && offset <= end - 46) {
                "Invalid backup ZIP directory bounds"
            }
            val start = offset.toInt()
            require(bytes[start] == 0x50.toByte() && bytes[start + 1] == 0x4b.toByte() &&
                    bytes[start + 2] == 0x01.toByte() && bytes[start + 3] == 0x02.toByte()) {
                "Invalid backup ZIP directory header"
            }
            val nameLength = u16(start + 28)
            val extraLength = u16(start + 30)
            val commentLength = u16(start + 32)
            require(46L + nameLength + extraLength + commentLength == size &&
                    nameLength == 4 && u16(start + 34) == 0 && u32(start + 42) == 0L) {
                "Invalid backup ZIP directory entry"
            }
            require(bytes.copyOfRange(start + 46, start + 50).contentEquals("data".toByteArray())) {
                "Invalid backup ZIP directory name"
            }
        }

        private fun writeLocalData(file: File, data: LocalData) {
            FileOutputStream(file).use { output ->
                // finish() leaves the underlying stream open long enough to sync the complete ZIP.
                val zip = ZipOutputStream(output)
                try {
                    zip.putNextEntry(ZipEntry("data"))
                    zip.write(Cbor.encodeToByteArray(data))
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    output.fd.sync()
                } finally {
                    zip.close()
                }
            }
        }

        private fun ensureDirectory(file: File) {
            check(file.isDirectory || file.mkdirs()) { "Cannot create directory: $file" }
        }

        private fun copyDirectory(source: File, destination: File) {
            check(source.isDirectory) { "Source snapshot path is not a directory: $source" }
            val files = source.listFiles() ?: error("Cannot list directory: $source")
            files.forEach { file ->
                check(file.canonicalFile.parentFile == source.canonicalFile) {
                    "Source snapshot points outside its directory: $file"
                }
                val target = File(destination, file.name)
                if (file.isDirectory) {
                    ensureDirectory(target)
                    copyDirectory(file, target)
                } else {
                    file.inputStream().use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                }
            }
        }

        private fun rename(source: File, destination: File) {
            check(!destination.exists() && source.renameTo(destination)) {
                "Cannot rename $source to $destination"
            }
        }

        private fun deleteDirectory(file: File) {
            check(file.deleteRecursively()) { "Cannot remove source snapshot journal: $file" }
        }
    }
}
