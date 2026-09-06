from pathlib import Path
import re
import sys

ROOT = Path("app/src")

def get(p): return Path(p).read_text(encoding="utf-8")
def put(p, s): Path(p).write_text(s, encoding="utf-8")
def rp(s, old, new, label, n=1):
    if old not in s:
        raise SystemExit(f"missing {label}")
    return s.replace(old, new, n)
def rx(s, pattern, repl, label, flags=0):
    s2, n = re.subn(pattern, repl, s, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label}: {n}")
    return s2
def noimp(s, *names):
    for name in names:
        s = s.replace(f"import {name}\n", "")
    return s
def delete(p):
    x = Path(p)
    if x.exists(): x.unlink()

legacy = {
    "app/src/main/java/app/ownplay/player/ui/live/PortraitLiveViewModes.kt": ["PortraitLiveBrowseWithViewModes"],
    "app/src/main/java/app/ownplay/player/ui/live/LandscapeLiveWorkspaceAdaptive.kt":
        ["LandscapeLiveWorkspaceAdaptive", "LandscapeLiveFocusPolicy", "LandscapeLiveFocusZone", "LandscapeLiveFocusAction"],
    "app/src/main/java/app/ownplay/player/ui/live/LiveBrowseHierarchy.kt": ["HierarchicalLiveBrowse"],
    "app/src/main/java/app/ownplay/player/ui/live/LiveBrowseHierarchyPolicy.kt":
        ["LiveBrowseHierarchyPolicy", "LiveBrowseHierarchyLevel"],
}
excluded = set(legacy)
excluded |= {
    "app/src/test/java/app/ownplay/player/ui/live/LandscapeLiveFocusPolicyTest.kt",
    "app/src/test/java/app/ownplay/player/ui/live/LiveBrowseHierarchyPolicyTest.kt",
}
hits = []
for f in ROOT.rglob("*.kt"):
    if f.as_posix() in excluded: continue
    t = f.read_text(encoding="utf-8")
    for symbols in legacy.values():
        for sym in symbols:
            if sym in t: hits.append(f"{f}: {sym}")
if hits:
    raise SystemExit("legacy Live still consumed:\n" + "\n".join(hits))
for p in excluded: delete(p)

p = "app/src/main/java/app/ownplay/player/ui/EpgGuideSheet.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.runtime.LaunchedEffect",
          "androidx.compose.runtime.withFrameNanos", "androidx.compose.ui.focus.FocusRequester",
          "androidx.compose.ui.focus.focusRequester", "androidx.compose.ui.platform.LocalConfiguration")
s = rp(s, """    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val doneFocusRequester = remember { FocusRequester() }
    val programFocusRequester = remember { FocusRequester() }
""", "", "EPG profile")
s = rx(s, r"""    val initialFocus = EpgGuideFocusPolicy\.initialFocus\(.*?    \)\n""", "", "EPG initial focus", re.S)
s = rx(s, r"""\n    LaunchedEffect\(\n        isTelevision,.*?\n    \}\n""", "\n", "EPG focus effect", re.S)
s = rx(s, r"""\n                if \(isTelevision\) \{\n                    TextButton\(.*?\n                \}\n""", "\n", "EPG Done", re.S)
s = rx(s, r"""\n                                focusRequester = if \(.*?                                \},""", "", "EPG row focus arg", re.S)
s = s.replace("    focusRequester: FocusRequester? = null,\n", "")
s = rx(s, r"""\n            \.then\(\n                focusRequester\?\.let \{ requester -> Modifier\.focusRequester\(requester\) \} \?: Modifier,\n            \)""", "", "EPG row focus modifier")
put(p, s)
delete("app/src/main/java/app/ownplay/player/ui/EpgGuideFocusPolicy.kt")
delete("app/src/test/java/app/ownplay/player/ui/EpgGuideFocusPolicyTest.kt")

