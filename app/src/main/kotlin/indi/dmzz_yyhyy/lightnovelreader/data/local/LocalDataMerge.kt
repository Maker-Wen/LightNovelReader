package indi.dmzz_yyhyy.lightnovelreader.data.local

import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookRecordEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.FormattingRuleEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.UserReadingDataEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.VolumeEntity
import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.time.LocalDateTime

/** The legacy built-in Wenku8 ID can also occur in string-valued API 4 backups. */
fun normalizeLocalDataSourceId(id: Identifier?): Identifier? =
    if (id == Identifier("lightnovelreader", "-791439186")) {
        Identifier("lightnovelreader", "Wenku8")
    } else {
        id
    }

/**
 * Merges two snapshots of the same source without adding counters from overlapping backups.
 * The existing snapshot keeps shelf order, settings and chapter bodies; incoming-only rows survive.
 * Cached directories also incorporate missing chapters and reconnect their interior navigation.
 * These rules are specific to backup restoration, rather than live reading updates.
 */
fun mergeLocalData(existing: LocalData, incoming: LocalData): LocalData {
    val sourceId = normalizeLocalDataSourceId(existing.webBookDataSourceId)
    require(sourceId == normalizeLocalDataSourceId(incoming.webBookDataSourceId)) {
        "Cannot merge snapshots from different data sources"
    }
    val existingUpdates = existing.bookInformationEntities.groupBy { it.id }
        .mapValues { (_, rows) -> rows.maxOf { it.lastUpdated } }
    val incomingUpdates = incoming.bookInformationEntities.groupBy { it.id }
        .mapValues { (_, rows) -> rows.maxOf { it.lastUpdated } }
    // Book metadata is only a preference for directory order, not a timestamp for chapter bodies.
    // Missing/unknown/equal dates cannot establish that a backup's directory is newer.
    val newerIncomingBooks = incomingUpdates.filter { (id, updated) ->
        val previous = existingUpdates[id]
        previous != null && previous != LocalDateTime.MIN && updated != LocalDateTime.MIN &&
            updated.isAfter(previous)
    }.keys
    val volumes = mergeRows(
        existing.volumeEntities.map { it.copy(chapterIds = it.chapterIds.distinct()) },
        incoming.volumeEntities.map { it.copy(chapterIds = it.chapterIds.distinct()) },
        key = { it.volumeId },
        merge = { old, new ->
            // A volume ID conflict must not attach another book's cached chapters to this book.
            if (old.bookId != new.bookId) old else {
                val preferIncoming = new.bookId in newerIncomingBooks
                val preferred = if (preferIncoming) new else old
                val other = if (preferIncoming) old else new
                preferred.copy(chapterIds = mergeChapterOrder(preferred.chapterIds, other.chapterIds))
            }
        }
    )
    val chapterContents = mergeRows(
        existing.chapterContentEntities,
        incoming.chapterContentEntities,
        key = { it.id }
    )
    val addedCachedChapterIds = incoming.chapterContentEntities.map { it.id }.toSet() -
        existing.chapterContentEntities.map { it.id }.toSet()
    return existing.copy(
        webBookDataSourceId = sourceId,
        bookInformationEntities = mergeRows(
            existing.bookInformationEntities,
            incoming.bookInformationEntities,
            key = { it.id },
            merge = { old, new -> if (new.lastUpdated.isAfter(old.lastUpdated)) new else old }
        ),
        bookRecordEntities = mergeRows(
            existing.bookRecordEntities,
            incoming.bookRecordEntities,
            key = { it.bookId to it.date },
            merge = ::mergeBookRecord
        ),
        dailyCountEntities = mergeRows(
            existing.dailyCountEntities,
            incoming.dailyCountEntities,
            key = { it.date },
            merge = { old, new -> old.merge(new) }
        ),
        bookshelfEntities = mergeRows(
            existing.bookshelfEntities.map { shelf ->
                shelf.copy(
                    allBookIds = shelf.allBookIds.distinct(),
                    pinnedBookIds = shelf.pinnedBookIds.distinct(),
                    updatedBookIds = shelf.updatedBookIds.distinct()
                )
            },
            incoming.bookshelfEntities.map { shelf ->
                shelf.copy(
                    allBookIds = shelf.allBookIds.distinct(),
                    pinnedBookIds = shelf.pinnedBookIds.distinct(),
                    updatedBookIds = shelf.updatedBookIds.distinct()
                )
            },
            key = { it.id },
            merge = { old, new ->
                old.copy(
                    allBookIds = (old.allBookIds + new.allBookIds).distinct(),
                    pinnedBookIds = (old.pinnedBookIds + new.pinnedBookIds).distinct(),
                    updatedBookIds = (old.updatedBookIds + new.updatedBookIds).distinct()
                )
            }
        ),
        bookshelfBookMetadataEntities = mergeRows(
            existing.bookshelfBookMetadataEntities.map {
                it.copy(bookShelfIds = it.bookShelfIds.distinct())
            },
            incoming.bookshelfBookMetadataEntities.map {
                it.copy(bookShelfIds = it.bookShelfIds.distinct())
            },
            key = { it.id },
            merge = { old, new ->
                old.copy(
                    lastUpdate = maxOf(old.lastUpdate, new.lastUpdate),
                    bookShelfIds = (old.bookShelfIds + new.bookShelfIds).distinct()
                )
            }
        ),
        chapterContentEntities = repairMergedChapterLinks(
            chapterContents, existing.volumeEntities, volumes, addedCachedChapterIds
        ),
        chapterInformationEntities = mergeRows(
            existing.chapterInformationEntities,
            incoming.chapterInformationEntities,
            key = { it.id }
        ),
        formattingRuleEntities = mergeFormattingRules(
            existing.formattingRuleEntities,
            incoming.formattingRuleEntities
        ),
        userDataEntities = mergeRows(
            existing.userDataEntities.map(::normalizeListSetting),
            incoming.userDataEntities.map(::normalizeListSetting),
            key = { it.path },
            merge = { old, new ->
                if (old.type == "StringList" && new.type == "StringList") {
                    old.copy(value = (old.value.split(',') + new.value.split(','))
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString(","))
                } else {
                    old
                }
            }
        ),
        userReadingDataEntities = mergeRows(
            existing.userReadingDataEntities,
            incoming.userReadingDataEntities,
            key = { it.id },
            merge = ::mergeUserReadingData
        ),
        volumeEntities = volumes
    )
}

