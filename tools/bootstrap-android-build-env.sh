#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="9.6.0"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_SHA256="bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01"

ANDROID_CLI_REVISION="15859902"
ANDROID_CLI_URL="https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CLI_REVISION}_latest.zip"
ANDROID_CLI_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
ANDROID_PLATFORM="android-36"
ANDROID_BUILD_TOOLS="36.0.0"

TOOLCHAIN_ROOT="${OWNPLAY_TOOLCHAIN_HOME:-$HOME/.ownplay-android-toolchain}"
CACHE_DIR="$TOOLCHAIN_ROOT/cache"
GRADLE_PARENT="$TOOLCHAIN_ROOT/gradle"
GRADLE_HOME="$GRADLE_PARENT/gradle-${GRADLE_VERSION}"
ANDROID_SDK_ROOT="${OWNPLAY_ANDROID_SDK_ROOT:-$TOOLCHAIN_ROOT/android-sdk}"
CMDLINE_TOOLS_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
ENV_FILE="$TOOLCHAIN_ROOT/ownplay-env.sh"

usage() {
  cat <<USAGE
Usage: $0 --dry-run | --check | --install [--validate]

Modes:
  --dry-run   Show the exact pinned toolchain plan; make no changes and use no network.
  --check     Verify Java 21, Gradle 9.6.0, and Android SDK platform 36.
  --install   Install Gradle and Android SDK components under a user-local toolchain root.
  --validate  After --install, run tools/validate-source-no-apk.sh from the project root.

Environment:
  OWNPLAY_TOOLCHAIN_HOME              Override the default toolchain root.
  OWNPLAY_ANDROID_SDK_ROOT            Override the Android SDK root.
  OWNPLAY_ACCEPT_ANDROID_SDK_LICENSES=1
                                        Explicitly authorize Android SDK license acceptance
                                        before sdkmanager installs packages.
USAGE
}

fail() {
  local message="$1"
  local status="${2:-2}"
  echo "ERROR: $message" >&2
  exit "$status"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required host command is unavailable: $1"
}

java_major() {
  java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/^[[:space:]]*java\.version = / {split($2,v,"."); print v[1]; exit}'
}

verify_java_21() {
  require_cmd java
  local major
  major="$(java_major)"
  [[ "$major" == "21" ]] || fail "Java major 21 is required; found ${major:-unknown}." 3
}

sha256_matches() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]]
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  sha256_matches "$file" "$expected" \
    || fail "SHA-256 mismatch for $file: expected $expected." 5
}

download_verified() {
  local url="$1"
  local expected_sha="$2"
  local destination="$3"
  if [[ -f "$destination" ]]; then
    if sha256_matches "$destination" "$expected_sha"; then
      return 0
    fi
    rm -f "$destination"
  fi
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 900 \
    --output "$destination.part" "$url"
  mv "$destination.part" "$destination"
  verify_sha256 "$destination" "$expected_sha"
}

verify_gradle() {
  [[ -x "$GRADLE_HOME/bin/gradle" ]] || fail "Gradle ${GRADLE_VERSION} is not installed at $GRADLE_HOME." 3
  local version
  version="$("$GRADLE_HOME/bin/gradle" --version | awk '/^Gradle / {print $2; exit}')"
  [[ "$version" == "$GRADLE_VERSION" ]] || fail "Expected Gradle ${GRADLE_VERSION}; found ${version:-unknown}." 3
}

verify_android_sdk() {
  [[ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]] || fail "Android sdkmanager is not installed at $CMDLINE_TOOLS_DIR/bin/sdkmanager." 3
  [[ -f "$ANDROID_SDK_ROOT/platforms/$ANDROID_PLATFORM/android.jar" ]] \
    || fail "Android SDK platform $ANDROID_PLATFORM is not installed under $ANDROID_SDK_ROOT." 3
  [[ -d "$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS" ]] \
    || fail "Android Build Tools $ANDROID_BUILD_TOOLS are not installed under $ANDROID_SDK_ROOT." 3
  [[ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]] \
    || fail "Android platform-tools/adb is not installed under $ANDROID_SDK_ROOT." 3
}

write_env_file() {
  mkdir -p "$TOOLCHAIN_ROOT"
  cat > "$ENV_FILE" <<ENV
export GRADLE_HOME="$GRADLE_HOME"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$GRADLE_HOME/bin:$CMDLINE_TOOLS_DIR/bin:$ANDROID_SDK_ROOT/platform-tools:\$PATH"
ENV
}

print_plan() {
  cat <<PLAN
OwnPlay Android build environment plan
  Host: Linux x86_64
  Java: existing Java major 21 (required)
  Toolchain root: $TOOLCHAIN_ROOT
  Gradle: $GRADLE_VERSION
  Gradle URL: $GRADLE_URL
  Gradle SHA-256: $GRADLE_SHA256
  Android CLI revision: $ANDROID_CLI_REVISION
  Android CLI URL: $ANDROID_CLI_URL
  Android CLI SHA-256: $ANDROID_CLI_SHA256
  Android SDK root: $ANDROID_SDK_ROOT
  SDK packages:
    platform-tools
    platforms;$ANDROID_PLATFORM
    build-tools;$ANDROID_BUILD_TOOLS
  Environment file: $ENV_FILE
PLAN
}

