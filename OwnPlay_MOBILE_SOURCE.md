# OwnPlay Mobile — Product & Source Authority

## Document Role

This is the durable product and implementation authority for the **OwnPlay Mobile total rebuild**.

The rebuild supersedes the previous Mobile implementation. Existing historical branches, QA APKs, screenshots of the legacy UI, and prior implementation details are evidence only; they are not implementation authority for the rebuilt application.

Do not store current HEAD, CI run IDs, version checkpoints, or temporary defects in this file. Verify those live from GitHub.

---

# 1. Product Identity

OwnPlay Mobile is an Android media player and playlist organizer for phones and tablets.

Application ID:

`app.ownplay.mobile`

Primary repository:

`Gersi365/OwnPlay-Mobile`

The application is rebuilt from zero in the existing Mobile repository while preserving the application ID and established signing identity for future update compatibility.

OwnPlay does not provide or sell media. Users add their own legitimate sources and credentials.

Supported product domains:

- Xtream-compatible sources;
- M3U / M3U8 playlists;
- Live channels;
- Movies / VOD;
- Series and episodes;
- EPG where available;
- favorites and personalization;
- Continue Watching and playback progress;
- offline downloads;
- Picture-in-Picture;
- backup/restore of supported personalization.

---

# 2. Rebuild Decision

The previous OwnPlay Mobile implementation is not to be incrementally restyled or refactored into the new product.

The active Mobile application source is to be recreated from zero with a new package structure, new presentation layer, new feature boundaries, new state holders, and new tests.

Repository/build infrastructure may be retained where it remains useful and does not constrain the new application design.

Legacy source may remain only as Git history or isolated migration reference. It must not remain in the active rebuilt execution path.

No destructive user-data deletion is authorized by this rebuild decision. Before a QA APK is promoted as an update candidate, the rebuilt application must have an explicit compatibility/migration path for supported existing personalization and persisted state.

---

# 3. Technology Baseline

Use:

- Kotlin;
- Jetpack Compose;
- Android Media3 / ExoPlayer;
- Room;
- DataStore;
- WorkManager;
- Coroutines / Flow;
- repository-driven data access.

The codebase should use a clean Mobile-only package hierarchy rooted at:

`app.ownplay.mobile`

Preferred feature boundaries:

- `app` — root composition and navigation;
- `design` — colors, typography, spacing, shared visual primitives;
- `live` — Live browse, Preview, EPG and fullscreen Live presentation;
- `library` — Continue Watching, Movies, Series and Offline browsing;
- `playback` — one player, presentation/session ownership and surface transfer;
- `sources` — provider configuration and source synchronization;
- `downloads` — managed offline transfer state and actions;
- `data` — Room/DataStore/repositories and migration/import;
- `settings` — user-facing settings and management entry points.

Avoid a monolithic activity/runtime that owns unrelated feature logic.

---

# 4. Visual Source of Truth

The rebuilt UI is **OwnPlay-branded, dark, cinematic and Stremio-inspired in browsing density and media-first hierarchy**, while remaining an original OwnPlay design.

The approved visual reference set is the generated OwnPlay mockup family retained in the OwnPlay Mobile project, including:

- Live browsing / Preview reference;
- Live fullscreen reference;
- Library reference;
- Settings reference.

The additional Live variant may inform spacing/detail treatment but must not silently supersede the primary Live reference.

When code and visual interpretation conflict, prefer the approved primary reference image plus the behavioral rules in this document.

## Visual language

- near-black / blue-black background;
- deep dark surfaces;
- bright electric blue primary accent;
- white primary text;
- cool gray secondary text;
- large, confident typography;
- content artwork is visually dominant;
- subtle rounded surfaces rather than pill-heavy chrome;
- low visual noise;
- fixed geometry for selected/active states;
- active state is communicated primarily through color and emphasis;
- no legacy purple OwnPlay presentation;
- no generic old IPTV list aesthetic.

The OwnPlay wordmark may use white `Own` plus blue `Play` and the product line `Your Channels. Your Way.` where the reference screen requires it.

---

# 5. Primary Navigation

Primary navigation is exactly:

- **Live**
- **Library**
- **Settings**

Downloads remains a Library/Settings/contextual capability and is not a fourth primary destination.

The bottom navigation should visually follow the approved references: dark integrated navigation, stable geometry, blue active icon/text and a fixed blue indicator treatment.

---

# 6. Live Experience

Live is media-first.

The browsing surface should contain:

- OwnPlay/header actions as defined by the approved Live reference;
- active Preview region when a channel is selected;
- Now Playing / Next EPG information associated with Preview;
- channel browsing below Preview;
- search and source/group filtering without overwhelming the media hierarchy.

## Activation contract

- activating a different channel opens or updates **Preview**;
- activating the same currently previewed channel again opens **fullscreen Live**;
- Back from fullscreen returns to Preview;
- Back from Preview closes Preview before leaving Live browsing.

## Preview contract

Preview has no visible playback controls.

Do not show:

- Play/Pause;
- Next/Previous;
- fullscreen button;
- close button;
- generic Media3 controller.

