#!/usr/bin/env bash

random_hex() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

generate_upload_key() {
  local secure_dir="${1:-$HOME/.local/share/relationship-manager/play-signing}"
  require_command keytool
  require_command python3
  require_command install
  secure_dir="$(python3 - "$secure_dir" <<'PY'
import os, sys
print(os.path.abspath(os.path.expanduser(sys.argv[1])))
PY
  )"
  local keystore="$secure_dir/relationship-manager-upload.jks"
  local secrets="$secure_dir/UPLOAD_KEY_SECRETS.txt"
  local properties="$APP_DIR/play-signing.properties"
  [[ ! -e "$keystore" ]] || fail "Keystore already exists: $keystore"
  [[ ! -e "$properties" ]] || fail "play-signing.properties already exists: $properties"

  local organization country
  read -r -p "Organization name for the upload certificate [Online Imoti OOD]: " organization
  organization="${organization:-Online Imoti OOD}"
  read -r -p "Two-letter country code [BG]: " country
  country="${country:-BG}"

  local store_secret
  store_secret="$(random_hex)"
  install -d -m 700 "$secure_dir"
  umask 077
  # PKCS12 uses the same password for the keystore and the private key.
  keytool -genkeypair \
    -keystore "$keystore" \
    -storetype PKCS12 \
    -storepass "$store_secret" \
    -alias relationship-manager-upload \
    -keypass "$store_secret" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Relationship Manager, O=$organization, C=$country" \
    -noprompt

  cat > "$properties" <<EOF
storeFile=$keystore
storeSecret=$store_secret
keyAlias=relationship-manager-upload
keySecret=$store_secret
EOF
  chmod 600 "$properties"
  cat > "$secrets" <<EOF
Relationship Manager upload-key recovery information

Keystore: $keystore
Alias: relationship-manager-upload
Store password: $store_secret
Key password: $store_secret

Back up this file and the .jks file offline. Do not commit, email or upload them.
EOF
  chmod 600 "$secrets"

  note "Upload key created"
  printf 'Keystore: %s\nSecrets:  %s\nProperties: %s\n\n' "$keystore" "$secrets" "$properties"
  keytool -list -v -keystore "$keystore" -storepass "$store_secret" -alias relationship-manager-upload \
    | grep -E 'Alias name:|SHA256:' || true
}

build_play_aab() {
  local version_code="${1:-}"
  local version_name="${2:-}"
  [[ -n "$version_code" && -n "$version_name" ]] || fail "Usage: build-play-aab <version-code> <version-name>"
  require_integer "$version_code"
  [[ -f "$APP_DIR/play-signing.properties" ]] || fail "Missing $APP_DIR/play-signing.properties. Run generate-upload-key first."
  require_command gradle
  require_command jarsigner

  note "Building signed Play AAB"
  (
    cd "$APP_DIR"
    gradle --no-daemon :app:bundleRelease \
      -PplayVersionCode="$version_code" \
      -PplayVersionName="$version_name"
  )

  local aab
  aab="$(find "$APP_DIR/app/build/outputs/bundle/release" -maxdepth 1 -type f -name '*.aab' -print -quit)"
  [[ -n "$aab" && -f "$aab" ]] || fail "Gradle completed but no release AAB was found."
  jarsigner -verify "$aab" >/dev/null || fail "The generated AAB is not signed correctly."
  printf '\nSigned AAB ready:\n%s\n' "$aab"
}

publish_internal() {
  local key_file="${1:-}"
  local aab_path="${2:-}"
  [[ -n "$key_file" && -n "$aab_path" ]] || fail "Usage: publish-internal <service-account-json> <aab-path>"
  require_file "$aab_path"
  require_command jq
  start_service_account_session "$key_file"

  note "Creating Google Play edit"
  local edit edit_id upload version_code release_payload
  edit="$(api_call POST "$API_BASE/applications/$PACKAGE_ID/edits" json '{}')"
  edit_id="$(printf '%s' "$edit" | jq -r '.id // empty')"
  [[ -n "$edit_id" ]] || fail "Google Play did not return an edit ID."

  note "Uploading Android App Bundle"
  upload="$(api_call POST "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE_ID/edits/$edit_id/bundles?uploadType=media" binary "$aab_path")"
  version_code="$(printf '%s' "$upload" | jq -r '.versionCode // empty')"
  [[ -n "$version_code" ]] || fail "Google Play did not return the uploaded version code."

  release_payload="$(jq -n --arg version_code "$version_code" --arg name "Internal $version_code" '{releases: [{name: $name, status: "completed", versionCodes: [$version_code]}]}')"
  note "Assigning version $version_code to the internal testing track"
  api_call PUT "$API_BASE/applications/$PACKAGE_ID/edits/$edit_id/tracks/internal" json "$release_payload" >/dev/null

  note "Committing Google Play edit"
  api_call POST "$API_BASE/applications/$PACKAGE_ID/edits/$edit_id:commit" json '{}' >/dev/null
  printf '\nInternal-test release published successfully. Version code: %s\n' "$version_code"
}

prepare_server_env() {
  local remote_key_path="${1:-}"
  local data_dir="${2:-}"
  local output_file="${3:-$HOME/.config/relationship-manager/relationship-manager-play.env}"
  [[ -n "$remote_key_path" && -n "$data_dir" ]] || fail "Usage: prepare-server-env <remote-service-account-json-path> <data-directory> [output-file]"
  [[ "$remote_key_path" = /* ]] || fail "remote-service-account-json-path must be an absolute server path."
  [[ "$data_dir" = /* ]] || fail "data-directory must be an absolute server path."
  require_command install
  install -d -m 700 "$(dirname "$output_file")"
  umask 077
  cat > "$output_file" <<EOF
RELATIONSHIP_MANAGER_DATA_DIR=$data_dir
RELATIONSHIP_MANAGER_PLAY_PACKAGE_NAME=$PACKAGE_ID
RELATIONSHIP_MANAGER_PLAY_PRODUCT_IDS=$PRODUCT_ID
RELATIONSHIP_MANAGER_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE=$remote_key_path
EOF
  chmod 600 "$output_file"
  printf '\nCreated server environment template:\n%s\n\n' "$output_file"
  cat <<EOF
Copy the service-account JSON to this server path with owner-only permissions:
  $remote_key_path

Then configure your PHP-FPM/Apache/container deployment to load this environment file:
  $output_file

Do not put either file below the public web root and do not commit them to Git.
EOF
}
