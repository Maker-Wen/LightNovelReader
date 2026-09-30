#!/usr/bin/env python3
"""Build and verify a locally signed APK for the independent development branch."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
BRANCH = "dev/independent-edition"
APPLICATION_ID = "io.github.makerwen.lightnovelreader"


def git(*args):
    return subprocess.check_output(["git", "-c", "core.fsmonitor=false", *args], cwd=ROOT)


def java_home():
    candidates = [Path(os.environ["JAVA_HOME"])] if os.environ.get("JAVA_HOME") else []
    if Path("/usr/libexec/java_home").exists():
        detected = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True)
        if detected.returncode == 0:
            candidates.append(Path(detected.stdout.strip()))
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates.extend(path.parent for path in sorted((gradle / "jdks").glob("**/release")))
    executable = shutil.which("java")
    if executable:
        candidates.append(Path(executable).resolve().parent.parent)
    for candidate in candidates:
        release = candidate / "release"
        if release.is_file() and re.search(r'^JAVA_VERSION="21(?:\.|\")', release.read_text(), re.M):
            return candidate
    raise SystemExit("JDK 21 is required. Set JAVA_HOME to a JDK 21 installation.")


def sdk_tools():
    local = ROOT / "local.properties"
    configured = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.M) if local.exists() else None
    sdk = configured.group(1).strip().replace(r"\:", ":").replace("\\\\", "\\") if configured else (
        os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    if not sdk:
        raise SystemExit("Set sdk.dir in local.properties or ANDROID_HOME.")
    suffix = ".bat" if os.name == "nt" else ""
    candidates = sorted((Path(sdk) / "build-tools").glob("*"),
                        key=lambda path: tuple(map(int, re.findall(r"\d+", path.name))), reverse=True)
    for candidate in candidates:
        if (candidate / ("apksigner" + suffix)).exists() and (candidate / ("aapt.exe" if os.name == "nt" else "aapt")).exists():
            return candidate / ("apksigner" + suffix), candidate / ("aapt.exe" if os.name == "nt" else "aapt")
    raise SystemExit("Android SDK build-tools with apksigner and aapt are required.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies only")
    args = parser.parse_args()
    branch = git("branch", "--show-current").decode().strip()
    if branch != BRANCH:
        raise SystemExit(f"Switch to {BRANCH} before building this distribution (current: {branch or 'detached HEAD'}).")
    if git("ls-files", "-u").strip():
        raise SystemExit("Resolve merge conflicts before building.")
    env = dict(os.environ, JAVA_HOME=str(java_home()))
    signer, aapt = sdk_tools()
    out = ROOT / "artifacts/apk/independent" / datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=False)
    source = {
        "branch": branch,
        "head": git("rev-parse", "HEAD").decode().strip(),
        "worktreeDirty": bool(git("status", "--porcelain", "--untracked-files=all").strip()),
        "stagedDiffSha256": hashlib.sha256(git("diff", "--cached", "--binary")).hexdigest(),
        "unstagedDiffSha256": hashlib.sha256(git("diff", "--binary")).hexdigest(),
        "untrackedFileSha256": {
            path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
            for path in git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")
            if path
        },
    }
    (out / "source-state.json").write_text(json.dumps(source, indent=2) + "\n")
    print(f"Building {APPLICATION_ID}\nBuild log: {out / 'build.log'}", flush=True)
    with tempfile.TemporaryDirectory(prefix="lnr-independent-") as temporary:
        init = Path(temporary) / "local-signing.gradle"
        init.write_text("""gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.plugins.withId('com.android.application') {
            project.extensions.getByName('androidComponents').finalizeDsl { android ->
                android.buildTypes.getByName('independent').signingConfig = android.signingConfigs.getByName('debug')
            }
        }
    }
}
""")
        command = ([str(ROOT / "gradlew.bat")] if os.name == "nt" else ["sh", "./gradlew"])
        command += ["--init-script", str(init), ":app:assembleIndependent"]
        if args.offline:
            command.append("--offline")
        with (out / "build.log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(f"Build failed. See {out / 'build.log'}")
    metadata_path = ROOT / "app/build/outputs/apk/independent/output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    element, = metadata["elements"]
    # Keep the artifact filename produced by the Android Gradle Plugin.
    apk = out / element["outputFile"]
    shutil.copy2(metadata_path.parent / element["outputFile"], apk)

    def run(*command):
        return subprocess.check_output(list(map(str, command)), env=env, text=True, stderr=subprocess.STDOUT)

    signature = run(signer, "verify", "--verbose", "--print-certs", apk)
    badging = run(aapt, "dump", "badging", apk)
    manifest = run(aapt, "dump", "xmltree", apk, "AndroidManifest.xml")
    config = (ROOT / "app/build/generated/source/buildConfig/independent/indi/dmzz_yyhyy/lightnovelreader/BuildConfig.java").read_text()
    if metadata["applicationId"] != APPLICATION_ID or f"name='{APPLICATION_ID}'" not in badging:
        raise SystemExit("Unexpected APK applicationId")
    if "application-label:'LightNovelReader'" not in badging:
        raise SystemExit("Unexpected application label")
    if "application-debuggable" in badging or "BenchmarkFixtureReceiver" in manifest:
        raise SystemExit("Test-only application configuration found in APK")
    if "INDEPENDENT_BUILD = true;" not in config or "BENCHMARK = false;" not in config:
        raise SystemExit("Incorrect independent build flags")
    for expected in [APPLICATION_ID + ".provider", APPLICATION_ID + ".androidx-startup",
                     "indi.dmzz_yyhyy.lightnovelreader.MainActivity"]:
        if expected not in manifest:
            raise SystemExit(f"Missing manifest identity: {expected}")
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    report = dict(source, applicationId=APPLICATION_ID, appName="LightNovelReader",
                  versionName=element["versionName"], versionCode=element["versionCode"],
                  apk=str(apk), bytes=apk.stat().st_size, sha256=sha, signatureVerified=True,
                  debuggable=False, independentBuild=True, benchmarkEnabled=False,
                  upstreamAppUpdatesEnabled=False)
    (out / "package-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    for name, text in [("signature.txt", signature), ("badging.txt", badging), ("manifest.txt", manifest)]:
        (out / name).write_text(text)
    (out / "SHA256SUMS").write_text(f"{sha}  {apk.name}\n")
    mapping = ROOT / "app/build/outputs/mapping/independent/mapping.txt"
    if mapping.exists():
        shutil.copy2(mapping, out / "mapping.txt")
    print(f"Verified APK: {apk}\nSHA-256: {sha}")


if __name__ == "__main__":
    main()
