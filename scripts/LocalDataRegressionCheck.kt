@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

import android.net.Uri
import indi.dmzz_yyhyy.lightnovelreader.data.local.SourceSnapshotStore
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.filterLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.mergeLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.*
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.Count
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val source = Identifier("test", "source")
private val otherSource = Identifier("test", "other")
private val wenku = Identifier("lightnovelreader", "Wenku8")
private val legacyWenku = Identifier("lightnovelreader", "-791439186")
private val timestamp = LocalDateTime.of(2026, 9, 30, 12, 0)
private var passed = 0

// Version-0 backups used an integer built-in source ID before API 4 identifiers.
@Serializable
private data class NumericLegacyLocalData(
    val webBookDataSourceId: Int,
    val bookInformationEntities: List<BookInformationEntity> = emptyList(),
    val bookRecordEntities: List<BookRecordEntity> = emptyList(),
    val dailyCountEntities: List<DailyCountEntity> = emptyList(),
    val bookshelfEntities: List<BookshelfEntity> = emptyList(),
    val bookshelfBookMetadataEntities: List<BookshelfBookMetadataEntity> = emptyList(),
    val chapterContentEntities: List<ChapterContentEntity> = emptyList(),
    val chapterInformationEntities: List<ChapterInformationEntity> = emptyList(),
    val formattingRuleEntities: List<FormattingRuleEntity> = emptyList(),
    val userDataEntities: List<UserDataEntity> = emptyList(),
    val userReadingDataEntities: List<UserReadingDataEntity> = emptyList(),
    val volumeEntities: List<VolumeEntity> = emptyList()
)

private fun scenario(name: String, block: () -> Unit) {
    try {
        block()
    } catch (failure: Throwable) {
        throw AssertionError("Failed .lnr scenario: $name", failure)
    }
    passed++
    println("PASS $name")
}

private fun count(vararg values: Pair<Int, Int>) = Count().apply {
    for ((hour, minutes) in values) setMinute(hour, minutes)
}

private fun fixture(id: String, sourceId: Identifier = source) = LocalData.empty().copy(
    webBookDataSourceId = sourceId,
    bookInformationEntities = listOf(BookInformationEntity(
        id, "Title $id", "", Uri.parse("https://example.invalid/$id.jpg"), "Author", "Description",
        listOf("Tag"), "Publisher", WordCount(123), timestamp, false
    )),
    bookRecordEntities = listOf(BookRecordEntity(
        id, timestamp.toLocalDate(), 2, 120, firstSeen = LocalTime.of(8, 0), lastSeen = LocalTime.of(9, 0)
    )),
    dailyCountEntities = listOf(DailyCountEntity(timestamp.toLocalDate(), count(8 to 2))),
    bookshelfEntities = listOf(BookshelfEntity(
        1, "Shelf $id", "default", autoCache = false, systemUpdateReminder = false,
        allBookIds = listOf(id), pinnedBookIds = listOf(id), updatedBookIds = listOf(id)
    )),
    bookshelfBookMetadataEntities = listOf(BookshelfBookMetadataEntity(id, timestamp, listOf(1))),
    chapterContentEntities = listOf(ChapterContentEntity(
        "$id-chapter", "Chapter $id", Json.parseToJsonElement("{\"text\":\"$id\"}").jsonObject, "", ""
    )),
    chapterInformationEntities = listOf(ChapterInformationEntity("$id-chapter", "Chapter $id")),
    formattingRuleEntities = listOf(FormattingRuleEntity(
        id = id.hashCode(), bookId = id, name = "Rule $id", isRegex = false,
        match = id, replacement = "", isEnabled = true
    )),
    userDataEntities = listOf(UserDataEntity("source.$id", "source", "String", id)),
    userReadingDataEntities = listOf(UserReadingDataEntity(
        id, timestamp, 120, .2f, "$id-chapter", "Chapter $id",
        mapOf("$id-chapter" to .2f), mapOf("$id-chapter" to .2f)
    )),
    volumeEntities = listOf(VolumeEntity(id, "$id-volume", "Volume $id", listOf("$id-chapter"), 0))
)

