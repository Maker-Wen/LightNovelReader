#!/usr/bin/env python3
"""Run .lnr merge, compatibility and crash-recovery checks on production code.

Uses the project Kotlin compiler and serialization plugin already in the Gradle
cache. Android/Room annotation stubs are platform boundaries; the backup models,
serializers, entity merges and snapshot journal are compiled from app sources.
This checks file recovery on the JVM, not Android Room transaction integration.
"""
from kotlin_check import root, run_check, versions


stubs = {
    "Uri": """package android.net
data class Uri(private val text: String) {
 override fun toString() = text
 companion object { val EMPTY = Uri(""); fun parse(text: String) = Uri(text) }
}
""",
    "UriExt": """package androidx.core.net
fun String.toUri() = android.net.Uri.parse(this)
""",
    "Room": """package androidx.room
import kotlin.reflect.KClass
annotation class Entity(val tableName: String = "", val primaryKeys: Array<String> = [], val indices: Array<Index> = [])
annotation class Index(val value: Array<String>)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo(val name: String)
annotation class TypeConverters(vararg val value: KClass<*>)
""",
    "Converters": """package indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter
class LocalDateTimeConverter
class ListConverter
class WorldCountConverter
class UriConverter
class JsonObjectConverter
class ChapterReadingProgressMapConverter
class CountConverter
""",
    "Annotation": """package androidx.annotation
annotation class StringRes
""",
    "Compose": """package androidx.compose.runtime
annotation class Immutable
""",
    "Parcelable": """package android.os
interface Parcelable
""",
    "Parcelize": """package kotlinx.parcelize
annotation class Parcelize
""",
}

app = root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader"
api = root / "api/src/main/kotlin/io/nightfish/lightnovelreader/api"
entity = app / "data/local/room/entity"
sources = [entity / f"{name}.kt" for name in (
    "Mergeable", "BookInformationEntity", "BookRecordEntity", "DailyCountEntity",
    "BookshelfEntity", "BookshelfBookMetadataEntity", "ChapterContentEntity",
    "ChapterInformationEntity", "FormattingRuleEntity", "UserDataEntity",
    "UserReadingDataEntity", "VolumeEntity",
)]
sources += [app / "data/serializer" / f"{name}.kt" for name in (
    "LocalDataIdentifierSerializer", "LocalDateTimeSerializer", "LocalDateSerializer",
    "LocalTimeSerializer", "JsonObjectSerializer", "CountSerializer", "UriSerializer",
)]
sources += [api / "identifier" / f"{name}.kt" for name in (
    "Identifier", "IdentifierSerializer", "Utils",
)]
sources += [api / "book/WordCount.kt", app / "utils/Identifier.kt", app / "data/statistics/Count.kt",
            app / "data/local/cbor/LocalData.kt", app / "data/local/cbor/AppLocalData.kt",
            app / "data/local/LocalDataMerge.kt", app / "data/local/SourceSnapshotStore.kt",
            root / "scripts/LocalDataRegressionCheck.kt"]

run_check("LocalDataRegressionCheckKt", sources, dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["kotlinSerialization"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["kotlinSerialization"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-cbor-jvm", versions["kotlinSerialization"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-jvm", versions["kotlinResult"]),
], stubs=stubs, compiler_plugins=[
    ("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", versions["kotlin"]),
])
