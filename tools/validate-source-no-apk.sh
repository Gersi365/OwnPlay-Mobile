#!/usr/bin/env bash
set -euo pipefail

# Drive-native source-only validation for OwnPlay Mobile.
# This script deliberately avoids assemble, bundle, package, install, and connected-device tasks.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINNED_GRADLE_VERSION="9.6.0"
PINNED_JAVA_MAJOR="21"
PINNED_ANDROID_API_LEVEL="36"
FFMPEG_AAR="app/libs/media3-decoder-ffmpeg-1.11.0-ffmpeg6.0.aar"
FFMPEG_PROVENANCE="docs/third_party/FFMPEG_AUDIO_DECODER.md"
PINNED_FFMPEG_AAR_SHA256="3997eab5910483a4b7ab2928def2894379b2d9664ac54291c488669380f98734"
SOURCE_MANIFEST="${OWNPLAY_SOURCE_MANIFEST:-$ROOT_DIR/../SOURCE_TREE_SHA256.txt}"
FFMPEG_LICENSE_FILES=(
  "app/src/main/assets/licenses/ffmpeg/LICENSE.md"
  "app/src/main/assets/licenses/ffmpeg/COPYING.LGPLv2.1"
  "app/src/main/assets/licenses/ffmpeg/COPYING.LGPLv3"
)
cd "$ROOT_DIR"

find_packaged_artifacts() {
  find . -type f \( -name '*.apk' -o -name '*.aab' \) -print
}

verify_source_manifest() {
  if [[ ! -f "$SOURCE_MANIFEST" ]]; then
    echo "ERROR: Source integrity manifest is missing: $SOURCE_MANIFEST" >&2
    echo "Set OWNPLAY_SOURCE_MANIFEST to the current SOURCE_TREE_SHA256.txt path." >&2
    exit 5
  fi
  sha256sum -c "$SOURCE_MANIFEST"
  echo "Validated source tree against: $SOURCE_MANIFEST"
}

verify_source_hygiene() {
  local forbidden_paths private_key_markers
  forbidden_paths="$(
    find . -type f \( \
      -name '.env' -o -name '.env.*' -o -name 'secrets.properties' -o \
      -name 'keystore.properties' -o -name 'qa-signing-secret.txt' -o \
      -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o \
      -name '*.pfx' -o -name '*.key' -o -name '*.pem' \
    \) -print
  )"
  if [[ -n "$forbidden_paths" ]]; then
    echo "ERROR: Secret/signing material must not be present in the current source tree:" >&2
    printf '%s\n' "$forbidden_paths" >&2
    exit 6
  fi

  private_key_markers="$(
    grep -RIlE \
      --exclude='*.aar' --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin \
      -- '-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----' . \
      || true
  )"
  if [[ -n "$private_key_markers" ]]; then
    echo "ERROR: Private-key material was detected in source text files:" >&2
    printf '%s\n' "$private_key_markers" >&2
    exit 6
  fi
}

verify_download_foreground_wiring() {
  local worker="app/src/main/java/app/ownplay/mobile/downloads/data/DownloadWorker.kt"
  [[ -f "$worker" ]] || { echo "ERROR: Missing $worker" >&2; exit 8; }
  grep -Fq 'setForeground(services.downloadNotifications.foregroundInfo(initial))' "$worker" || {
    echo "ERROR: Long-running downloads must promote execution to foreground." >&2; exit 8;
  }
  if grep -Fq 'val foregroundUpdates = if (preferences.notificationsEnabled)' "$worker"; then
    echo "ERROR: Download notification preference must not disable foreground execution." >&2
    exit 8
  fi
  echo "Validated long-running download foreground wiring."
}

verify_ffmpeg_runtime_wiring() {
  local playback_engine="app/src/main/java/app/ownplay/mobile/feature/playback/data/Media3PlaybackEngine.kt"
  [[ -f "$playback_engine" ]] || { echo "ERROR: Missing $playback_engine" >&2; exit 7; }
  grep -Fq 'DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON' "$playback_engine" || {
    echo "ERROR: Bundled FFmpeg extension is not enabled in playback runtime wiring." >&2; exit 7;
  }
  grep -Fq '.setEnableDecoderFallback(true)' "$playback_engine" || {
    echo "ERROR: Decoder fallback must remain enabled for the FFmpeg audio fallback path." >&2; exit 7;
  }
  echo "Validated Media3 FFmpeg extension renderer and decoder fallback wiring."
}

