#!/usr/bin/env bash
set -euo pipefail

mkdir -p ci-reports
trap 'status=$?; if (( status != 0 )); then printf "::error::Official Android SDK installation failed; compileSdk 37 is unchanged. See ci-reports/sdk-*.txt.\n"; fi' EXIT
sdkmanager --list --channel=3 2>&1 | tee ci-reports/sdk-packages-channel3.txt
sdkmanager --channel=0 'platforms;android-37.0' 'ndk;28.2.13676358' 'cmake;3.22.1' 2>&1 | tee ci-reports/sdk-install.txt
python3 - <<'PY' | tee ci-reports/sdk-platform.txt
import os
from pathlib import Path
import xml.etree.ElementTree as ET

sdk = Path(os.environ["ANDROID_HOME"])
platform = sdk / "platforms/android-37.0"
package = ET.parse(platform / "package.xml").getroot().find("localPackage")
assert package is not None and package.attrib["path"] == "platforms;android-37.0"
details = package.find("type-details")
assert details is not None and details.findtext("api-level") == "37.0"
assert not details.findtext("codename")
assert (platform / "android.jar").is_file()
print((platform / "source.properties").read_text())
print("Verified official platforms;android-37.0 for AGP compileSdk = 37")
PY
