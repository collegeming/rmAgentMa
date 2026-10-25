#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def verify_renderer_assets(apk_path, expected):
    with zipfile.ZipFile(apk_path) as apk:
        actual = {name.removeprefix("assets/renderer/") for name in apk.namelist() if name.startswith("assets/renderer/") and not name.endswith("/")}
        assert actual == expected, f"{apk_path.name} renderer file set differs from the verified renderer"
        for name in sorted(expected):
            assert apk.read("assets/renderer/" + name) == (renderer / name).read_bytes(), f"{apk_path.name} renderer differs: {name}"


root = Path(os.environ["GITHUB_WORKSPACE"])
temporary = Path(os.environ["RUNNER_TEMP"])
renderer = temporary / "probe-renderer"
upstream = temporary / "aionui-source"
output = root / "ci-artifacts/host-probe"
output.mkdir(parents=True, exist_ok=False)
manifest = json.loads((renderer / "asset-manifest.json").read_text(encoding="utf-8"))
metadata = json.loads((renderer / "renderer-build.json").read_text(encoding="utf-8"))
assert metadata["assetManifestSha256"] == digest(renderer / "asset-manifest.json")
assert metadata["upstream"]["commit"] == run("git", "rev-parse", "HEAD", cwd=upstream)

product_apk = root / "android/app/build/outputs/apk/oss/debug/app-oss-debug.apk"
apks = {
    "rmAgentMa-host-probe-debug.apk": root / "android/host-probe/build/outputs/apk/debug/host-probe-debug.apk",
    "rmAgentMa-host-probe-tests.apk": root / "android/host-probe/build/outputs/apk/androidTest/debug/host-probe-debug-androidTest.apk",
    # Only this job builds the product APK with renderer assets, so ship it with the same evidence.
    "rmAgentMa-app-renderer-debug.apk": product_apk,
}
expected = {"asset-manifest.json", "renderer-build.json"} | {record["path"] for record in manifest["files"]}
audited_licenses = {"index.html", "NOTICE", "THIRD_PARTY_NOTICES.txt", "licenses/index.html", "licenses/coverage.json"}
assert audited_licenses <= expected, f"verified renderer lacks license evidence: {sorted(audited_licenses - expected)}"
verify_renderer_assets(apks["rmAgentMa-host-probe-debug.apk"], expected)
verify_renderer_assets(product_apk, expected)

# The release variant carries the same audited assets; shrinkResources and R8 must not drop any of them. The APK
# stays unsigned here because CI holds no release key, and the release renderer entry stays off until the release
# variant declares a host surface of its own.
release_apk = root / "android/app/build/outputs/apk/oss/release/app-oss-release-unsigned.apk"
assert release_apk.is_file(), "The release build must package the same audited renderer assets"
verify_renderer_assets(release_apk, expected)

sdk = Path(os.environ["ANDROID_HOME"])
signers = sorted(sdk.glob("build-tools/*/apksigner"), key=lambda path: tuple(int(part) for part in path.parent.name.split(".")) if all(part.isdigit() for part in path.parent.name.split(".")) else (0,))
assert signers, "Android SDK apksigner is required"
apk_records = []
for name, source in apks.items():
    shutil.copy2(source, output / name)
    certificate = run(str(signers[-1]), "verify", "--verbose", "--print-certs", str(output / name))
    assert "certificate SHA-256 digest:" in certificate, "Missing signing certificate fingerprint"
    (output / (name + ".certificate.txt")).write_text(certificate + "\n", encoding="utf-8")
    apk_records.append({"path": name, "sha256": digest(output / name), "certificate": name + ".certificate.txt"})

release_name = "rmAgentMa-app-renderer-release-unsigned.apk"
shutil.copy2(release_apk, output / release_name)

shutil.copytree(renderer, output / "renderer")
shutil.copy2(temporary / "renderer-tools/tools.json", output / "tools.json")
shutil.copy2(temporary / "renderer-tools/SHA256SUMS", output / "tool-distribution-SHA256SUMS")
shutil.copy2(upstream / "package.json", output / "upstream-package.json")
shutil.copy2(upstream / "bun.lock", output / "upstream-bun.lock")
shutil.copy2(root / "renderer/upstream.json", output / "upstream.json")
shutil.copy2(temporary / "aionui-renderer-work/.rmagent/prepare.json", output / "renderer-prepare.json")
shutil.copy2(temporary / "aionui-renderer-work/.rmagent/check-results.json", output / "renderer-check-results.json")

source_paths = ["renderer", ".github", "android"]
subprocess.run(["git", "archive", "--format=tar.gz", "-o", str(output / "main-source.tar.gz"), "HEAD", *source_paths], cwd=root, check=True)
subprocess.run(["git", "archive", "--format=tar.gz", "-o", str(output / "upstream-source.tar.gz"), "HEAD"], cwd=upstream, check=True)
with tarfile.open(output / "main-source.tar.gz", "r:gz") as source:
    packaged_pin = json.load(source.extractfile("renderer/upstream.json"))
    assert packaged_pin == json.loads((root / "renderer/upstream.json").read_text(encoding="utf-8"))

provenance = {
    "schemaVersion": 1,
    "repository": os.environ["GITHUB_REPOSITORY"],
    "sourceSha": run("git", "rev-parse", "HEAD", cwd=root),
    "eventSha": os.environ["GITHUB_SHA"],
    "sourceRef": os.environ["GITHUB_REF"],
    "runUrl": f'{os.environ["GITHUB_SERVER_URL"]}/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}',
    "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
    "upstream": metadata["upstream"],
    "tools": metadata["tools"],
    "patches": metadata["patches"],
    "overlay": metadata["overlay"],
    "buildScripts": metadata["buildScripts"],
    "sourceSha256": metadata["sourceSha256"],
    "assetManifestSha256": digest(renderer / "asset-manifest.json"),
    "rendererBuildSha256": digest(renderer / "renderer-build.json"),
    "apks": apk_records,
    "rendererHostAppApk": {
        "path": product_apk.relative_to(root).as_posix(),
        "sha256": digest(product_apk),
        "rendererAssets": "assets/renderer/** verified byte-identical to renderer/",
    },
    "rendererReleaseApk": {
        "path": release_name,
        "sha256": digest(output / release_name),
        "signed": False,
        "rendererAssets": "assets/renderer/** verified byte-identical to renderer/, NOTICE and licenses/coverage.json included",
        "rendererEntry": "off: -PRendererAssetsRelease=true packages the audited assets, but the release variant declares no renderer host surface yet",
    },
    "signing": "runner-generated debug key; fingerprints archived, private key excluded",
    "deviceTests": "compiled and packaged only; not executed on a device",
    "dshIntegration": "not executed in CI; no Agent installation or provider requests",
}
(output / "build-metadata.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
records = [f"{digest(path)}  {path.relative_to(output).as_posix()}\n" for path in sorted(output.rglob("*")) if path.is_file()]
(output / "SHA256SUMS").write_text("".join(records), encoding="utf-8")
