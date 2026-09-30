#!/usr/bin/env python3
"""Check production priority scheduling with cached JVM dependencies (no network)."""
from kotlin_check import root, run_check, versions


run_check("PriorityDispatcherCheckKt", [
    root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/coroutine/PriorityDispatcher.kt",
    root / "scripts/PriorityDispatcherCheck.kt",
], dependencies=[
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-jvm", versions["kotlinResult"]),
    ("com.michael-bull.kotlin-result", "kotlin-result-coroutines-jvm", versions["kotlinResult"]),
])
