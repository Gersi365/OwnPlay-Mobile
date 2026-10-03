#!/usr/bin/env bash
# OwnPlay QA v37 one-shot bootstrap. NONSECRET SOURCE; NOT A WORKFLOW.
# Execute only in a separately verified zero-extra-cost GitHub Actions job
# with environment: qa-signing; exact branch; protected secret-write and Drive
# credentials provisioned by authorized owner through a protected UI.
set -euo pipefail
set +x
umask 077
fail() { printf 'OWNPLAY_QA_V37_ROUTE_BLOCKED: %s\n' "$1" >&2; exit 2; }
repo="Gersi365/OwnPlay-Mobile"
branch="qa/v37-clean-install-signing"
work=""
cleanup_secret_writer=false
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$cleanup_secret_writer" == "true" && -n "${GH_TOKEN:-}" ]]; then
    gh secret delete OWNPLAY_QA_SECRET_ADMIN_TOKEN --env qa-signing --repo "$repo" >/dev/null 2>&1 || true
    unset GH_TOKEN
  fi
  if [[ -n "$work" && -d "$work" ]]; then
    rm -rf -- "$work"
  fi
  exit "$status"
}
trap cleanup EXIT
[[ "$(printenv GITHUB_ACTIONS || true)" == "true" ]] || fail "GitHub Actions required"
[[ "$(printenv GITHUB_REPOSITORY || true)" == "$repo" ]] || fail "repository identity mismatch"
[[ "$(printenv GITHUB_REF_NAME || true)" == "$branch" ]] || fail "branch identity mismatch"
[[ "$(printenv GITHUB_EVENT_NAME || true)" == "workflow_dispatch" ]] || fail "protected manual dispatch required"
[[ "$(printenv OWNPLAY_QA_ENVIRONMENT || true)" == "qa-signing" ]] || fail "restricted environment identity missing"
[[ -n "$(printenv OWNPLAY_QA_SECRET_ADMIN_TOKEN || true)" ]] || fail "protected environment-secret writer not connected"
[[ -n "$(printenv OWNPLAY_DRIVE_ACCESS_TOKEN || true)" ]] || fail "protected canonical Drive credential not connected"
runner_temp="$(printenv RUNNER_TEMP || true)"
android_home="$(printenv ANDROID_HOME || true)"
[[ -n "$runner_temp" && -d "$runner_temp" ]] || fail "runner temporary directory missing"
for tool in gh python3 keytool openssl base64 sha256sum grep stat unzip; do
  command -v "$tool" >/dev/null 2>&1 || fail "required signing tool unavailable: $tool"
done
build_tools="$android_home/build-tools/36.0.0"
[[ -x "$build_tools/apksigner" && -r "$build_tools/lib/apksigner.jar" ]] ||
  fail "pinned Android Build Tools 36.0.0 apksigner missing"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
[[ -s "$script_dir/private-drive-bridge.py" && -s "$script_dir/sign-verified-v37.sh" ]] ||
  fail "nonsecret signing components missing"
[[ "$(git hash-object -- "$script_dir/private-drive-bridge.py")" == "bf51ae6febc4e1ac4eba8072563a15a87645b73d" ]] ||
  fail "verified canonical private Drive bridge version mismatch"
[[ "$(git hash-object -- "$script_dir/sign-verified-v37.sh")" == "cb2db8ec3095f594906127b308f9996e166ea4d3" ]] ||
  fail "verified canonical QA signer stage version mismatch"

# GitHub's ordinary GITHUB_TOKEN is NOT assumed to administer environment
# secrets. Owner-authorized secret-write credential stays in protected env.
export GH_TOKEN="$(printenv OWNPLAY_QA_SECRET_ADMIN_TOKEN)"
cleanup_secret_writer=true
unset OWNPLAY_QA_SECRET_ADMIN_TOKEN
gh api "repos/$repo/environments/qa-signing/secrets/public-key" >/dev/null 2>&1 ||
  fail "encrypted environment-secret API access unavailable"
existing="$(gh api "repos/$repo/environments/qa-signing/secrets" --jq '.secrets[].name' 2>/dev/null)" ||
  fail "environment-secret inventory unavailable"
for key in OWNPLAY_QA_V37_P12_B64 OWNPLAY_QA_V37_PASS OWNPLAY_QA_V37_CERT_SHA256 OWNPLAY_QA_V37_ALIAS; do
  ! grep -Fxq "$key" <<< "$existing" || fail "new QA signer secret already exists; never overwrite"
done
python3 -I "$script_dir/private-drive-bridge.py" probe >/dev/null ||
  fail "authenticated canonical private Drive ingress/egress folder probe failed"

work="$(mktemp -d -- "$runner_temp/ownplay-v37-secure.XXXXXXXX")"
chmod 700 -- "$work"
# Prove one real private upload, exact raw readback, and deletion in the
# dedicated output folder BEFORE generating or persisting any QA signing key.
python3 -I "$script_dir/private-drive-bridge.py" preflight "$work" >/dev/null ||
  fail "private Drive write/readback/cleanup preflight failed before key generation"
python3 -I "$script_dir/private-drive-bridge.py" download "$work/unsigned.apk" >/dev/null ||
  fail "canonical private v37 APK download/hash verification failed"
[[ "$(sha256sum "$work/unsigned.apk" | cut -d ' ' -f1)" == "7faf4f50d189e55cc6591b7ef95f94bdb9f0ad5fb79d194e5438e152fcdc69cb" ]] ||
  fail "presign APK SHA-256 mismatch"