verify_ffmpeg_aar_integrity() {
  [[ -f "$FFMPEG_AAR" ]] || { echo "ERROR: Missing $FFMPEG_AAR" >&2; exit 7; }
  [[ -f "$FFMPEG_PROVENANCE" ]] || { echo "ERROR: Missing $FFMPEG_PROVENANCE" >&2; exit 7; }
  local license_file documented_sha actual_sha
  for license_file in "${FFMPEG_LICENSE_FILES[@]}"; do
    [[ -f "$license_file" ]] || { echo "ERROR: Missing $license_file" >&2; exit 7; }
  done
  documented_sha="$(sed -nE 's/^- AAR SHA-256: `([0-9a-f]{64})`$/\1/p' "$FFMPEG_PROVENANCE" | head -n 1)"
  [[ "$documented_sha" == "$PINNED_FFMPEG_AAR_SHA256" ]] || {
    echo "ERROR: FFmpeg provenance checksum does not match the pinned validation checksum." >&2; exit 7;
  }
  actual_sha="$(sha256sum "$FFMPEG_AAR" | awk '{ print $1 }')"
  [[ "$actual_sha" == "$PINNED_FFMPEG_AAR_SHA256" ]] || {
    echo "ERROR: FFmpeg decoder AAR checksum mismatch." >&2
    echo "Expected: $PINNED_FFMPEG_AAR_SHA256" >&2
    echo "Actual:   $actual_sha" >&2
    exit 7
  }
  echo "Validated FFmpeg decoder AAR SHA-256: $actual_sha"
  echo "Validated FFmpeg provenance and license assets."
}

verify_room_schemas() {
  local schema_dir="app/schemas/app.ownplay.mobile.data.db.OwnPlayDatabase"
  local database_source="app/src/main/java/app/ownplay/mobile/data/db/OwnPlayDatabase.kt"
  local version schema_file
  for version in 1 2 3; do
    schema_file="$schema_dir/$version.json"
    [[ -f "$schema_file" ]] || { echo "ERROR: Missing historical Room schema: $schema_file" >&2; exit 5; }
  done
  if [[ -f "$database_source" ]]; then
    version="$(sed -nE 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*([0-9]+),[[:space:]]*$/\1/p' "$database_source" | head -n 1)"
    [[ -n "$version" ]] || { echo "ERROR: Could not determine database version." >&2; exit 5; }
    schema_file="$schema_dir/$version.json"
    [[ -f "$schema_file" ]] || { echo "ERROR: Missing active Room schema: $schema_file" >&2; exit 5; }
    echo "Validated Room schema v$version and historical schemas v1-v3."
  fi
}

verify_source_manifest
verify_source_hygiene
verify_ffmpeg_aar_integrity
verify_ffmpeg_runtime_wiring
verify_download_foreground_wiring
verify_room_schemas

existing_artifacts="$(find_packaged_artifacts)"
if [[ -n "$existing_artifacts" ]]; then
  echo "ERROR: Packaged Android artifacts already exist before validation:" >&2
  printf '%s\n' "$existing_artifacts" >&2
  exit 2
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java is not available. Provision Java $PINNED_JAVA_MAJOR before validation." >&2
  exit 3
fi
java_version_line="$(java -version 2>&1 | head -n 1)"
java_version="$(sed -nE 's/.*version "([^"]+)".*/\1/p' <<<"$java_version_line")"
java_major="${java_version%%.*}"
[[ "$java_major" == "$PINNED_JAVA_MAJOR" ]] || {
  echo "ERROR: Source validation requires Java $PINNED_JAVA_MAJOR; found ${java_version:-unknown}." >&2
  exit 3
}
echo "Validated Java major version: $java_major"

if [[ -x "./gradlew" ]]; then
  GRADLE_CMD=("./gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=("gradle")
else
  echo "ERROR: Gradle is not available. Provision Gradle $PINNED_GRADLE_VERSION before validation." >&2
  exit 3
fi

gradle_version="$("${GRADLE_CMD[@]}" --version | awk '/^Gradle / { print $2; exit }')"
[[ "$gradle_version" == "$PINNED_GRADLE_VERSION" ]] || {
  echo "ERROR: Source validation requires Gradle $PINNED_GRADLE_VERSION; found ${gradle_version:-unknown}." >&2
  exit 3
}
echo "Validated Gradle version: $gradle_version"

android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$android_sdk_root" ]] || {
  echo "ERROR: Android SDK is not configured. Set ANDROID_SDK_ROOT or ANDROID_HOME and install platform android-$PINNED_ANDROID_API_LEVEL." >&2
  exit 3
}
android_platform_jar="$android_sdk_root/platforms/android-$PINNED_ANDROID_API_LEVEL/android.jar"
[[ -f "$android_platform_jar" ]] || {
  echo "ERROR: Source validation requires Android SDK platform android-$PINNED_ANDROID_API_LEVEL; missing $android_platform_jar." >&2
  exit 3
}
echo "Validated Android SDK platform: android-$PINNED_ANDROID_API_LEVEL"

"${GRADLE_CMD[@]}" \
  :app:compileDebugKotlin \
  :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest \
  :app:lintDebug \
  --rerun-tasks \
  --no-build-cache \
  --stacktrace

verify_room_schemas
verify_source_manifest

created_artifacts="$(find_packaged_artifacts)"
if [[ -n "$created_artifacts" ]]; then
  echo "ERROR: Source-only validation produced packaged Android artifacts:" >&2
  printf '%s\n' "$created_artifacts" >&2
  exit 4
fi

echo "PASS: Drive-native source-only validation completed and no APK/AAB was produced."
