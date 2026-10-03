#!/usr/bin/env bash
# OwnPlay QA v37: fail-closed signing stage. NONSECRET executable logic only.
# No key generation, credential acquisition, network calls or artifact uploads.
set -euo pipefail
set +x
umask 077

fail() { printf 'OWNPLAY_QA_V37_SIGNING_BLOCKED: %s\n' "$1" >&2; exit 2; }
if [[ $# -ne 7 ]]; then
  fail "usage: sign-verified-v37.sh <private-input-apk> <build-tools-36.0.0-dir> <protected-p12> <protected-store-pass-file> <protected-key-pass-file> <new-cert-sha256> <private-output-apk>"
fi
[[ "$(printenv GITHUB_ACTIONS || true)" == "true" ]] || fail "requires a verified GitHub Actions protected runner"
[[ "$(printenv OWNPLAY_QA_ENVIRONMENT || true)" == "qa-signing" ]] || fail "requires restricted qa-signing environment"
[[ -n "$(printenv RUNNER_TEMP || true)" ]] || fail "missing runner temporary directory"
[[ -n "$(printenv OWNPLAY_QA_KEY_ALIAS || true)" ]] || fail "missing protected key alias"
input="$1"; build_tools="$2"; keystore="$3"; store_pass="$4"; key_pass="$5"; expected_cert="$6"; output="$7"
expected_input="7faf4f50d189e55cc6591b7ef95f94bdb9f0ad5fb79d194e5438e152fcdc69cb"
[[ "$expected_cert" =~ ^[[:xdigit:]]{64}$ ]] || fail "expected new certificate SHA-256 is required"
expected_cert="$(printf '%s' "$expected_cert" | tr '[:upper:]' '[:lower:]')"
verify_package_version() {
  local artifact="$1" badging
  badging="$("$build_tools/aapt" dump badging "$artifact" 2>/dev/null)" || fail "APK manifest inspection failed"
  grep -Fq "package: name='app.ownplay.mobile' versionCode='37' versionName='0.1.0-dev'" <<< "$badging" || fail "APK package/version identity mismatch"
}
[[ -f "$input" && ! -L "$input" ]] || fail "private APK ingress is not available"
[[ -f "$keystore" && ! -L "$keystore" ]] || fail "protected PKCS12 is not available"
[[ -f "$store_pass" && ! -L "$store_pass" && -s "$store_pass" ]] || fail "protected store password file is not available"
[[ -f "$key_pass" && ! -L "$key_pass" && -s "$key_pass" ]] || fail "protected key password file is not available"
[[ -x "$build_tools/apksigner" && -r "$build_tools/lib/apksigner.jar" && -x "$build_tools/aapt" ]] || fail "pinned Android Build Tools 36.0.0 apksigner/aapt are missing"
[[ "$input" != "$output" && "$keystore" != "$output" && ! -e "$output" ]] || fail "output must be new and separate"
output_dir="$(dirname -- "$output")"
[[ -d "$output_dir" && ! -L "$output_dir" ]] || fail "private output directory is missing"
private_permissions() {
  local mode
  mode="$(stat -c '%a' -- "$1")" || fail "cannot inspect protected path"
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] || fail "invalid protected permissions"
  (( (8#$mode & 8#077) == 0 )) || fail "protected input/output has group or public permissions"
  [[ "$(stat -c '%u' -- "$1")" == "$(id -u)" ]] || fail "protected path is not owned by runner"
}
for item in "$input" "$(dirname -- "$input")" "$keystore" "$store_pass" "$key_pass" "$output_dir"; do
  private_permissions "$item"
done
actual_input="$(sha256sum -- "$input" | cut -d ' ' -f 1)"
[[ "$actual_input" == "$expected_input" ]] || fail "input differs from independently verified canonical QA v37 APK"
verify_package_version "$input"
unzip -tq -- "$input" >/dev/null || fail "APK ZIP integrity failed"
runner_temp="$(printenv RUNNER_TEMP)"
[[ -d "$runner_temp" && ! -L "$runner_temp" ]] || fail "invalid temporary directory"
work="$(mktemp -d -- "$runner_temp/ownplay-v37-sign.XXXXXXXX")"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
"$build_tools/apksigner" sign \
  --ks "$keystore" --ks-type PKCS12 --ks-key-alias "$OWNPLAY_QA_KEY_ALIAS" \
  --ks-pass "file:$store_pass" --key-pass "file:$key_pass" \
  --v1-signing-enabled false --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out "$work/signed.apk" "$input"
"$build_tools/apksigner" verify --verbose --print-certs "$work/signed.apk" > "$work/verify.txt" \
  || fail "apksigner verification failed"
grep -Fq 'Verified using v1 scheme (JAR signing): false' "$work/verify.txt" || fail "unexpected v1 signature"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' "$work/verify.txt" || fail "missing v2 signature"
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' "$work/verify.txt" || fail "missing v3 signature"
verify_package_version "$work/signed.apk"
private_permissions "$work/signed.apk"
! grep -Eq '^Signer #2 ' "$work/verify.txt" || fail "unexpected additional signer"
actual_cert="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}' "$work/verify.txt" | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
[[ "$actual_cert" == "$expected_cert" ]] || fail "new signer certificate does not match expected identity"
private_permissions "$work"
mv -- "$work/signed.apk" "$output"
chmod 600 -- "$output"
printf 'OWNPLAY_QA_V37_SIGNED_VERIFIED\n'
printf 'PACKAGE=app.ownplay.mobile\n'
printf 'VERSION_CODE=37\n'
printf 'VERSION_NAME=0.1.0-dev\n'
printf 'SIGNATURE_SCHEMES=v2,v3\n'
printf 'INSTALL_MODE=CLEAN_INSTALL_ONLY\n'
printf 'INPUT_SHA256=%s\n' "$actual_input"
printf 'NEW_CERT_SHA256=%s\n' "$actual_cert"
printf 'SIGNED_APK_SHA256=%s\n' "$(sha256sum -- "$output" | cut -d ' ' -f 1)"
# Caller must privately transfer signed APK to canonical Drive and verify raw readback.
# Never upload it as a public repository blob, release or public Actions artifact.
