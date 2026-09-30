#!/usr/bin/env python3
"""Run the reader's pure Kotlin checks using dependencies already cached by Gradle."""
from kotlin_check import root, run_check, versions

reader = root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader"
sources = [reader / "ReaderProgressMap.kt", reader / "ReaderPageIndexKey.kt", reader / "ReaderSeekGesture.kt",
           reader / "content/ReaderPosition.kt", root / "scripts/ReaderProgressCheck.kt", root / "scripts/ReaderSeekGestureCheck.kt"]
run_check("ReaderProgressCheckKt", sources, dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["kotlinSerialization"]),
])
