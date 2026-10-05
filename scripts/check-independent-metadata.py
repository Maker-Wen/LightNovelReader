#!/usr/bin/env python3
"""Check metadata parsers and release guards using synthetic reports and temporary Git repos.

These checks do not build, sign, or verify an APK. No fixture is written as an
APK or as a production package-verification.json/update.json artifact.
"""
import argparse
import copy
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
from independent_metadata import APPLICATION_ID, apk_output, package_from_badging, signing_certificate_from_output, update_metadata_from_report, verify_inspection

spec = importlib.util.spec_from_file_location("independent_build", Path(__file__).with_name("build-independent.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

CERTIFICATE = "a" * 64
VERSION_NAME = "1.3.0_MakerWen (2026/10/04)"
AGP = dict(applicationId=APPLICATION_ID, elements=[dict(
    outputFile="LightNovelReader-1.3.0.apk", versionCode=10300012, versionName=VERSION_NAME
)])
BADGING = (f"package: name='{APPLICATION_ID}' versionCode='10300012' versionName='{VERSION_NAME}'\n"
           "sdkVersion:'24'\napplication-label:'LightNovelReader'\n")
SIGNATURE = f"Verifies\nSigner #1 certificate SHA-256 digest: {CERTIFICATE}\n"
MANIFEST = f"{APPLICATION_ID}.provider\n{APPLICATION_ID}.androidx-startup\nindi.dmzz_yyhyy.lightnovelreader.MainActivity"
CONFIG = "public static final boolean INDEPENDENT_BUILD = true;\npublic static final boolean BENCHMARK = false;"
REPORT = dict(applicationId=APPLICATION_ID, versionCode=10300012, versionName=VERSION_NAME,
              apkFile=AGP["elements"][0]["outputFile"], bytes=3_000_000_000, sha256="b" * 64,
              minSdk=24, signingCertificateSha256=CERTIFICATE, signatureVerified=True,
              independentBuild=True, debuggable=False, benchmarkEnabled=False)


class MetadataChecks(unittest.TestCase):
    def test_url_free_schema_preserves_actual_report_types(self):
        result = update_metadata_from_report(dict(REPORT, downloadUrl="ignored", releaseUrl="ignored"))
        self.assertEqual(set(result), {"schemaVersion", "applicationId", "versionCode", "versionName",
                                     "apkFile", "sizeBytes", "sha256", "minSdk", "signingCertificateSha256"})
        self.assertEqual(result["schemaVersion"], 1)
        self.assertEqual(result["sizeBytes"], 3_000_000_000)
        self.assertIs(type(result["versionCode"]), int)
        self.assertIs(type(result["sizeBytes"]), int)
        self.assertEqual(result["apkFile"], AGP["elements"][0]["outputFile"])

    def test_inspection_parser_reads_apk_version_sdk_and_signer(self):
        actual = verify_inspection(AGP, SIGNATURE, BADGING, MANIFEST, CONFIG, CERTIFICATE.upper())
        self.assertEqual(actual["versionName"], VERSION_NAME)
        self.assertEqual(actual["versionCode"], 10300012)
        self.assertEqual(actual["minSdk"], 24)
        self.assertEqual(actual["signingCertificateSha256"], CERTIFICATE)

    def test_agp_cannot_override_apk_versions(self):
        for field, value in (("versionCode", 10300011), ("versionName", "wrong")):
            with self.subTest(field=field):
                metadata = copy.deepcopy(AGP)
                metadata["elements"][0][field] = value
                with self.assertRaisesRegex(ValueError, "does not match"):
                    verify_inspection(metadata, SIGNATURE, BADGING, MANIFEST, CONFIG)

    def test_signer_must_exist_be_unique_and_match_expected(self):
        for output in ("", SIGNATURE + SIGNATURE, SIGNATURE.replace(CERTIFICATE, "a" * 63)):
            with self.subTest(output=output), self.assertRaises(ValueError):
                signing_certificate_from_output(output)
        with self.assertRaisesRegex(ValueError, "installation signer"):
            verify_inspection(AGP, SIGNATURE, BADGING, MANIFEST, CONFIG, "c" * 64)

    def test_sdk_interval_signer_reports_one_certificate_identity(self):
        output = (f"Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {CERTIFICATE}\n"
                  f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {CERTIFICATE.upper()}\n")
        self.assertEqual(signing_certificate_from_output(output), CERTIFICATE)
        self.assertEqual(verify_inspection(AGP, output, BADGING, MANIFEST, CONFIG, CERTIFICATE)
                         ["signingCertificateSha256"], CERTIFICATE)

    def test_sdk_interval_reports_reject_multiple_certificate_identities(self):
        output = (f"Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {CERTIFICATE}\n"
                  f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {'c' * 64}\n")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            signing_certificate_from_output(output)

    def test_sdk_interval_reports_reject_duplicate_or_mixed_labels(self):
        interval = f"Signer (minSdkVersion=28, maxSdkVersion=32) certificate SHA-256 digest: {CERTIFICATE}\n"
        for output in (interval * 2, interval + SIGNATURE, SIGNATURE.replace("Signer #1", "Signer #2")):
            with self.subTest(output=output), self.assertRaises(ValueError):
                signing_certificate_from_output(output)

    def test_badging_requires_numeric_sdk_and_version_code(self):
        for badging in (BADGING.replace("sdkVersion:'24'\n", ""),
                        BADGING.replace("sdkVersion:'24'", "sdkVersion:'S'"),
                        BADGING.replace("versionCode='10300012'", "versionCode='1.5'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                package_from_badging(badging)

    def test_independent_identity_and_flags_are_required(self):
        cases = [dict(badging=BADGING.replace(APPLICATION_ID, "upstream.reader")),
                 dict(badging=BADGING + "application-debuggable\n"),
                 dict(manifest=MANIFEST + "\nBenchmarkFixtureReceiver"),
                 dict(manifest=""), dict(build_config=CONFIG.replace("INDEPENDENT_BUILD = true", "INDEPENDENT_BUILD = false"))]
        for changes in cases:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                evidence = dict(metadata=AGP, signature=SIGNATURE, badging=BADGING, manifest=MANIFEST, build_config=CONFIG)
                evidence.update(changes)
                verify_inspection(**evidence)

    def test_report_rejects_unverified_or_non_independent_packages(self):
        for field, value in (("signatureVerified", False), ("signatureVerified", 1), ("independentBuild", False),
                             ("debuggable", True), ("benchmarkEnabled", True), ("applicationId", "upstream.reader")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                update_metadata_from_report(dict(REPORT, **{field: value}))

    def test_schema_rejects_invalid_types_sizes_and_digests(self):
        for field, value in (("versionCode", "10300012"), ("versionCode", True), ("versionCode", 0),
                             ("bytes", 1.5), ("bytes", 0), ("minSdk", False), ("sha256", "a" * 63),
                             ("signingCertificateSha256", "z" * 64), ("versionName", "")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                update_metadata_from_report(dict(REPORT, **{field: value}))

    def test_only_one_safe_agp_filename_is_accepted(self):
        for filename in ("../release.apk", "a/release.apk", "a\\release.apk", "/release.apk", "update.json"):
            with self.subTest(filename=filename), self.assertRaises(ValueError):
                metadata = copy.deepcopy(AGP)
                metadata["elements"][0]["outputFile"] = filename
                apk_output(metadata)
        with self.assertRaises(ValueError):
            apk_output(dict(AGP, elements=AGP["elements"] * 2))


class ReleaseGuardChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="lnr-release-guard-")
        self.root = Path(self.temporary.name)
        self.root_patch = patch.object(build, "ROOT", self.root)
        self.root_patch.start()
        self.git("init", "-q", "-b", build.BRANCH)
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Release guard fixture")
        self.git("commit", "-q", "--allow-empty", "-m", "independent fixture")
        self.commit = self.git("rev-parse", "HEAD")
        self.git("tag", "fixture-v1")

    def tearDown(self):
        self.root_patch.stop()
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", "-c", "core.fsmonitor=false", *args], cwd=self.root,
                                       stderr=subprocess.STDOUT, text=True).strip()

    def test_default_branch_allows_dirty_local_build(self):
        (self.root / "local-fixture.txt").write_text("untracked fixture")
        self.assertEqual(build.validate_build_ref()["head"], self.commit)

    def test_default_mode_rejects_detached_head(self):
        self.git("checkout", "-q", "--detach", self.commit)
        with self.assertRaisesRegex(SystemExit, "Switch to"):
            build.validate_build_ref()

    def test_exact_detached_tag_or_commit_is_allowed(self):
        self.git("checkout", "-q", "--detach", self.commit)
        self.assertEqual(build.validate_build_ref(release_tag="fixture-v1")["head"], self.commit)
        self.assertEqual(build.validate_build_ref(release_ref=self.commit)["head"], self.commit)

    def test_release_mode_rejects_dirty_checkout(self):
        (self.root / "local-fixture.txt").write_text("untracked fixture")
        with self.assertRaisesRegex(SystemExit, "clean checkout"):
            build.validate_build_ref(release_tag="fixture-v1")

    def test_release_mode_rejects_head_mismatch(self):
        self.git("commit", "-q", "--allow-empty", "-m", "next fixture")
        with self.assertRaisesRegex(SystemExit, "exact"):
            build.validate_build_ref(release_tag="fixture-v1")

    def test_tag_must_belong_to_independent_history(self):
        self.git("checkout", "-q", "--orphan", "unrelated")
        self.git("commit", "-q", "--allow-empty", "-m", "unrelated fixture")
        self.git("tag", "unrelated-v1")
        with self.assertRaisesRegex(SystemExit, "history"):
            build.validate_build_ref(release_tag="unrelated-v1")

    def test_detached_ci_can_use_remote_history(self):
        self.git("update-ref", f"refs/remotes/origin/{build.BRANCH}", self.commit)
        self.git("checkout", "-q", "--detach", self.commit)
        self.git("branch", "-D", build.BRANCH)
        self.assertEqual(build.validate_build_ref(release_ref=self.commit)["head"], self.commit)

    def test_no_independent_history_is_rejected(self):
        self.git("checkout", "-q", "--detach", self.commit)
        self.git("branch", "-D", build.BRANCH)
        with self.assertRaisesRegex(SystemExit, "Fetch or create"):
            build.validate_build_ref(release_ref=self.commit)

    def test_empty_refs_and_tag_expressions_are_rejected(self):
        for options in (dict(release_tag=""), dict(release_ref=" "), dict(release_tag="fixture-v1^")):
            with self.subTest(options=options), self.assertRaises(SystemExit):
                build.validate_build_ref(**options)


class SigningEnvironmentChecks(unittest.TestCase):
    def arguments(self, **values):
        return argparse.Namespace(**dict(dict(keystore=None, key_alias=None, expected_signing_certificate_sha256=None), **values))

    def test_default_signer_ignores_ambient_keystore_selection(self):
        env = build.signing_environment(self.arguments(), dict(INDEPENDENT_KEYSTORE_FILE="ambient", INDEPENDENT_KEY_ALIAS="ambient"))
        self.assertNotIn("INDEPENDENT_KEYSTORE_FILE", env)
        self.assertNotIn("INDEPENDENT_KEY_ALIAS", env)

    def test_fixed_signer_requires_existing_file_alias_and_passwords(self):
        with tempfile.TemporaryDirectory(prefix="lnr-signing-input-") as temporary:
            fixture = Path(temporary) / "empty-input-fixture"
            fixture.touch()  # Validate inputs only; this is never used to sign an APK.
            for args, env in ((self.arguments(keystore=fixture), {}),
                              (self.arguments(keystore=fixture, key_alias="fixture"), {}),
                              (self.arguments(keystore=fixture, key_alias="fixture"), dict(INDEPENDENT_STORE_PASSWORD="fixture"))):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    build.signing_environment(args, env)

    def test_invalid_expected_certificate_fails_before_signing(self):
        with self.assertRaises(SystemExit):
            build.signing_environment(self.arguments(expected_signing_certificate_sha256="not-a-certificate"), {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
