# Reuse the QA v37 signing identity for v38 and later

This is the separate reuse procedure. It never creates or rotates a key.

## Preconditions

- The one time v37 bootstrap has completed successfully and the four encrypted secrets below exist in the protected GitHub qa-signing environment:
  - OWNPLAY_QA_V37_P12_B64
  - OWNPLAY_QA_V37_PASS
  - OWNPLAY_QA_V37_CERT_SHA256
  - OWNPLAY_QA_V37_ALIAS
- The environment allows only the exact signing branch for the requested QA version. Do not broaden it to all branches or to untrusted pull requests.
- Use a standard GitHub hosted Linux runner only after confirming included minutes remain; stop if GitHub presents a charge or asks to activate billing.
- The input APK and its metadata must be fetched through the approved private Drive route and independently checked against the expected Drive ID, SHA-256, package and version before signing.
- The destination is the canonical private QA Builds folder. Upload and raw byte readback must both pass.

## Each v38+ run

1. Confirm all four secret names are present without retrieving or printing their values. If any are absent, stop; do not run the v37 key generation bootstrap again.
2. In a mode 0700 runner temporary directory, decode the existing PKCS12 and password to mode 0600 files. Read the expected certificate fingerprint and alias from the same protected environment.
3. Confirm the certificate exported from the PKCS12 has the exact stored SHA-256 fingerprint. Abort on any mismatch.
4. Sign the independently verified version APK with v1 disabled, v2 and v3 enabled, and v4 disabled. Verify both signature schemes, certificate, package app.ownplay.mobile, requested versionCode, versionName and final SHA-256.
5. Upload only the signed APK and nonsecret verification report to the canonical private Drive folder, then verify metadata and raw bytes by readback.
6. Delete runner temporary files when the job exits. Never run key generation, call gh secret set, replace the four v37 secrets, or publish the keystore, password or signed APK as a public artifact.

The v37 input bridge and v37 signing script are pinned to version 37. A v38+ workflow must provide the corresponding per-version input ID/hash and manifest expectations before it may call a reusable signer; this document does not claim that a v38+ signing workflow has been operationally tested.