# Generate fresh QA-only PKCS12 in 0700 protected temporary directory.
# This directory is removed on exit; no key is ever published as an artifact.
openssl rand -hex 32 > "$work/qa-v37.pass"
chmod 600 -- "$work/qa-v37.pass"
alias="ownplay_qa_v37"
keytool -genkeypair -noprompt \
  -alias "$alias" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
  -validity 10000 -dname "CN=OwnPlay QA v37, O=OwnPlay, C=US" \
  -storetype PKCS12 -keystore "$work/qa-v37.p12" \
  -storepass:file "$work/qa-v37.pass" -keypass:file "$work/qa-v37.pass" \
  >/dev/null 2>&1 || fail "private QA signer generation failed"
chmod 600 -- "$work/qa-v37.p12"
cert_sha="$(
  keytool -exportcert -rfc -alias "$alias" -storetype PKCS12 \
    -keystore "$work/qa-v37.p12" -storepass:file "$work/qa-v37.pass" 2>/dev/null |
  openssl x509 -inform PEM -outform DER 2>/dev/null |
  sha256sum | cut -d ' ' -f1
)" || fail "new QA certificate extraction failed"
[[ "$cert_sha" =~ ^[0-9a-f]{64}$ ]] || fail "new QA certificate fingerprint missing"

# Exact presign APK -> new key -> v2/v3 verification -> private report.
export OWNPLAY_QA_KEY_ALIAS="$alias"
bash "$script_dir/sign-verified-v37.sh" \
  "$work/unsigned.apk" "$build_tools" \
  "$work/qa-v37.p12" "$work/qa-v37.pass" "$work/qa-v37.pass" \
  "$cert_sha" "$work/signed.apk" > "$work/nonsecret-signature-report.txt" ||
  fail "new QA signer did not pass APK signature verification"
chmod 600 -- "$work/nonsecret-signature-report.txt"
grep -Fxq 'OWNPLAY_QA_V37_SIGNED_VERIFIED' "$work/nonsecret-signature-report.txt" ||
  fail "signed APK verification evidence missing"

# Restrict every key/password value to encrypted qa-signing environment secrets.
# gh secret set consumes a PRIVATE dotenv; it is never committed, uploaded,
# printed or emitted to normal Actions artifacts/logs.
base64 -w0 "$work/qa-v37.p12" > "$work/qa-v37.p12.b64"
[[ "$(wc -c < "$work/qa-v37.p12.b64")" -lt 45000 ]] ||
  fail "encrypted environment secret payload too large"
{
  printf 'OWNPLAY_QA_V37_P12_B64=%s\n' "$(cat "$work/qa-v37.p12.b64")"
  printf 'OWNPLAY_QA_V37_PASS=%s\n' "$(cat "$work/qa-v37.pass")"
  printf 'OWNPLAY_QA_V37_CERT_SHA256=%s\n' "$cert_sha"
  printf 'OWNPLAY_QA_V37_ALIAS=%s\n' "$alias"
} > "$work/private-github-secrets.env"
chmod 600 -- "$work/private-github-secrets.env"
gh secret set --env qa-signing --repo "$repo" --env-file "$work/private-github-secrets.env" \
  >/dev/null 2>&1 || fail "restricted encrypted secret persistence failed"
stored="$(gh api "repos/$repo/environments/qa-signing/secrets" --jq '.secrets[].name' 2>/dev/null)" ||
  fail "post-persistence secret inventory unavailable"
for key in OWNPLAY_QA_V37_P12_B64 OWNPLAY_QA_V37_PASS OWNPLAY_QA_V37_CERT_SHA256 OWNPLAY_QA_V37_ALIAS; do
  grep -Fxq "$key" <<< "$stored" || fail "expected restricted encrypted secret missing"
done
# Remove the one-time secret-writer credential from the environment after the
# QA signer secrets are persisted. Read back names only; never reveal values.
gh secret delete OWNPLAY_QA_SECRET_ADMIN_TOKEN --env qa-signing --repo "$repo" \
  >/dev/null 2>&1 || fail "one-time environment-secret writer cleanup failed"
stored_after_cleanup="$(gh api "repos/$repo/environments/qa-signing/secrets" --jq '.secrets[].name' 2>/dev/null)" ||
  fail "post-cleanup environment-secret inventory unavailable"
grep -Fxq "OWNPLAY_QA_V37_P12_B64" <<< "$stored_after_cleanup" ||
  fail "QA signing key was missing after cleanup"
! grep -Fxq "OWNPLAY_QA_SECRET_ADMIN_TOKEN" <<< "$stored_after_cleanup" ||
  fail "one-time environment-secret writer still exists after cleanup"
cleanup_secret_writer=false
unset GH_TOKEN
# The protected job keeps the QA signing key only in encrypted environment
# secrets; its private runner copy is removed on exit.

python3 -I "$script_dir/private-drive-bridge.py" upload \
  "$work/signed.apk" "$work/nonsecret-signature-report.txt" \
  > "$work/private-drive-egress.txt" 2> "$work/private-drive-egress.error" ||
  fail "signed APK and nonsecret evidence private canonical Drive return failed"
grep -Fxq 'OWNPLAY_PRIVATE_SIGNED_APK_READBACK_PASS' "$work/private-drive-egress.txt" ||
  fail "private signed APK raw Drive readback marker missing"
grep -Fxq 'OWNPLAY_PRIVATE_EVIDENCE_READBACK_PASS' "$work/private-drive-egress.txt" ||
  fail "private signature evidence raw Drive readback marker missing"
printf 'OWNPLAY_QA_V37_PROTECTED_ROUTE_COMPLETE\n'
printf 'NEW_CERT_SHA256=%s\n' "$cert_sha"
printf 'PRIVATE_KEY_STORAGE=github_environment_qa-signing_encrypted_secrets\n'