install_gradle() {
  mkdir -p "$CACHE_DIR" "$GRADLE_PARENT"
  local archive="$CACHE_DIR/gradle-${GRADLE_VERSION}-bin.zip"
  download_verified "$GRADLE_URL" "$GRADLE_SHA256" "$archive"
  if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
    rm -rf "$GRADLE_HOME"
    unzip -q "$archive" -d "$GRADLE_PARENT"
  fi
  verify_gradle
}

install_android_cli() {
  mkdir -p "$CACHE_DIR" "$ANDROID_SDK_ROOT/cmdline-tools"
  local archive="$CACHE_DIR/commandlinetools-linux-${ANDROID_CLI_REVISION}_latest.zip"
  download_verified "$ANDROID_CLI_URL" "$ANDROID_CLI_SHA256" "$archive"

  if [[ ! -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]]; then
    local extract_dir
    extract_dir="$(mktemp -d "$TOOLCHAIN_ROOT/android-cli.XXXXXX")"
    trap 'rm -rf "${extract_dir:-}"' RETURN
    unzip -q "$archive" -d "$extract_dir"
    [[ -x "$extract_dir/cmdline-tools/bin/sdkmanager" ]] \
      || fail "Unexpected Android command-line tools archive layout." 5
    rm -rf "$CMDLINE_TOOLS_DIR"
    mkdir -p "$(dirname "$CMDLINE_TOOLS_DIR")"
    mv "$extract_dir/cmdline-tools" "$CMDLINE_TOOLS_DIR"
    rm -rf "$extract_dir"
    trap - RETURN
  fi
}

install_android_packages() {
  if [[ "${OWNPLAY_ACCEPT_ANDROID_SDK_LICENSES:-0}" != "1" ]]; then
    cat >&2 <<LICENSE
ERROR: Android SDK package installation requires explicit license acceptance.
Review the Android SDK license terms, then rerun with:
  OWNPLAY_ACCEPT_ANDROID_SDK_LICENSES=1 bash "$0" --install
No license has been accepted by this script.
LICENSE
    exit 4
  fi

  set +o pipefail
  yes | "$CMDLINE_TOOLS_DIR/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null
  local license_status="${PIPESTATUS[1]}"
  set -o pipefail
  [[ "$license_status" -eq 0 ]] || fail "Android SDK license acceptance failed." 4

  "$CMDLINE_TOOLS_DIR/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;$ANDROID_PLATFORM" \
    "build-tools;$ANDROID_BUILD_TOOLS"
}

mode=""
run_validation=0
while (($#)); do
  case "$1" in
    --dry-run|--check|--install)
      [[ -z "$mode" ]] || fail "Choose exactly one mode: --dry-run, --check, or --install."
      mode="$1"
      ;;
    --validate)
      run_validation=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "Unknown argument: $1"
      ;;
  esac
  shift
done

[[ -n "$mode" ]] || { usage >&2; exit 2; }
if [[ "$run_validation" -eq 1 && "$mode" != "--install" ]]; then
  fail "--validate is supported only with --install."
fi
[[ "$(uname -s)" == "Linux" ]] || fail "This bootstrap currently supports Linux only." 3
case "$(uname -m)" in
  x86_64|amd64) ;;
  *) fail "This bootstrap currently supports Linux x86_64 only; found $(uname -m)." 3 ;;
esac

verify_java_21

case "$mode" in
  --dry-run)
    print_plan
    exit 0
    ;;
  --check)
    verify_gradle
    verify_android_sdk
    write_env_file
    echo "PASS: OwnPlay Android build environment is ready. Source $ENV_FILE before building."
    exit 0
    ;;
  --install)
    require_cmd curl
    require_cmd unzip
    require_cmd sha256sum
    require_cmd awk
    require_cmd mktemp

    # Require explicit license authorization before any Android SDK package mutation.
    if [[ "${OWNPLAY_ACCEPT_ANDROID_SDK_LICENSES:-0}" != "1" ]]; then
      install_android_packages
    fi

    install_gradle
    install_android_cli
    install_android_packages
    verify_gradle
    verify_android_sdk
    write_env_file

    export GRADLE_HOME ANDROID_SDK_ROOT
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    export PATH="$GRADLE_HOME/bin:$CMDLINE_TOOLS_DIR/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

    echo "PASS: OwnPlay Android build environment is ready."
    echo "Environment file: $ENV_FILE"

    if [[ "$run_validation" -eq 1 ]]; then
      script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
      project_root="$(cd "$script_dir/.." && pwd)"
      bash "$project_root/tools/validate-source-no-apk.sh"
    fi
    ;;
esac