p = "app/src/main/java/app/ownplay/player/ui/LivePreviewPanel.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.ui.platform.LocalConfiguration")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "preview profile")
s = s.replace("on either mobile or TV. TV keeps\n * focus in the channel browser so a second OK on the selected channel can open fullscreen. Mobile\n * gets", "on Mobile. A")
s = s.replace("Back/ESC ownership", "Back ownership")
s = rx(s, r"""            if \(!isTelevision\) \{\n(?P<body>                Box\(.*?\n                \)\n)            \}\n""", lambda m: m.group("body"), "preview tap branch", re.S)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "android.view.KeyEvent", "androidx.compose.foundation.focusable",
          "androidx.compose.ui.focus.FocusRequester", "androidx.compose.ui.focus.focusRequester",
          "androidx.compose.ui.input.key.onKeyEvent", "androidx.compose.ui.input.key.onPreviewKeyEvent",
          "androidx.compose.ui.platform.LocalConfiguration")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n    val backFocusRequester = remember\(contentKey\) \{ FocusRequester\(\) \}\n    val controlsFocusRequester = remember\(contentKey\) \{ FocusRequester\(\) \}\n    val wakeFocusRequester = remember\(contentKey\) \{ FocusRequester\(\) \}\n""", "", "on-demand profile")
s = rx(s, r"""\n    LaunchedEffect\(isTelevision, controlsVisible, playbackState, contentKey\) \{.*?\n    \}\n\n    val remoteWakeModifier = if \(isTelevision && !controlsVisible\) \{.*?\n    \}\n""", "\n", "on-demand remote focus", re.S)
s = rx(s, r"""        Box\(\n            modifier = Modifier\n                \.fillMaxSize\(\)\n                \.onPreviewKeyEvent \{ event ->.*?\n                \},\n        \) \{""",
       "        Box(\n            modifier = Modifier.fillMaxSize(),\n        ) {", "on-demand root key", re.S)
s = s.replace("\n                    .then(remoteWakeModifier),", ",", 1)
s = s.replace("modifier = Modifier.focusRequester(backFocusRequester),", "modifier = Modifier,", 1)
s = s.replace("modifier = Modifier.focusRequester(controlsFocusRequester),", "modifier = Modifier,", 1)
s = rx(s, r"""\nprivate fun KeyEvent\.isOnDemandRemoteNavigationKeyDown\(\): Boolean =.*?(?=\nprivate fun )""",
       "\n", "on-demand key helper", re.S)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/view/ContentViewMode.kt"; s = get(p)
s = noimp(s, "androidx.activity.compose.BackHandler", "androidx.compose.runtime.LaunchedEffect",
          "androidx.compose.runtime.withFrameNanos", "androidx.compose.ui.focus.FocusRequester",
          "androidx.compose.ui.focus.focusRequester", "app.ownplay.player.ui.tv.TvPopupFocusAction",
          "app.ownplay.player.ui.tv.TvPopupFocusPolicy")
i = s.index("@Composable\ninternal fun ContentViewModeMenu(")
s = s[:i] + """@Composable
internal fun ContentViewModeMenu(
    mode: ContentViewMode,
    onModeSelected: (ContentViewMode) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(buildString {
                prefix?.takeIf(String::isNotBlank)?.let { append(it); append(" · ") }
                append(mode.label)
            })
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Change view",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContentViewMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == mode) "${option.label} ✓" else option.label) },
                    onClick = {
                        expanded = false
                        if (option != mode) onModeSelected(option)
                    },
                )
            }
        }
    }
}
"""
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/library/LibraryContinueWatching.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.ui.platform.LocalConfiguration")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n    val cardWidth = if \(isTelevision\) 172\.dp else 138\.dp\n""", "    val cardWidth = 138.dp\n", "continue profile")
s = rx(s, r"""            style = if \(isTelevision\) \{\n                MaterialTheme\.typography\.titleMedium\n            \} else \{\n                MaterialTheme\.typography\.titleSmall\n            \},""",
       "            style = MaterialTheme.typography.titleSmall,", "continue title")
s = s.replace("Arrangement.spacedBy(if (isTelevision) 10.dp else 8.dp)", "Arrangement.spacedBy(8.dp)")
s = rx(s, r"""                        if \(!isTelevision\) \{\n                            ContinueWatchingProgressSlot\(progress = progress\)\n                        \}""",
       "                        ContinueWatchingProgressSlot(progress = progress)", "continue progress")
