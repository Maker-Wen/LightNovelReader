#!/usr/bin/env python3
"""Build and verify a signed APK and update.json for the independent distribution."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True  # Imported helpers must not dirty a clean release checkout.
from independent_metadata import APPLICATION_ID, apk_output, sha256_hex, update_metadata_from_report, verify_inspection

ROOT = Path(__file__).resolve().parent.parent
BRANCH = "dev/independent-edition"


def git(*args):
    return subprocess.check_output(["git", "-c", "core.fsmonitor=false", *args], cwd=ROOT)


def validate_build_ref(release_tag=None, release_ref=None):
    branch = git("branch", "--show-current").decode().strip()
    head = git("rev-parse", "HEAD").decode().strip()
    if git("ls-files", "-u").strip():
        raise SystemExit("Resolve merge conflicts before building.")
    if release_tag is None and release_ref is None:
        if branch != BRANCH:
            raise SystemExit(f"Switch to {BRANCH} before building this distribution (current: {branch or 'detached HEAD'}).")
        return dict(branch=branch, head=head)
    if release_tag is not None and release_ref is not None:
        raise SystemExit("Use only one of --release-tag and --release-ref.")
    if (release_tag is not None and not release_tag.strip()) or (release_ref is not None and not release_ref.strip()):
        raise SystemExit("Release tag/ref must be nonempty.")
    ref = f"refs/tags/{release_tag}" if release_tag is not None else release_ref
    if release_tag is not None:
        valid = subprocess.run(["git", "check-ref-format", ref], cwd=ROOT, capture_output=True)
        if valid.returncode:
            raise SystemExit("--release-tag must be a literal valid tag name.")
    try:
        commit = git("rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()
    except subprocess.CalledProcessError:
        raise SystemExit("Release ref must resolve to an existing commit or tag.") from None
    if commit != head:
        raise SystemExit("Check out the exact --release-tag/--release-ref commit before building.")
    if git("status", "--porcelain", "--untracked-files=all").strip():
        raise SystemExit("Release builds require a clean checkout of the confirmed commit.")
    # A normal checkout has a local branch; CI's detached tag checkout uses origin.
    has_independent_history = False
    for independent_ref in (f"refs/heads/{BRANCH}", f"refs/remotes/origin/{BRANCH}"):
        exists = subprocess.run(["git", "show-ref", "--verify", "--quiet", independent_ref], cwd=ROOT)
        if exists.returncode == 0:
            has_independent_history = True
            ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", commit, independent_ref], cwd=ROOT)
            if ancestor.returncode == 0:
                return dict(branch=branch, head=head, releaseRef=ref, releaseTag=release_tag)
    if has_independent_history:
        raise SystemExit(f"Release commit must belong to {BRANCH} history.")
    raise SystemExit(f"Fetch or create {BRANCH} before verifying release history.")


def signing_environment(args, env):
    if args.expected_signing_certificate_sha256:
        try:
            args.expected_signing_certificate_sha256 = sha256_hex(
                args.expected_signing_certificate_sha256, "Expected signing certificate"
            )
        except ValueError as error:
            raise SystemExit(str(error)) from None
    if args.keystore:
        if not args.keystore.is_file():
            raise SystemExit("The signing keystore file does not exist.")
        if not args.key_alias:
            raise SystemExit("--keystore requires --key-alias.")
        for key in ("INDEPENDENT_STORE_PASSWORD", "INDEPENDENT_KEY_PASSWORD"):
            if not env.get(key):
                raise SystemExit(f"--keystore requires {key} in the environment.")
        env.update(INDEPENDENT_KEYSTORE_FILE=str(args.keystore.resolve()), INDEPENDENT_KEY_ALIAS=args.key_alias)
    elif args.key_alias:
        raise SystemExit("--key-alias requires --keystore.")
    else:
        # Ambient CI variables must never replace the default local Android signer.
        env.pop("INDEPENDENT_KEYSTORE_FILE", None)
        env.pop("INDEPENDENT_KEY_ALIAS", None)
    return env


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
    release = parser.add_mutually_exclusive_group()
    release.add_argument("--release-tag", help="Build the checked-out tag after verifying independent branch history")
    release.add_argument("--release-ref", help="Build an exact clean checked-out commit/ref from independent branch history")
    parser.add_argument("--keystore", type=Path, help="Use a fixed signing keystore; passwords come from INDEPENDENT_*_PASSWORD")
    parser.add_argument("--key-alias", help="Signing key alias for --keystore")
    parser.add_argument("--expected-signing-certificate-sha256", help="Reject APKs whose signer differs from this SHA-256")
    args = parser.parse_args()
    checkout = validate_build_ref(args.release_tag, args.release_ref)
    env = signing_environment(args, dict(os.environ, JAVA_HOME=str(java_home())))
    signer, aapt = sdk_tools()
    out = ROOT / "artifacts/apk/independent" / datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=False)
    source = {
        **checkout,
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
                def keystoreFile = System.getenv('INDEPENDENT_KEYSTORE_FILE')
                def signing = android.signingConfigs.getByName('debug')
                if (keystoreFile) {
                    signing = android.signingConfigs.maybeCreate('independentFixed')
                    signing.storeFile = new File(keystoreFile)
                    signing.storePassword = System.getenv('INDEPENDENT_STORE_PASSWORD')
                    signing.keyAlias = System.getenv('INDEPENDENT_KEY_ALIAS')
                    signing.keyPassword = System.getenv('INDEPENDENT_KEY_PASSWORD')
                }
                android.buildTypes.getByName('independent').signingConfig = signing
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
    if args.release_tag or args.release_ref:
        # Do not label concurrent edits or a moved HEAD as the confirmed release.
        if validate_build_ref(args.release_tag, args.release_ref)["head"] != checkout["head"]:
            raise SystemExit("Release checkout changed during the build.")
    metadata_path = ROOT / "app/build/outputs/apk/independent/output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    try:
        element = apk_output(metadata)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    # Keep the artifact filename produced by the Android Gradle Plugin.
    apk = out / element["outputFile"]
    shutil.copy2(metadata_path.parent / element["outputFile"], apk)

    def run(*command):
        return subprocess.check_output(list(map(str, command)), env=env, text=True, stderr=subprocess.STDOUT)

    signature = run(signer, "verify", "--verbose", "--print-certs", apk)
    badging = run(aapt, "dump", "badging", apk)
    manifest = run(aapt, "dump", "xmltree", apk, "AndroidManifest.xml")
    config = (ROOT / "app/build/generated/source/buildConfig/independent/indi/dmzz_yyhyy/lightnovelreader/BuildConfig.java").read_text()
    try:
        verification = verify_inspection(metadata, signature, badging, manifest, config,
                                         args.expected_signing_certificate_sha256)
    except ValueError as error:
        raise SystemExit(str(error)) from None
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    report = dict(source, **verification, appName="LightNovelReader", apk=str(apk), apkFile=apk.name,
                  bytes=apk.stat().st_size, sha256=sha, upstreamAppUpdatesEnabled=False)
    update = update_metadata_from_report(report)
    (out / "package-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    (out / "update.json").write_text(json.dumps(update, indent=2, ensure_ascii=False) + "\n")
    for name, text in [("signature.txt", signature), ("badging.txt", badging), ("manifest.txt", manifest)]:
        (out / name).write_text(text)
    (out / "SHA256SUMS").write_text(f"{sha}  {apk.name}\n")
    mapping = ROOT / "app/build/outputs/mapping/independent/mapping.txt"
    if mapping.exists():
        shutil.copy2(mapping, out / "mapping.txt")
    print(f"Verified APK: {apk}\nSHA-256: {sha}\nUpdate metadata: {out / 'update.json'}")


if __name__ == "__main__":
    main()