/** Keep the preferred relative order, inserting additions before their next shared chapter. */
private fun mergeChapterOrder(preferred: List<String>, other: List<String>): List<String> {
    val preferredIds = preferred.toSet()
    val beforeChapter = mutableMapOf<String, MutableList<String>>()
    val pending = mutableListOf<String>()
    for (id in other.distinct()) {
        if (id in preferredIds) {
            if (pending.isNotEmpty()) {
                beforeChapter.getOrPut(id) { mutableListOf() }.addAll(pending)
                pending.clear()
            }
        } else pending.add(id)
    }
    return buildList {
        for (id in preferred) {
            beforeChapter[id]?.let(::addAll)
            add(id)
        }
        addAll(pending)
    }
}

private fun repairMergedChapterLinks(
    contents: List<ChapterContentEntity>,
    previousVolumes: List<VolumeEntity>,
    volumes: List<VolumeEntity>,
    addedCachedChapterIds: Set<String>
): List<ChapterContentEntity> {
    data class Neighbours(val previous: String?, val next: String?, val volumeChapterIds: Set<String>)
    val previousById = previousVolumes.associateBy { it.volumeId }
    val chapterOwners = volumes.flatMap { volume -> volume.chapterIds.map { it to volume.volumeId } }
        .groupBy({ it.first }, { it.second })
    val neighbours = mutableMapOf<String, Neighbours>()
    for (volume in volumes) {
        val previous = previousById[volume.volumeId] ?: continue
        if (previous.bookId != volume.bookId) continue
        if (previous.chapterIds.distinct() == volume.chapterIds &&
            volume.chapterIds.none { it in addedCachedChapterIds }) continue
        // A chapter listed by several volumes does not establish an unambiguous adjacency.
        if (volume.chapterIds.any { chapterOwners.getValue(it).distinct().size != 1 }) continue
        val chapterIds = volume.chapterIds.toSet()
        volume.chapterIds.forEachIndexed { index, id ->
            neighbours[id] = Neighbours(
                volume.chapterIds.getOrNull(index - 1), volume.chapterIds.getOrNull(index + 1), chapterIds
            )
        }
    }
    return contents.map { chapter ->
        val links = neighbours[chapter.id] ?: return@map chapter
        // Repair interior links on preserved bodies. Boundary and outside-directory links may
        // lead to uncached chapters or another volume, so retain the final boundary edges.
        chapter.copy(
            prevChapter = links.previous ?: chapter.prevChapter.takeUnless { it in links.volumeChapterIds }.orEmpty(),
            nextChapter = links.next ?: chapter.nextChapter.takeUnless { it in links.volumeChapterIds }.orEmpty()
        )
    }
}