private fun equalSnapshot(expected: LocalData, actual: LocalData) {
    check(Cbor.encodeToByteArray(expected).contentEquals(Cbor.encodeToByteArray(actual))) {
        "Snapshots differ after serialization"
    }
}

private fun withStore(block: (File, SourceSnapshotStore) -> Unit) {
    val parent = Files.createTempDirectory("lnr-snapshot-check-").toFile()
    try {
        val directory = File(parent, "local_data")
        block(directory, SourceSnapshotStore(directory))
    } finally {
        parent.deleteRecursively()
    }
}

private fun save(store: SourceSnapshotStore, data: LocalData, replaceAll: Boolean = false) {
    val change = store.prepare(mapOf(checkNotNull(data.webBookDataSourceId) to data), replaceAll)
    change.apply()
    change.finish()
}

private fun writeZip(file: File, entryName: String, bytes: ByteArray) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(bytes)
        zip.closeEntry()
    }
}

private fun checkMerges() {
    scenario("incoming-only rows survive every table and repeated import is idempotent") {
        val local = fixture("local")
        val incoming = fixture("incoming").copy(
            dailyCountEntities = listOf(DailyCountEntity(timestamp.toLocalDate().plusDays(1), count(9 to 3))),
            bookshelfEntities = fixture("incoming").bookshelfEntities.map { it.copy(id = 2) }
        )
        val merged = mergeLocalData(local, incoming)
        val sizes = listOf(
            merged.bookInformationEntities.size, merged.bookRecordEntities.size, merged.dailyCountEntities.size,
            merged.bookshelfEntities.size, merged.bookshelfBookMetadataEntities.size, merged.chapterContentEntities.size,
            merged.chapterInformationEntities.size, merged.formattingRuleEntities.size, merged.userDataEntities.size,
            merged.userReadingDataEntities.size, merged.volumeEntities.size
        )
        check(sizes.all { it == 2 }) { "Incoming rows were dropped: $sizes" }
        check(merged.bookInformationEntities.map { it.id } == listOf("local", "incoming"))
        equalSnapshot(merged, mergeLocalData(merged, incoming))
        equalSnapshot(merged, mergeLocalData(merged, merged))
    }
    scenario("reading counts restore maxima while preserving flags and time bounds") {
        val old = fixture("shared")
        val incoming = old.copy(bookRecordEntities = old.bookRecordEntities.map {
            it.copy(reads = 5, seconds = 60, isFinished = true, isFavorited = true,
                firstSeen = LocalTime.of(7, 0), lastSeen = LocalTime.of(10, 0))
        }, dailyCountEntities = listOf(DailyCountEntity(timestamp.toLocalDate(), count(8 to 1, 9 to 3))))
        val merged = mergeLocalData(old, incoming)
        check(merged.bookRecordEntities.single().let {
            it.reads == 5 && it.seconds == 120 && it.isFinished && it.isFavorited &&
                it.firstSeen == LocalTime.of(7, 0) && it.lastSeen == LocalTime.of(10, 0)
        })
        check(merged.dailyCountEntities.single().timeCount.let { it.getMinute(8) == 2 && it.getMinute(9) == 3 })
        equalSnapshot(merged, mergeLocalData(merged, incoming))
    }
    scenario("older reading backup cannot mix newer time with older chapter or overall progress") {
        val old = fixture("shared")
        val localReading = old.userReadingDataEntities.single().copy(
            lastReadTime = timestamp.plusDays(1), readingProgress = .1f,
            lastReadChapterId = "new-chapter", lastReadChapterTitle = "New chapter",
            currentChapterReadingProgressMap = mapOf("shared" to .1f, "local-only" to .4f),
            maxChapterReadingProgressMap = mapOf("shared" to .4f, "local-only" to .8f)
        )
        val backupReading = localReading.copy(
            lastReadTime = timestamp, totalReadTime = 150, readingProgress = .9f,
            lastReadChapterId = "old-chapter", lastReadChapterTitle = "Old chapter",
            currentChapterReadingProgressMap = mapOf("shared" to .9f, "backup-only" to .3f),
            maxChapterReadingProgressMap = mapOf("shared" to .9f, "backup-only" to .7f)
        )
        val local = old.copy(userReadingDataEntities = listOf(localReading))
        val backup = old.copy(userReadingDataEntities = listOf(backupReading))
        val reading = mergeLocalData(local, backup).userReadingDataEntities.single()
        check(reading.lastReadTime == localReading.lastReadTime && reading.readingProgress == .1f)
        check(reading.lastReadChapterId == "new-chapter" && reading.lastReadChapterTitle == "New chapter")
        check(reading.totalReadTime == 150)
        check(reading.currentChapterReadingProgressMap == mapOf("shared" to .1f, "local-only" to .4f, "backup-only" to .3f))
        check(reading.maxChapterReadingProgressMap == mapOf("shared" to .9f, "local-only" to .8f, "backup-only" to .7f))
        val reversed = mergeLocalData(backup, local).userReadingDataEntities.single()
        check(reversed.lastReadChapterId == reading.lastReadChapterId && reversed.readingProgress == reading.readingProgress)
        check(reversed.currentChapterReadingProgressMap == reading.currentChapterReadingProgressMap)
        check(reversed.maxChapterReadingProgressMap == reading.maxChapterReadingProgressMap)
    }
    scenario("bookshelf order and local settings survive union; unsaved distinct rules survive") {
        val old = fixture("shared")
        val oldShelf = old.bookshelfEntities.single().copy(allBookIds = listOf("b", "a"), pinnedBookIds = listOf("b"))
        val newShelf = oldShelf.copy(name = "Imported name", allBookIds = listOf("a", "c", "b"), pinnedBookIds = listOf("c"))
        val rule = old.formattingRuleEntities.single().copy(id = null)
        val local = old.copy(bookshelfEntities = listOf(oldShelf), formattingRuleEntities = listOf(rule))
        val incoming = old.copy(bookshelfEntities = listOf(newShelf), formattingRuleEntities = listOf(rule, rule.copy(match = "different")))
        val merged = mergeLocalData(local, incoming)
        check(merged.bookshelfEntities.single().let { it.name == oldShelf.name && it.allBookIds == listOf("b", "a", "c") && it.pinnedBookIds == listOf("b", "c") })
        check(merged.formattingRuleEntities.size == 2)
        equalSnapshot(merged, mergeLocalData(merged, incoming))
    }
    scenario("legacy formatting rules remain idempotent after generated IDs are stored in Room") {
        val original = fixture("shared")
        val assigned = original.formattingRuleEntities.single().copy(id = 40)
        val legacy = assigned.copy(id = null)
        val local = original.copy(formattingRuleEntities = listOf(assigned))
        val incoming = original.copy(formattingRuleEntities = listOf(legacy, legacy.copy(name = "New rule", match = "new")))
        val merged = mergeLocalData(local, incoming)
        check(merged.formattingRuleEntities.map { it.id } == listOf(40, 41))
        equalSnapshot(merged, mergeLocalData(merged, incoming))
        check(runCatching {
            mergeLocalData(local.copy(formattingRuleEntities = listOf(assigned.copy(id = Int.MAX_VALUE))), incoming)
        }.isFailure)
    }
    scenario("StringList setting union preserves local order and repeated imports do not duplicate values") {
        val original = fixture("shared")
        val local = original.copy(userDataEntities = listOf(
            UserDataEntity("favorites", "source", "StringList", "b,a,b"),
            UserDataEntity("setting", "source", "String", "local")
        ))
        val incoming = original.copy(userDataEntities = listOf(
            UserDataEntity("favorites", "source", "StringList", "a,c,c"),
            UserDataEntity("setting", "source", "String", "backup"),
            UserDataEntity("new-list", "source", "StringList", "x,x,y")
        ))
        val merged = mergeLocalData(local, incoming)
        check(merged.userDataEntities.associate { it.path to it.value } == mapOf(
            "favorites" to "b,a,c", "setting" to "local", "new-list" to "x,y"
        ))
        equalSnapshot(merged, mergeLocalData(merged, incoming))
    }
    scenario("inactive sources obey all export category choices") {
        val data = fixture("shared")
        equalSnapshot(LocalData.empty().copy(webBookDataSourceId = source), filterLocalData(data, false, false, false, false))
        val cache = filterLocalData(data, true, false, false, false)
        check(cache.bookInformationEntities.isNotEmpty() && cache.chapterContentEntities.isNotEmpty() &&
            cache.chapterInformationEntities.isNotEmpty() && cache.volumeEntities.isNotEmpty())
        check(cache.bookshelfEntities.isEmpty() && cache.bookRecordEntities.isEmpty() && cache.userDataEntities.isEmpty() && cache.formattingRuleEntities.isEmpty())
        val shelves = filterLocalData(data, false, true, false, false)
        check(shelves.bookshelfEntities.isNotEmpty() && shelves.bookshelfBookMetadataEntities.isNotEmpty() && shelves.bookInformationEntities.isEmpty())
        val reading = filterLocalData(data, false, false, true, false)
        check(reading.bookRecordEntities.isNotEmpty() && reading.dailyCountEntities.isNotEmpty() &&
            reading.userReadingDataEntities.isNotEmpty() && reading.userDataEntities.isNotEmpty() && reading.volumeEntities.isEmpty())
        val settings = filterLocalData(data, false, false, false, true)
        check(settings.formattingRuleEntities.isNotEmpty() && settings.userDataEntities.isEmpty())
    }
    scenario("merge enforces source isolation and accepts canonical Wenku alias") {
        check(runCatching { mergeLocalData(fixture("a"), fixture("b", otherSource)) }.isFailure)
        check(runCatching { mergeLocalData(fixture("a"), LocalData.empty()) }.isFailure)
        val merged = mergeLocalData(fixture("a", wenku), fixture("b", legacyWenku))
        check(merged.webBookDataSourceId == wenku && merged.bookInformationEntities.size == 2)
    }
}