EPG is information adjacent to or over the presentation as defined by the approved visual direction, not a generic transport control layer.

---

# 7. Fullscreen Live

Fullscreen Live is video-first.

Requirements:

- one active player;
- one active video destination;
- no generic Media3 controller;
- transient OwnPlay overlay only;
- channel identity and Now/Next EPG may appear transiently;
- same-channel Preview ↔ fullscreen must preserve playback continuity;
- no duplicate audio;
- no stale surface;
- no black-video/audio-only transition.

The approved fullscreen Live visual reference is the presentation target.

---

# 8. Picture-in-Picture

Picture-in-Picture is a first-class playback destination, not a second playback session.

Always preserve:

- one player;
- one active video target;
- explicit release of the old target;
- clean binding of the PiP target;
- clean restoration when returning;
- no duplicate audio;
- no stale video surface;
- no audio-only PiP caused by lost video ownership.

Rotation-driven presentation changes must remain inactive while PiP owns playback.

Physical-device validation is mandatory for PiP rendering and surface ownership.

---

# 9. Library

Library is a cinematic home for personal media.

The approved Library reference defines the primary hierarchy:

1. Continue Watching;
2. Movies;
3. Series;
4. Downloaded Media where applicable.

Content artwork should dominate the screen.

Continue Watching must expose:

- progress;
- Resume;
- Play from Beginning.

Movies and Series should use artwork-led rows/grids and open dedicated details surfaces.

Avoid category-pill walls and legacy IPTV-style catalog chrome as the dominant structure.

---

# 10. Movie, Series and Offline Playback

Movie, Series episode and completed Offline playback use one consistent fullscreen presentation contract.

Requirements:

- video-first black fullscreen surface;
- custom transient OwnPlay controls;
- transient Back/title treatment;
- seek slider;
- current position / duration;
- Play/Pause/Retry where applicable;
- controls auto-hide during playback;
- no persistent source/network/storage/download-management chrome over video.

---

# 11. Playback Progress

Continue Watching is core.

For incomplete saved progress:

**Resume** starts from the saved position.

**Play from Beginning** starts from zero without prematurely deleting the saved progress before playback actually begins.

Progress persistence must survive normal exit/background transitions where practical.

---

# 12. Downloads

Canonical action model:

| State | User action |
|---|---|
| unmanaged | `Download` |
| `QUEUED` | `Pause` |
| `DOWNLOADING` | `Pause` |
| `PAUSED` | `Resume` |
| `FAILED` | `Retry` |
| `COMPLETED` | `Play Offline` / `Resume Offline` |

Any managed download may expose `Remove` outside fullscreen playback.

Rules:

- Retry uses repository retry semantics;
- only completed, integrity-verified files may be played Offline;
- ordinary progress updates must not reorder Downloaded Media rows;
- online playback must not implicitly cancel the same item's active download;
- paused downloads remain paused until explicit Resume.

---

# 13. Sources

Sources are user-owned configuration, not provider branding.

Support:

- Xtream-compatible source configuration;
- M3U / M3U8 source configuration;
- refresh/synchronization;
- source selection;
- provider metadata ingestion;
- EPG linkage where available.

Provider refresh must preserve supported local personalization.

Credentials must not be included in personalization backup unless an explicit later decision changes this rule.

---

# 14. Settings

Settings follows the approved dark grouped-card reference and may include:

- Playback;
- Live & EPG;
- Downloads;
- Appearance;
- Sources / source management;
- Backup & Restore;
- About.

Settings must remain visually consistent with the media product and must not revert to generic legacy preference styling.

---

# 15. Persistence and Legacy Compatibility

The rebuilt application may use new Room entities and repositories, but an update from the previously installed `app.ownplay.mobile` must not silently discard supported user state.

Before an update QA APK is considered promotable, implement and test a migration/import layer for supported legacy state, including where available:

- configured source metadata and selection;
- favorites;
- hidden/restored state;
- manual ordering;
- custom groups;
- local names/logos;
- playback progress / Continue Watching;
- managed download metadata where safely compatible.

Credentials require explicit security review before migration or backup handling.

No destructive database migration or user-data deletion is authorized without a separate explicit decision.

---

# 16. Performance and Reliability

Large playlists must remain responsive.

Prefer:

- repository-layer access;
- cache-first reads where appropriate;
- bounded background work;
- stable list keys;
- deterministic ordering;
- request coalescing;
- lifecycle-safe playback ownership;
- explicit state reducers for critical transitions;
- recoverable operations.

Reliability remains more important than ornamental animation.

---

# 17. Validation and Physical QA

Source/CI PASS is not physical-device PASS.

Physical QA remains mandatory for:

- actual video rendering;
- Preview/fullscreen surface transfer;
- Picture-in-Picture;
- background/foreground;
- rotation;
- real resume position;
- provider/network behavior;
- downloads;
- update/migration behavior;
- perceived performance;
- touch ergonomics;
- visual acceptance against approved references.

Critical playback/surface failures block promotion.

---

# 18. Documentation Rule

Update this file only when the durable product or architecture contract changes.

Do not record current HEAD, CI run IDs, temporary work status, version checkpoints or transient defects here.