/** Applies the same export choices to active database snapshots and saved source snapshots. */
fun filterLocalData(
    data: LocalData,
    localBookCache: Boolean,
    bookshelf: Boolean,
    readingRecord: Boolean,
    settings: Boolean
): LocalData = data.copy(
    webBookDataSourceId = normalizeLocalDataSourceId(data.webBookDataSourceId),
    bookInformationEntities = data.bookInformationEntities.takeIf { localBookCache }.orEmpty(),
    chapterContentEntities = data.chapterContentEntities.takeIf { localBookCache }.orEmpty(),
    chapterInformationEntities = data.chapterInformationEntities.takeIf { localBookCache }.orEmpty(),
    volumeEntities = data.volumeEntities.takeIf { localBookCache }.orEmpty(),
    bookshelfEntities = data.bookshelfEntities.takeIf { bookshelf }.orEmpty(),
    bookshelfBookMetadataEntities = data.bookshelfBookMetadataEntities.takeIf { bookshelf }.orEmpty(),
    bookRecordEntities = data.bookRecordEntities.takeIf { readingRecord }.orEmpty(),
    dailyCountEntities = data.dailyCountEntities.takeIf { readingRecord }.orEmpty(),
    userReadingDataEntities = data.userReadingDataEntities.takeIf { readingRecord }.orEmpty(),
    userDataEntities = data.userDataEntities.takeIf { readingRecord }.orEmpty(),
    formattingRuleEntities = data.formattingRuleEntities.takeIf { settings }.orEmpty()
)

private fun mergeBookRecord(old: BookRecordEntity, new: BookRecordEntity): BookRecordEntity =
    old.copy(
        reads = maxOf(old.reads, new.reads),
        seconds = maxOf(old.seconds, new.seconds),
        isFinished = old.isFinished || new.isFinished,
        isFavorited = old.isFavorited || new.isFavorited,
        firstSeen = minOf(old.firstSeen, new.firstSeen),
        lastSeen = maxOf(old.lastSeen, new.lastSeen)
    )

private fun mergeUserReadingData(
    old: UserReadingDataEntity,
    new: UserReadingDataEntity
): UserReadingDataEntity {
    val incomingIsNewer = new.lastReadTime.isAfter(old.lastReadTime)
    val latest = if (incomingIsNewer) new else old
    return latest.copy(
        totalReadTime = maxOf(old.totalReadTime, new.totalReadTime),
        currentChapterReadingProgressMap = mergeMapValues(
            old.currentChapterReadingProgressMap,
            new.currentChapterReadingProgressMap
        ) { previous, incoming -> if (incomingIsNewer) incoming else previous },
        maxChapterReadingProgressMap = mergeMapValues(
            old.maxChapterReadingProgressMap,
            new.maxChapterReadingProgressMap,
            ::maxOf
        )
    )
}

private fun normalizeListSetting(data: UserDataEntity): UserDataEntity =
    if (data.type == "StringList") {
        data.copy(value = data.value.split(',')
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(","))
    } else {
        data
    }

private fun mergeFormattingRules(
    existing: List<FormattingRuleEntity>,
    incoming: List<FormattingRuleEntity>
): List<FormattingRuleEntity> {
    // Reserve all explicit IDs before allocating IDs for legacy unsaved rules. Persisting these
    // IDs keeps a subsequent import of the same null-ID backup from duplicating its rules.
    var lastAllocatedId = sequenceOf(existing, incoming)
        .flatMap { it.asSequence() }
        .mapNotNull { it.id }
        .maxOrNull()
        ?.coerceAtLeast(0)
        ?: 0
    val rules = linkedMapOf<Int, FormattingRuleEntity>()
    val ruleContents = mutableMapOf<FormattingRuleEntity, Int>()
    for (snapshotRules in listOf(existing, incoming)) {
        for (rule in snapshotRules) {
            val contents = rule.copy(id = null)
            val id = rule.id ?: ruleContents[contents] ?: run {
                try {
                    Math.addExact(lastAllocatedId, 1)
                } catch (error: ArithmeticException) {
                    throw IllegalArgumentException("No formatting rule IDs available", error)
                }.also { lastAllocatedId = it }
            }
            val retainedRule = rules.getOrPut(id) { rule.copy(id = id) }
            ruleContents.putIfAbsent(retainedRule.copy(id = null), id)
        }
    }
    return rules.values.toList()
}

private fun <T, K> mergeRows(
    existing: List<T>,
    incoming: List<T>,
    key: (T) -> K,
    merge: (T, T) -> T = { old, _ -> old }
): List<T> {
    val rows = linkedMapOf<K, T>()
    for (snapshotRows in listOf(existing, incoming)) {
        for (row in snapshotRows) {
            val rowKey = key(row)
            rows[rowKey] = rows[rowKey]?.let { merge(it, row) } ?: row
        }
    }
    return rows.values.toList()
}

private fun <K, V> mergeMapValues(
    existing: Map<K, V>,
    incoming: Map<K, V>,
    merge: (V, V) -> V
): Map<K, V> = existing.toMutableMap().apply {
    for ((key, value) in incoming) {
        this[key] = if (containsKey(key)) merge(getValue(key), value) else value
    }
}
