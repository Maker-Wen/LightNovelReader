#!/usr/bin/env python3
"""Check the production setting bridge using cached Compose runtime and JVM dependencies."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from kotlin_check import artifact, cache, gradle, root, versions

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=root / "app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/setting/AbstractSettingState.kt")
args = parser.parse_args()
compiler = artifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", versions["kotlin"])
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = artifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", versions["kotlin"], "pom")
compiler_dependencies = [artifact(*(dependency.findtext(f"m:{field}", namespaces=namespace)
                                   for field in ("groupId", "artifactId", "version")))
                         for dependency in ET.parse(pom).findall("m:dependencies/m:dependency", namespace)]
annotations = artifact("org.jetbrains", "annotations")
compiler_dependencies.append(annotations)
runtime = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"]), annotations,
           artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["kotlinxCoroutinesCore"]),
           artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-test-jvm", versions["kotlinxCoroutinesCore"]),
           artifact("androidx.collection", "collection-jvm", "1.5.0"),
           artifact("androidx.annotation", "annotation-jvm", "1.10.0")]
local = root / "local.properties"
configured = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.M) if local.exists() else None
sdk_path = configured.group(1).strip().replace(r"\:", ":").replace("\\\\", "\\") if configured else (
    os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
if not sdk_path:
    raise SystemExit("Set sdk.dir in local.properties or ANDROID_HOME.")
sdk = Path(sdk_path)
android_jars = sorted((sdk / "platforms").glob("*/android.jar"))
if not android_jars:
    raise SystemExit(f"Missing Android SDK platform jar under {sdk}")
runtime.append(android_jars[-1])
java = (Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else
        next(gradle.glob("jdks/**/bin/java"), Path(shutil.which("java") or "java")))
with tempfile.TemporaryDirectory(prefix="setting-state-check-") as temporary:
    for name in ("runtime-android", "runtime-annotation-android"):
        aars = list((cache / "androidx.compose.runtime" / name / versions["runtime"]).glob("*/*.aar"))
        if len(aars) != 1:
            raise SystemExit(f"Expected one cached {name}:{versions['runtime']} AAR")
        destination = Path(temporary) / f"{name}.jar"
        with zipfile.ZipFile(aars[0]) as archive:
            destination.write_bytes(archive.read("classes.jar"))
        runtime.append(destination)
    classpath = os.pathsep.join(map(str, runtime))
    output = Path(temporary) / "checks.jar"
    sources = [args.source, root / "api/src/main/kotlin/io/nightfish/lightnovelreader/api/userdata/UserData.kt",
               root / "scripts/SettingStateCheck.kt"]
    subprocess.run([str(java), "-cp", os.pathsep.join(map(str, [compiler, *compiler_dependencies])),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "11",
                    "-classpath", classpath, "-d", str(output), *map(str, sources)], check=True)
    subprocess.run([str(java), "-cp", str(output) + os.pathsep + classpath, "SettingStateCheckKt"], check=True)