s = s.replace("if (isTelevision || !hideSubtitleOnMobile)", "if (!hideSubtitleOnMobile)")
s = rx(s, r"""                        if \(isTelevision\) \{.*?                        \} else \{\n(?P<mobile>                            Text\(.*?\n                            \)\n)                        \}""",
       lambda m: m.group("mobile"), "continue resume", re.S)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/vod/RemotePoster.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.ui.platform.LocalConfiguration")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "poster profile")
s = s.replace("contentDescription = if (isTelevision) title else null", "contentDescription = null")
s = rx(s, r"""            RemotePosterState\.Loading -> if \(isTelevision\) \{\n                PosterFallbackLabel\(title = title\)\n            \}""",
       "            RemotePosterState.Loading -> Unit", "poster loading")
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt"; s = get(p)
s = noimp(s, "androidx.compose.runtime.LaunchedEffect", "androidx.compose.runtime.remember",
          "androidx.compose.runtime.withFrameNanos", "androidx.compose.ui.focus.FocusRequester",
          "androidx.compose.ui.focus.focusRequester", "androidx.compose.ui.platform.LocalConfiguration")
s = s.replace("    focusBackOnEntry: Boolean,\n", "")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and android\.content\.res\.Configuration\.UI_MODE_TYPE_MASK ==\n            android\.content\.res\.Configuration\.UI_MODE_TYPE_TELEVISION\n    val detailPrimaryFocusRequester = remember\(movie\.movieId\) \{ FocusRequester\(\) \}\n    val offlineCopyAvailable = !isTelevision && download\?\.state == DownloadStates\.COMPLETED\n\n    LaunchedEffect\(isTelevision, focusBackOnEntry, movie\.movieId\) \{.*?\n    \}\n""",
       "    val offlineCopyAvailable = download?.state == DownloadStates.COMPLETED\n", "movie TV block", re.S)
s = rx(s, r"""                    modifier = Modifier\n                        \.weight\(1f\)\n                        \.focusRequester\(detailPrimaryFocusRequester\),""",
       "                    modifier = Modifier.weight(1f),", "movie focus")
s = s.replace("            if (!isTelevision) {\n                val target", "            val target", 1)
s = s.replace("""                if (download?.state == DownloadStates.FAILED) {
                    Text(
                        text = download.failureReason ?: "Download failed. Retry when the source is available.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            details?.let { info ->""",
"""                if (download?.state == DownloadStates.FAILED) {
                    Text(
                        text = download.failureReason ?: "Download failed. Retry when the source is available.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

            details?.let { info ->""", 1)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.runtime.withFrameNanos",
          "androidx.compose.ui.platform.LocalConfiguration")
s = s.replace("    focusBackOnEntry: Boolean,\n", "")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "series details profile")
s = rx(s, r"""\n    LaunchedEffect\(\n        isTelevision,\n        focusBackOnEntry,.*?\n    \}\n""", "\n", "series details TV focus", re.S)
s = s.replace("""    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED
""", "    val offlineCopyAvailable = download?.state == DownloadStates.COMPLETED\n", 1)
s = s.replace("if (!isTelevision && !offlineCopyAvailable)", "if (!offlineCopyAvailable)")
s = s.replace("if (!isTelevision && download != null)", "if (download != null)")
s = s.replace("if (!isTelevision && offlineCopyAvailable)", "if (offlineCopyAvailable)")
s = s.replace("                !isTelevision &&\n", "")
s = rx(s, r"""            if \(!isTelevision\) \{\n(?P<body>                download\?\.failureReason.*?\n                \}\n)            \}""",
       lambda m: m.group("body"), "series failure branch", re.S)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt"; s = get(p)
