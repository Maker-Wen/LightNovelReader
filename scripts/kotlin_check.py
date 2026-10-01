"""Compile focused production-source checks using the existing Gradle dependency cache."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
cache = gradle / "caches/modules-2/files-2.1"
versions = dict(re.findall(r'^(\w+) = "([^"]+)"', (root / "gradle/libs.versions.toml").read_text(), re.M))


def artifact(group, name, version="*", extension="jar"):
    matches = sorted(path for path in (cache / group / name).glob(f"{version}/*/*.{extension}")
                     if path.name == f"{name}-{path.parent.parent.name}.{extension}")
    if not matches:
        raise SystemExit(f"Missing cached {group}:{name}:{version}; resolve project dependencies with Gradle first.")
    return matches[-1]


def run_check(main_class, sources, dependencies=(), stubs=None, compiler_plugins=()):
    compiler = artifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", versions["kotlin"])
    pom = artifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", versions["kotlin"], "pom")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    compiler_dependencies = [artifact(*(dependency.findtext(f"m:{field}", namespaces=namespace)
                                       for field in ("groupId", "artifactId", "version")))
                             for dependency in ET.parse(pom).findall("m:dependencies/m:dependency", namespace)]
    annotations = artifact("org.jetbrains", "annotations")
    compiler_dependencies.append(annotations)
    # Keep the compiler's own dependencies separate from the versions used by production checks.
    runtime = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"]), annotations,
               *(artifact(*dependency) for dependency in dependencies)]
    classpath = os.pathsep.join(map(str, runtime))
    java = (Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else
            next(gradle.glob("jdks/**/bin/java"), Path(shutil.which("java") or "java")))
    with tempfile.TemporaryDirectory(prefix="kotlin-check-") as temporary:
        compiled_sources = list(sources)
        for name, source in (stubs or {}).items():
            path = Path(temporary) / f"{name}.kt"
            path.write_text(source)
            compiled_sources.append(path)
        output = Path(temporary) / "checks.jar"
        subprocess.run([str(java), "-cp", os.pathsep.join(map(str, [compiler, *compiler_dependencies])),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-classpath", classpath, "-d", str(output),
                        *(f"-Xplugin={artifact(*plugin)}" for plugin in compiler_plugins),
                        *map(str, compiled_sources)], check=True)
        subprocess.run([str(java), "-cp", str(output) + os.pathsep + classpath, main_class], check=True)
