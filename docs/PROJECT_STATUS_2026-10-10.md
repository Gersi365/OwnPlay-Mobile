# OwnPlay Mobile — development status (2026-10-10)

> **Non-authoritative, public-safe progress snapshot.** This document is a GitHub handoff aid, not the canonical source baseline, live continuation pointer, release approval, or proof of device QA. For execution, verify the current OwnPlay Mobile Drive workflow, continuation pointer, source manifests, and current working tree. This snapshot can become stale.

## Repository and source boundaries

- The public GitHub repository remains an **execution/CI mirror**; it is not a full code backup.
- The latest identified **canonical Drive source** is the **v46 QA candidate**. No v48 source promotion was verified.
- The active Ubuntu **local-only** development checkout was inspected on 2026-10-10. Its branch is `candidate/v47-live-remediation-20261008T015532Z`, based on `d66048648bcb315548395c87386e4c15a574db14`.
- At the recorded checkpoint, that checkout had **96 uncommitted Git status entries** (modified and new files). These changes are **not represented by the GitHub default branch**; do not treat a successful older CI run as validation of them.
- Nothing in this update copies, promotes, signs, or publishes the local source.

## Latest verified LOCAL validation checkpoint

Recorded on **2026-10-10** against the actual local, uncommitted development source:

- **598/598 JVM unit tests PASS** across **124 suites**; 0 failures, 0 errors, 0 skipped.
- Gradle 9.6.0 offline: `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, `:app:lintDebug`, and `:app:assembleRelease` reported **BUILD SUCCESSFUL**.
- An **unsigned** release APK was produced; this is **not** a signed QA/release build.
- The latest local work includes a GA-012 Stop & Save stale-identity guard, plus test/implementation work around recording-versus-download priority, DVR publication safety, download retry state, and Android SAF scan-grant consistency.
- These are **local code/JVM/build results only**. No GitHub CI run against the complete dirty candidate, physical-device acceptance, real IPTV/provider end-to-end verification, private signing, or release is evidenced by this checkpoint.

## Remaining acceptance and continuation work

1. **Reconcile and preserve the local candidate:** inspect the current full diff and file ownership, including untracked files. Keep pre-existing changes; do not reset or replace the working tree. Compare it with the canonical Drive baseline and approved requirements before any promotion or mirror sync.
2. **Complete product behavior:** verify recording/playback conflict choice, recording/download priority, DVR finalization and recovery, and Downloads/SAF behavior against approved acceptance contracts. A 2026-10-09 audit specifically found manual **Record now** discoverability in both Live Preview and Fullview still open; the approved **whole archived program** Catch-up Record behavior also requires implementation/acceptance verification.
3. **Run runtime QA:** test actual Android lifecycle, WorkManager, SAF permissions, process recovery, Live DVR and Stop & Save, and supported real-provider scenarios on authorized devices. JVM tests and Android-test compilation are not substitutes for runtime acceptance.
4. **Complete CI and release gates separately:** once an exact allowed source baseline is verified and authorized for GitHub mirroring, run CI against that exact tree, reconcile any failures, and obtain independent signing/physical QA authorization before release.
5. **Revalidate on resume:** fetch the current canonical Drive workflow and single live continuation pointer; inspect current local/Drive/GitHub identities and newer test receipts. This dated GitHub document is for orientation only, never a second source of truth.

## Publication safety

Do **not** publish provider credentials, channel/user data, private signing keys or keystores, authenticated material, signed QA artifacts, private Drive recovery content, or other secrets. Source mirroring remains subject to canonical-baseline provenance and explicit release/QA gates.

**GitHub documentation update only:** this snapshot does **not** imply any modification to Drive or the PC, any code push, or any project/release acceptance.
