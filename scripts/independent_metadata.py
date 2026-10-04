"""Validate APK inspection evidence and build the independent update manifest.

Only the build script's successful apksigner/aapt inspections supply production
evidence. The functions here stay pure so schema and rejection paths can be
checked without building or pretending to verify an APK.
"""
import re
from pathlib import Path

APPLICATION_ID = "io.github.makerwen.lightnovelreader"
SHA256 = re.compile(r"[0-9a-fA-F]{64}")


def positive_integer(value, field):
    if type(value) is not int or value <= 0:
        raise ValueError(f"{field} must be a positive integer")
    return value


def sha256_hex(value, field):
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise ValueError(f"{field} must contain exactly 64 hexadecimal characters")
    return value.lower()


def apk_output(metadata):
    if metadata.get("applicationId") != APPLICATION_ID:
        raise ValueError("Unexpected AGP applicationId")
    elements = metadata.get("elements")
    if not isinstance(elements, list) or len(elements) != 1 or not isinstance(elements[0], dict):
        raise ValueError("Expected exactly one APK output")
    element = elements[0]
    filename = element.get("outputFile")
    if (not isinstance(filename, str) or not filename.endswith(".apk")
            or Path(filename).name != filename or "/" in filename or "\\" in filename):
        raise ValueError("AGP outputFile must be a single APK filename")
    positive_integer(element.get("versionCode"), "AGP versionCode")
    if not isinstance(element.get("versionName"), str) or not element["versionName"].strip():
        raise ValueError("AGP versionName must be nonempty")
    return element


def package_from_badging(badging):
    package = re.search(r"^package: (.+)$", badging, re.M)
    if not package:
        raise ValueError("aapt did not report an APK package")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package.group(1)))
    version_code = fields.get("versionCode", "")
    min_sdk = re.search(r"^sdkVersion:'([0-9]+)'\s*$", badging, re.M)
    if not version_code.isdecimal() or not min_sdk:
        raise ValueError("aapt did not report numeric versionCode and minSdk")
    if not fields.get("name") or not fields.get("versionName", "").strip():
        raise ValueError("aapt did not report applicationId and versionName")
    return {
        "applicationId": fields["name"],
        "versionCode": positive_integer(int(version_code), "APK versionCode"),
        "versionName": fields["versionName"],
        "minSdk": positive_integer(int(min_sdk.group(1)), "APK minSdk"),
    }


def signing_certificate_from_output(signature):
    certificates = re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$", signature, re.M
    )
    if len(certificates) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    return sha256_hex(certificates[0], "APK signingCertificateSha256")


def verify_inspection(metadata, signature, badging, manifest, build_config, expected_certificate=None):
    """Validate inspection output after both tool processes have exited successfully."""
    element = apk_output(metadata)
    package = package_from_badging(badging)
    if package["applicationId"] != APPLICATION_ID:
        raise ValueError("Unexpected APK applicationId")
    if (package["versionCode"] != element["versionCode"]
            or package["versionName"] != element["versionName"]):
        raise ValueError("APK version does not match AGP output metadata")
    certificate = signing_certificate_from_output(signature)
    if expected_certificate is not None and certificate != sha256_hex(expected_certificate, "Expected certificate"):
        raise ValueError("APK signing certificate does not match the expected installation signer")
    if "application-label:'LightNovelReader'" not in badging:
        raise ValueError("Unexpected application label")
    if "application-debuggable" in badging or "BenchmarkFixtureReceiver" in manifest:
        raise ValueError("Test-only application configuration found in APK")
    if "INDEPENDENT_BUILD = true;" not in build_config or "BENCHMARK = false;" not in build_config:
        raise ValueError("Incorrect independent build flags")
    for identity in (APPLICATION_ID + ".provider", APPLICATION_ID + ".androidx-startup",
                     "indi.dmzz_yyhyy.lightnovelreader.MainActivity"):
        if identity not in manifest:
            raise ValueError(f"Missing manifest identity: {identity}")
    return dict(package, signingCertificateSha256=certificate, signatureVerified=True,
                debuggable=False, independentBuild=True, benchmarkEnabled=False)


def update_metadata_from_report(report):
    """Create URL-free schema 1 data from the completed package-verification report."""
    if report.get("applicationId") != APPLICATION_ID:
        raise ValueError("Unexpected verification-report applicationId")
    for field, expected in (("signatureVerified", True), ("independentBuild", True),
                            ("debuggable", False), ("benchmarkEnabled", False)):
        if report.get(field) is not expected:
            raise ValueError(f"Verification report requires {field}={expected}")
    version_name = report.get("versionName")
    if not isinstance(version_name, str) or not version_name.strip():
        raise ValueError("Verification report requires versionName")
    apk_file = report.get("apkFile")
    # Reuse the filename checks without depending on any Gradle source constants.
    apk_output(dict(applicationId=APPLICATION_ID, elements=[dict(
        outputFile=apk_file, versionCode=report.get("versionCode"), versionName=version_name
    )]))
    return {
        "schemaVersion": 1,
        "applicationId": APPLICATION_ID,
        "versionCode": positive_integer(report.get("versionCode"), "versionCode"),
        "versionName": version_name,
        "apkFile": apk_file,
        "sizeBytes": positive_integer(report.get("bytes"), "sizeBytes"),
        "sha256": sha256_hex(report.get("sha256"), "sha256"),
        "minSdk": positive_integer(report.get("minSdk"), "minSdk"),
        "signingCertificateSha256": sha256_hex(report.get("signingCertificateSha256"), "signingCertificateSha256"),
    }
