#!/usr/bin/env python3
"""Check actual independent release parsing with deterministic HTTP responses.

This compiles production metadata/source code against cached Kotlin libraries.
It does not access GitHub, download APKs, inspect signing keys, or install updates.
"""
from kotlin_check import root, run_check, versions

update = root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/update"
run_check("IndependentUpdateCheckKt", [
    update / "Release.kt",
    update / "IndependentUpdateMetadata.kt",
    update / "IndependentGitHubUpdateSource.kt",
    root / "scripts/IndependentUpdateCheck.kt",
], dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["kotlinSerialization"]),
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["kotlinSerialization"]),
])