private fun checkSnapshots() {
    scenario("new snapshots write ZIP and restore real serialized content") {
        withStore { directory, store ->
            val data = fixture("snapshot")
            save(store, data)
            equalSnapshot(data, checkNotNull(SourceSnapshotStore(directory).read(source)))
            check(directory.listFiles()!!.filter(File::isFile).all { it.readBytes().take(2) == listOf(0x50.toByte(), 0x4b.toByte()) })
        }
    }
    scenario("legacy raw snapshots and Wenku aliases merge without dropping unique books") {
        withStore { directory, store ->
            save(store, fixture("canonical", wenku))
            File(directory, "-791439186").writeBytes(Cbor.encodeToByteArray(fixture("legacy", legacyWenku)))
            val rows = store.readAll()
            check(rows.keys == setOf(wenku))
            check(rows.getValue(wenku).bookInformationEntities.map { it.id }.toSet() == setOf("legacy", "canonical"))
            check(checkNotNull(store.read(legacyWenku)).webBookDataSourceId == wenku)
        }
    }
    scenario("integer legacy source IDs remain readable before API 4 migration") {
        withStore { directory, store ->
            directory.mkdirs()
            val legacy = NumericLegacyLocalData(-791439186, fixture("legacy", wenku).bookInformationEntities)
            File(directory, "-791439186").writeBytes(Cbor { encodeDefaults = true }.encodeToByteArray(legacy))
            val data = checkNotNull(store.read(wenku))
            check(data.webBookDataSourceId == wenku && data.bookInformationEntities.single().id == "legacy")
        }
    }
    scenario("prepared but unapplied journal recovers the original snapshot") {
        withStore { directory, store ->
            val before = fixture("before")
            save(store, before)
            store.prepare(mapOf(source to fixture("after")))
            SourceSnapshotStore(directory).recover(null)
            equalSnapshot(before, checkNotNull(store.read(source)))
        }
    }
    scenario("interrupted apply without database commit rolls back all replaced and removed sources") {
        withStore { directory, store ->
            val before = fixture("before")
            val retained = fixture("retained", otherSource)
            save(store, before)
            save(store, retained)
            val change = store.prepare(mapOf(source to fixture("after")), replaceAll = true)
            change.apply()
            check(store.read(otherSource) == null)
            SourceSnapshotStore(directory).recover(null)
            equalSnapshot(before, checkNotNull(store.read(source)))
            equalSnapshot(retained, checkNotNull(store.read(otherSource)))
        }
    }
    scenario("interrupted committed apply finalizes the new snapshot and deletions") {
        withStore { directory, store ->
            save(store, fixture("before"))
            save(store, fixture("removed", otherSource))
            val after = fixture("after")
            val change = store.prepare(mapOf(source to after), replaceAll = true)
            change.apply()
            SourceSnapshotStore(directory).recover(change.id)
            equalSnapshot(after, checkNotNull(store.read(source)))
            check(store.read(otherSource) == null)
        }
    }
    scenario("interrupted directory displacement restores original snapshots") {
        withStore { directory, store ->
            val before = fixture("before")
            save(store, before)
            store.prepare(mapOf(source to fixture("after")))
            check(directory.renameTo(File(store.transactionDirectory, "displaced")))
            check(!directory.exists())
            SourceSnapshotStore(directory).recover(null)
            equalSnapshot(before, checkNotNull(store.read(source)))
        }
    }
    scenario("explicit rollback restores prior snapshots and removes introduced sources") {
        withStore { _, store ->
            val before = fixture("before")
            save(store, before)
            val change = store.prepare(mapOf(source to fixture("after"), otherSource to fixture("introduced", otherSource)))
            change.apply()
            change.rollback()
            equalSnapshot(before, checkNotNull(store.read(source)))
            check(store.read(otherSource) == null)
        }
    }
    scenario("malformed inactive snapshot is reported instead of omitted from backup") {
        withStore { directory, store ->
            directory.mkdirs()
            File(directory, "broken").writeText("broken backup")
            check(runCatching { store.readAll() }.isFailure)
        }
    }
    scenario("replacement payload mismatch is rejected without changing original data") {
        withStore { _, store ->
            val before = fixture("before")
            save(store, before)
            check(runCatching { store.prepare(mapOf(source to fixture("bad", otherSource))) }.isFailure)
            equalSnapshot(before, checkNotNull(store.read(source)))
            check(!store.transactionDirectory.exists())
        }
    }
    scenario("authoritative active snapshot excludes corrupt aliases and repairs them without losing inactive sources") {
        withStore { directory, store ->
            val inactive = fixture("inactive", otherSource)
            save(store, inactive)
            File(directory, "-791439186").writeText("corrupt active legacy snapshot")
            check(runCatching { store.readAll() }.isFailure)
            val remaining = store.readAll(setOf(wenku))
            check(remaining.keys == setOf(otherSource))
            equalSnapshot(inactive, checkNotNull(store.read(otherSource)))
            val replacement = fixture("active", wenku)
            save(store, replacement)
            equalSnapshot(replacement, checkNotNull(store.read(wenku)))
            equalSnapshot(inactive, checkNotNull(store.read(otherSource)))
            check(!File(directory, "-791439186").exists())
        }
    }
    scenario("failed authoritative replacement restores exact corrupt file for subsequent recovery") {
        withStore { directory, store ->
            directory.mkdirs()
            val bytes = "original corrupt current snapshot".toByteArray()
            val original = File(directory, source.toString())
            original.writeBytes(bytes)
            val change = store.prepare(mapOf(source to fixture("repaired")))
            change.apply()
            checkNotNull(store.read(source))
            change.rollback()
            check(original.readBytes().contentEquals(bytes))
            check(runCatching { store.read(source) }.isFailure)
        }
    }
    scenario("excluding active snapshot does not silently ignore a corrupt inactive source") {
        withStore { directory, store ->
            directory.mkdirs()
            File(directory, source.toString()).writeText("corrupt current snapshot")
            File(directory, otherSource.toString()).writeText("corrupt inactive snapshot")
            check(runCatching { store.readAll(setOf(source)) }.isFailure)
            check(runCatching { store.prepare(mapOf(source to fixture("active"))) }.isFailure)
            check(!store.transactionDirectory.exists())
        }
    }
    scenario("resolved journal cleanup can resume after original copies have already been deleted") {
        withStore { directory, store ->
            save(store, fixture("before"))
            val after = fixture("after")
            val change = store.prepare(mapOf(source to after))
            change.apply()
            // finish/recover first removes the manifest to mark resolution, then its old copies.
            check(File(store.transactionDirectory, "manifest").delete())
            check(File(store.transactionDirectory, "before").deleteRecursively())
            SourceSnapshotStore(directory).recover(null)
            equalSnapshot(after, checkNotNull(store.read(source)))
            check(!store.transactionDirectory.exists())
        }
    }
}

