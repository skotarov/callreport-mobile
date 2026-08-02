#!/usr/bin/env bash
# Verifies that the single Relationship Manager build is Play-ready.
set -Eeuo pipefail
IFS=$'\n\t'

readonly APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$APP_DIR"
gradle --no-daemon :app:processReleaseMainManifest

manifest="$(find app/build/intermediates -type f -path '*release*' -name AndroidManifest.xml \
  | grep -E '/(merged_manifest|merged_manifests)/' \
  | head -n 1)"
[[ -n "$manifest" && -f "$manifest" ]] || {
  echo "ERROR: Could not locate the merged release manifest." >&2
  exit 1
}

grep -Fq 'applicationId = "com.onlineimoti.relationshipmanager"' app/build.gradle.kts || {
  echo "ERROR: The single build must use the Play package com.onlineimoti.relationshipmanager." >&2
  exit 1
}

grep -Fq 'PLAY_COMPANY_LICENSE_PRODUCT_ID' app/build.gradle.kts && grep -Fq 'rm_company_license' app/build.gradle.kts || {
  echo "ERROR: Missing Google Play product ID rm_company_license." >&2
  exit 1
}

grep -Fq 'PLAY_BILLING_ENABLED' app/build.gradle.kts && grep -Fq 'buildConfigField("boolean", "PLAY_BILLING_ENABLED", "true")' app/build.gradle.kts || {
  echo "ERROR: Google Play Billing must be enabled in the single build." >&2
  exit 1
}

python3 - "$manifest" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(path).getroot()

application = root.find("application")
if application is None:
    print("ERROR: The merged manifest has no <application> element.", file=sys.stderr)
    raise SystemExit(1)

permissions = {
    element.attrib.get(android + "name", "")
    for element in root.findall("uses-permission")
}
if "android.permission.INTERNET" not in permissions:
    print("ERROR: The app needs INTERNET permission for server and billing flows.", file=sys.stderr)
    raise SystemExit(1)

print("Single Play-ready manifest check passed.")
print(path)
PY