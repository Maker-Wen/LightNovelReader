#!/usr/bin/env python3
"""Run pure Kotlin scroll geometry checks with the compiler already cached by Gradle."""
from kotlin_check import root, run_check

sources = [root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/scroll/ScrollGeometryIndex.kt",
           root / "scripts/ScrollGeometryCheck.kt"]
run_check("ScrollGeometryCheckKt", sources)