s = s.replace("            focusBackOnEntry = true,\n", "").replace("                focusBackOnEntry = returnToLibraryOnDetailBack,\n", "")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n    val catalogReturnFocusRequester = remember \{ FocusRequester\(\) \}\n""", "", "series catalog profile")
s = rx(s, r"""    LaunchedEffect\(isTelevision, restoreFocusOnEntry, focusCategoryKey\) \{.*?\n    \}\n""",
       """    LaunchedEffect(restoreFocusOnEntry, focusCategoryKey) {
        if (restoreFocusOnEntry) onFocusRestored()
    }
""", "series catalog focus", re.S)
s = s.replace("Modifier.focusRequester(catalogReturnFocusRequester)", "Modifier")
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/vod/VodRoute.kt"; s = get(p)
s = s.replace("                focusBackOnEntry = true,\n", "").replace(
    "                    focusBackOnEntry = returnToLibraryOnDetailBack || restoreDetailFocusAfterPlayback,\n", "")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and android\.content\.res\.Configuration\.UI_MODE_TYPE_MASK ==\n            android\.content\.res\.Configuration\.UI_MODE_TYPE_TELEVISION\n    val categoryFocusRequester = remember \{ FocusRequester\(\) \}\n""", "", "vod catalog profile")
s = rx(s, r"""    LaunchedEffect\(isTelevision, restoreFocusOnEntry, focusCategoryKey\) \{.*?\n    \}\n""",
       """    LaunchedEffect(restoreFocusOnEntry, focusCategoryKey) {
        if (!restoreFocusOnEntry) return@LaunchedEffect
        onFocusRestored()
    }
""", "vod catalog focus", re.S)
s = s.replace("Modifier.focusRequester(categoryFocusRequester)", "Modifier")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and android\.content\.res\.Configuration\.UI_MODE_TYPE_MASK ==\n            android\.content\.res\.Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "vod playback profile")
s = re.sub(r"""    val (?:back|controls|wake)FocusRequester = remember\(movie\.movieId\) \{ FocusRequester\(\) \}\n""", "", s)
s = rx(s, r"""\n    LaunchedEffect\(isTelevision, controlsVisible, playbackState, movie\.movieId\) \{.*?\n    \}\n\n    val remoteWakeModifier = if \(isTelevision && !controlsVisible\) \{.*?\n    \}\n""", "\n", "vod remote focus", re.S)
s = rx(s, r"""        Box\(\n            modifier = Modifier\n                \.fillMaxSize\(\)\n                \.onPreviewKeyEvent \{ event ->.*?\n                \},\n        \) \{""",
       "        Box(\n            modifier = Modifier.fillMaxSize(),\n        ) {", "vod root key", re.S)
s = s.replace(".then(remoteWakeModifier)", "")
s = re.sub(r"modifier = Modifier\.focusRequester\((?:back|controls)FocusRequester\),", "modifier = Modifier,", s)
s = rx(s, r"""\nprivate fun KeyEvent\.isRemoteNavigationKeyDown\(\): Boolean =.*?(?=\nprivate fun )""", "\n", "vod key helper", re.S)
s = noimp(s, "android.view.KeyEvent", "androidx.compose.foundation.focusable",
          "androidx.compose.ui.input.key.onKeyEvent", "androidx.compose.ui.input.key.onPreviewKeyEvent")
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/library/LibrarySeriesComponents.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.runtime.withFrameNanos",
          "androidx.compose.ui.platform.LocalConfiguration")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "library series profile")
s = rx(s, r"""\n    LaunchedEffect\(isTelevision, group\.key, selectedSeasonNumber, selectedEpisodeId\) \{.*?\n    \}\n""", "\n", "library series entry focus", re.S)
s = rx(s, r"""\n    LaunchedEffect\(\n        isTelevision,.*?\n    \}\n""", "\n", "library series return focus", re.S)
s = rx(s, r"""                primaryActionFocusRequester = episodeActionFocusRequester\n                    \.takeIf \{.*?\n                    \},""",
       "                primaryActionFocusRequester = null,", "library series requester", re.S)
put(p, s)

