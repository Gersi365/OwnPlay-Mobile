#!/usr/bin/env bash
# OwnPlay QA v37: NONSECRET orchestration of canonical Drive ingress,
# protected QA signer, cryptographic verification, and private Drive egress.
# Does NOT provision credentials, generate keys, dispatch Actions, grant
# permissions, enable billing, or publish any binary.
set -euo pipefail
set +x
umask 077

blocked() { printf 'OWNPLAY_QA_V37_PATH_BLOCKED: %s\n' "$1" >&2; exit 2; }
[[ $# -eq 0 ]] || blocked "this orchestrator takes no command-line arguments"
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || blocked "GitHub Actions is required"
[[ "${GITHUB_REPOSITORY:-}" == "Gersi365/OwnPlay-Mobile" ]] || blocked "unexpected execution repository"
[[ "${GITHUB_REF:-}" == "refs/heads/qa/v37-clean-install-signing" ]] || blocked "wrong QA signing branch"
[[ "${GITHUB_EVENT_NAME:-}" == "workflow_dispatch" ]] || blocked "only explicitly dispatched QA signing is eligible"
[[ "${OWNPLAY_QA_ENVIRONMENT:-}" == "qa-signing" ]] || blocked "restricted qa-signing job context missing"
[[ -n "${OWNPLAY_DRIVE_ACCESS_TOKEN:-}" ]] || blocked "protected Drive access token missing"

# These variables name protected private files/dirs, NEVER secret contents.
for key in OWNPLAY_ANDROID_BUILD_TOOLS OWNPLAY_QA_KEYSTORE_FILE \
           OWNPLAY_QA_STORE_PASSWORD_FILE OWNPLAY_QA_KEY_PASSWORD_FILE \
           OWNPLAY_QA_EXPECTED_CERT_SHA256 OWNPLAY_QA_KEY_ALIAS RUNNER_TEMP; do
  [[ -n "${!key:-}" ]] || blocked "one or more required protected signing inputs missing"
done
[[ "$OWNPLAY_QA_EXPECTED_CERT_SHA256" =~ ^[[:xdigit:]]{64}$ ]] || blocked "new signer certificate identity missing"

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
bridge="$here/private-drive-bridge.py"
signer="$here/sign-verified-v37.sh"
[[ -f "$bridge" && -f "$signer" && ! -L "$bridge" && ! -L "$signer" ]] || blocked "verified stage files unavailable"
[[ -d "$RUNNER_TEMP" && ! -L "$RUNNER_TEMP" ]] || blocked "private runner temporary root unavailable"
work="$(mktemp -d -- "$RUNNER_TEMP/ownplay-v37-private.XXXXXXXX")"
chmod 700 -- "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
input="$work/unsigned-v37.apk"
output="$work/signed-v37.apk"
report="$work/signature-report.txt"

# Probe canonical Drive folders + write capability before touching bytes.
# Token never enters argv, repository, workflow artifacts or public logs.
python3 -I "$bridge" probe > "$work/drive-probe.txt" 2> "$work/drive-probe.error" \
  || blocked "authenticated canonical Drive read/write preflight failed"
grep -Fxq 'OWNPLAY_PRIVATE_DRIVE_AUTH_AND_FOLDER_PREFLIGHT_PASS' "$work/drive-probe.txt" \
  || blocked "private Drive preflight acknowledgement missing"

python3 -I "$bridge" download "$input" > "$work/ingress.txt" 2> "$work/ingress.error" \
  || blocked "private Drive APK ingress failed"
grep -Fxq 'OWNPLAY_PRIVATE_INGRESS_PASS' "$work/ingress.txt" \
  || blocked "canonical APK ingress verification missing"

# Drive authentication is excluded from the signer; password VALUES are
# passed only via protected 0600 files previously provisioned in qa-signing.
env -u OWNPLAY_DRIVE_ACCESS_TOKEN bash "$signer" \
  "$input" "$OWNPLAY_ANDROID_BUILD_TOOLS" "$OWNPLAY_QA_KEYSTORE_FILE" \
  "$OWNPLAY_QA_STORE_PASSWORD_FILE" "$OWNPLAY_QA_KEY_PASSWORD_FILE" \
  "$OWNPLAY_QA_EXPECTED_CERT_SHA256" "$output" > "$report" 2> "$work/signing.error" \
  || blocked "protected QA signing or signature verification failed"
chmod 600 -- "$report"
grep -Fxq 'OWNPLAY_QA_V37_SIGNED_VERIFIED' "$report" \
  || blocked "cryptographic verification marker missing"
grep -Eq '^NEW_CERT_SHA256=[[:xdigit:]]{64}$' "$report" \
  || blocked "verified new certificate evidence missing"
grep -Eq '^SIGNED_APK_SHA256=[[:xdigit:]]{64}$' "$report" \
  || blocked "signed APK digest missing"

# Fixed canonical Drive output folder, byte-for-byte remote readback.
python3 -I "$bridge" upload "$output" "$report" \
  > "$work/egress.txt" 2> "$work/egress.error" \
  || blocked "private signed APK delivery or Drive raw readback failed"
grep -Fxq 'OWNPLAY_PRIVATE_SIGNED_APK_READBACK_PASS' "$work/egress.txt" \
  || blocked "signed APK Drive readback marker missing"
grep -Fxq 'OWNPLAY_PRIVATE_EVIDENCE_READBACK_PASS' "$work/egress.txt" \
  || blocked "signature evidence Drive readback marker missing"

# Never print tokens, private keystores/passwords, Drive IDs, APK bytes, logs.
printf 'OWNPLAY_QA_V37_PRIVATE_SIGNING_PIPELINE_PASS\n'