private fun checkBackups() {
    val backup = AppLocalData(localDataList = listOf(fixture("source")), globalLocalData = LocalData.empty())
    val raw = Cbor.encodeToByteArray(backup)
    scenario("exported ZIP and historical bare CBOR decode through one production reader") {
        val decodedRaw = SourceSnapshotStore.readBackup(raw.inputStream())
        equalSnapshot(backup.localDataList.single(), decodedRaw.localDataList.single())
        withStore { directory, _ ->
            val zip = File(directory.parentFile, "backup.lnr")
            writeZip(zip, "data", raw)
            val decodedZip = zip.inputStream().use(SourceSnapshotStore::readBackup)
            equalSnapshot(decodedRaw.globalLocalData, decodedZip.globalLocalData)
            equalSnapshot(decodedRaw.localDataList.single(), decodedZip.localDataList.single())
        }
    }
    scenario("unsupported backup version and corrupt CBOR fail before mutation") {
        val unsupported = Cbor.encodeToByteArray(backup.copy(version = 1))
        check(runCatching { SourceSnapshotStore.readBackup(unsupported.inputStream()) }.isFailure)
        check(runCatching { SourceSnapshotStore.readBackup(byteArrayOf().inputStream()) }.isFailure)
        check(runCatching { SourceSnapshotStore.readBackup("invalid".byteInputStream()) }.isFailure)
    }
    scenario("truncated ZIP and wrong or multiple entries are rejected") {
        withStore { directory, _ ->
            val zip = File(directory.parentFile, "backup.lnr")
            writeZip(zip, "data", raw)
            val bytes = zip.readBytes()
            check(runCatching { SourceSnapshotStore.readBackup(bytes.copyOf(bytes.size - 10).inputStream()) }.isFailure)
            writeZip(zip, "other", raw)
            check(runCatching { zip.inputStream().use(SourceSnapshotStore::readBackup) }.isFailure)
            ZipOutputStream(zip.outputStream()).use { output ->
                for (name in listOf("data", "extra")) {
                    output.putNextEntry(ZipEntry(name))
                    output.write(raw)
                    output.closeEntry()
                }
            }
            check(runCatching { zip.inputStream().use(SourceSnapshotStore::readBackup) }.isFailure)
        }
    }
}

fun main() {
    checkMerges()
    checkSnapshots()
    checkBackups()
    println("$passed production .lnr merge, compatibility and recovery scenarios passed")
}