p = "app/src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"; s = get(p)
s = noimp(s, "android.content.res.Configuration", "androidx.compose.ui.platform.LocalConfiguration",
          "app.ownplay.player.ui.OfflineMediaTvFocusPolicy")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n""", "", "library profile")
s = s.replace("val presentationDownloads = if (isTelevision) emptyList() else downloads", "val presentationDownloads = downloads")
s = s.replace("remember(isTelevision) {", "remember {").replace("mutableStateOf(isTelevision)", "mutableStateOf(false)")
s = rx(s, r"""\n    LaunchedEffect\(isTelevision\) \{.*?\n    \}\n""", "\n", "library TV init", re.S)
s = s.replace("        isTelevision = isTelevision,\n", "")
s = rx(s, r"""\n    LaunchedEffect\(isTelevision, visibleFocusKeys, libraryViewMode\) \{.*?\n    \}\n""", "\n", "library TV focus", re.S)
s = s.replace("horizontal = if (isTelevision) 20.dp else 10.dp", "horizontal = 10.dp")
s = s.replace("vertical = if (isTelevision) 12.dp else 4.dp", "vertical = 4.dp")
s = s.replace("Arrangement.spacedBy(if (isTelevision) 10.dp else 4.dp)", "Arrangement.spacedBy(4.dp)")
s = rx(s, r"""        if \(isTelevision\) \{.*?        \} else \{\n(?P<mobile>            LazyRow\(.*?\n            \}\n)        \}\n\n        when \(filter\)""",
       lambda m: m.group("mobile") + "\n        when (filter)", "library toolbar", re.S)
s = s.replace("showLabel = isTelevision", "showLabel = false")
s = s.replace("if (isTelevision || searchExpanded || query.isNotBlank())", "if (searchExpanded || query.isNotBlank())")
s = rx(s, r"""    val configuration = LocalConfiguration\.current\n    val isTelevision =\n        configuration\.uiMode and Configuration\.UI_MODE_TYPE_MASK == Configuration\.UI_MODE_TYPE_TELEVISION\n    val cardMinSize = if \(isTelevision\) 172\.dp else 150\.dp\n    val compactMinSize = if \(isTelevision\) 120\.dp else 108\.dp\n""",
       "    val cardMinSize = 150.dp\n    val compactMinSize = 108.dp\n", "library grid profile")
s = s.replace("    isTelevision: Boolean,\n", "").replace("    !isTelevision &&\n        !offlineOnly &&", "    !offlineOnly &&")
put(p, s)

p = "app/src/test/java/app/ownplay/player/ui/DownloadActionConsistencyContractTest.kt"; s = get(p)
s = s.replace("offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED",
              "offlineCopyAvailable = download?.state == DownloadStates.COMPLETED")
put(p, s)

p = "app/src/test/java/app/ownplay/player/ui/library/UnifiedLibraryPresentationTest.kt"; s = get(p)
s = s.replace("                isTelevision = false,\n", "")
s = rx(s, r"""    @Test\n    fun `tv and offline filters keep their existing presentation`\(\) \{.*?\n    \}\n""",
"""    @Test
    fun `offline filter does not show initial loading surface`() {
        assertFalse(
            shouldShowMobileLibraryInitialLoading(
                offlineOnly = true,
                hasItems = false,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
    }
""", "library presentation TV test", re.S)
put(p, s)

forbidden = [
    "ANDROID_TV", "UI_MODE_TYPE_TELEVISION", "KEYCODE_DPAD", "KEYCODE_CHANNEL_UP",
    "KEYCODE_CHANNEL_DOWN", "TvRemote", "TvPlayback", "TvPopup", "TvBackground",
    "OfflineMediaTvFocusPolicy", "tvRemote", "isTelevision", "focusBackOnEntry",
    "LEANBACK_LAUNCHER", "android.software.leanback", "android.hardware.type.television",
]
bad = []
for f in ROOT.rglob("*"):
    if not f.is_file() or f.suffix not in {".kt", ".xml"}: continue
    t = f.read_text(encoding="utf-8", errors="ignore")
    for token in forbidden:
        if token in t: bad.append(f"{f}: {token}")
    if "/ui/tv/" in f.as_posix() or f.name == "tv_banner.xml": bad.append(str(f))
if bad:
    print("\n".join(bad))
    sys.exit(2)
