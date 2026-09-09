# OwnPlay Mobile — Engineering Workflow

## Document Role

Stable engineering procedure for the OwnPlay Mobile total rebuild.

Current repository state, exact HEAD and CI evidence must always be read live from GitHub.

---

# 1. Authority Order

When information conflicts, use:

1. latest explicit user decision;
2. `OwnPlay_MOBILE_SOURCE.md`;
3. verified current GitHub repository state;
4. focused current QA/audit evidence;
5. historical notes.

Do not silently reconcile conflicts.

---

# 2. Repository Identity

Primary repository:

`Gersi365/OwnPlay-Mobile`

Application ID:

`app.ownplay.mobile`

The total rebuild stays in this repository unless the user explicitly changes that decision.

---

# 3. Rebuild Operating Model

The rebuild is a forward-only source replacement, not a history rewrite.

Prefer:

- a dedicated Draft rebuild branch/PR;
- deletion of legacy active application source on that branch;
- clean new source rooted at `app.ownplay.mobile`;
- retained repository/build infrastructure only where useful;
- new tests written against the rebuilt contracts;
- no legacy implementation copied merely to accelerate progress.

Legacy Git history remains the recovery/reference mechanism.

Do not force-push, reset, rebase or rewrite history.

---

# 4. Before Every Mutation

1. inspect GitHub;
2. identify the authoritative Mobile source ref;
3. verify exact HEAD;
4. inspect the active execution/build path;
5. read `OwnPlay_MOBILE_SOURCE.md`;
6. verify requested scope and approval boundaries;
7. distinguish retained infrastructure from legacy application code.

---

# 5. Rebuild Stages

Use auditable stages rather than one unreviewable mega-commit.

Recommended order:

1. **Foundation reset** — remove old active app source and create clean application/design/navigation skeleton;
2. **Sources + persistence** — source models, Xtream/M3U ingestion, Room/DataStore and legacy migration/import;
3. **Live** — browse, Preview, EPG, activation reducer and fullscreen handoff;
4. **Library** — Continue Watching, Movies, Series, details and progress;
5. **Playback ownership** — Media3 controller, one player/one target, VOD fullscreen and PiP;
6. **Downloads** — WorkManager transfer model and Offline playback;
7. **Settings / backup** — durable preferences, source management, backup/restore;
8. **Hardening** — performance, error states, migration tests, source regression tests;
9. **Physical visual/playback QA** — only after explicit QA APK authorization.

A stage may be subdivided when needed to keep diffs reviewable.

---

# 6. Architecture Rules

Use clean feature boundaries rooted at `app.ownplay.mobile`.

Critical state transitions should be explicit and testable, especially:

- Live activation;
- Preview/fullscreen/PiP presentation ownership;
- playback start/resume;
- download actions;
- migration/import;
- source refresh reconciliation.

Avoid a single global runtime object that accumulates unrelated feature responsibilities.

Shared code should represent stable domain contracts, not convenience coupling.

---

# 7. Visual Implementation Rules

Use the approved OwnPlay mockups retained in the project as visual source material.

Do not infer acceptance from CI.

For each primary screen:

1. implement the structural hierarchy first;
2. compare against the approved reference;
3. preserve stable geometry for active/selected states;
4. add focused screenshot/structural contracts where useful;
5. defer final visual PASS to physical-device evidence.

The rebuilt interface uses blue OwnPlay accents and must not fall back to the previous legacy purple presentation.

---

# 8. Persistence / Migration Safety

The user authorized rebuilding application source, not deleting user data.

Therefore:

- do not ship a destructive database reset;
- retain historical Room schemas as migration evidence until the new compatibility path is proven;
- design a deterministic legacy import/migration layer;
- test migrations with representative old-state fixtures;
- do not migrate credentials casually;
- do not delete old persisted state until a successful migration/compatibility decision explicitly allows it.

Destructive database migration or user-data deletion still requires separate explicit approval.

---

# 9. APK Rule

Routine rebuild work is **source-only / NO APK**.

QA APK generation requires explicit authorization.

When authorized:

- preserve `app.ownplay.mobile`;
- preserve established signing identity unless explicitly changed;
- preserve monotonic versioning;
- build from the exact intended source candidate;
- report source SHA, package, version, signer SHA-256 and APK SHA-256;
- never equate build success with physical QA success.

---

# 10. Validation

After each source stage:

- run focused unit/contract tests;
- run Kotlin compilation/lint as appropriate;
- run Mobile no-APK validation;
- verify zero APK;
- preserve/verify Room schema evidence where applicable;
- verify validation belongs to the exact final HEAD.

If HEAD changes, previous validation is historical only.

---

# 11. Physical QA Boundary

Physical-device testing is required for:

- visual acceptance against approved references;
- actual video rendering;
- Preview ↔ fullscreen continuity;
- Picture-in-Picture;
- background/foreground;
- rotation;
- update/migration behavior;
- real resume position;
- real provider/network behavior;
- downloads;
- touch ergonomics and perceived performance.

Critical playback/surface failures block promotion.

---

# 12. Restricted Actions

Explicit approval is required before:

- merge;
- ready-for-review transition;
- release/publication/deployment;
- QA APK generation;
- signing changes;
- force-push/reset/rebase/history rewrite;
- destructive database migration;
- user-data deletion;
- authentication/account cutover;
- breaking backup-format changes;
- changes outside the authorized Mobile rebuild scope.

The user's current rebuild decision authorizes the Mobile application source replacement/architecture rebuild itself, but does not implicitly authorize the other restricted actions above.

---

# 13. Reporting

After each meaningful stage report:

- what was inspected;
- what legacy source was removed or isolated;
- what new source was created;
- exact final HEAD;
- validation result;
- explicit APK status;
- remaining physical-QA/migration boundary;
- one best next step.

At the end of a meaningful stage ask exactly:

**“A të vazhdoj me këtë?”**
