# ColorOS 16 player-state lyric publication

Device evidence: OnePlus 13T (PKX110), ColorOS 16, SystemUIPlugin 16.001.002,
Bridge 4.5.3 (148), NetEase 9.0.40, NetEase Provider 1.2.1 (4), LSPosed.

The 2026-10-30 capture showed:

- 23:48:34.739: pause, Bridge `hasModel=true, hasOfficialLyric=true`.
- 23:48:34.802: native dataset commits zero rows; `lyricUiMode=false, hasLyric=true`.
- 23:50:58.277: next track commits 77 rows and enters lyric mode.
- 23:51:05.847: seek from 7243 ms to 32002 ms resolves the expected lyric row.
- 23:51:05.905: native dataset commits zero rows after BUFFERING/PLAYING updates.

Inspection of the device's SystemUI APK identifies
`LegacyMediaDataManagerImpl.loadPlayerMediaDataInBgInner` as an incomplete publication
path. Its foreground runnable creates a fresh `OplusMediaDataEx`, assigns duration,
previous playback state, artwork color and media style, and publishes with reason
`loadPlayerMediaDataInBg`. The fresh extension contains the default empty native lyric
object. The notification and metadata paths explicitly call `loadLyricInBg`.

`AbsMediaClient.onMediaDataLoaded` copies the extension's lyric data to Seedling.
`MediaLiveAlertController` serializes its original lines into the card's `extra.lyric`.
The plugin parser (`n6.f` on this build) creates its lyric model from that field;
`f7.b3` clears the lyric views when the resulting list is empty. These obfuscated
names document this inspected build only; the repair uses host method signatures.

The repair completes the existing native loader before publication, only for the
player-rebuild reason, a managed package, a present session token and metadata, and
a lyricInfo payload matching the incoming title/artist. It uses that entry's metadata
and a null previous lyric, so another track or session cannot supply fallback lyrics.
Existing populated native lyrics remain authoritative. Reflection failures return to
the host publication path. The hook leaves playback state, view geometry and user
lyric-mode preferences under host control.

Regression tests exercise pause/buffering/resume rebuilds, populated native data,
other publication reasons, missing identity, mismatched tracks/packages, session
replacement and empty loader results.

Local validation: 25 tests passed across PlayerMediaLyricRepairTest,
LyricInfoTrackMatcherTest and SystemUiLoadLyricPolicyTest; `:app:assembleDebug` passed.

On-device validation (2026-10-31): the user confirmed that the reported lockscreen
lyric disappearance was fixed after installing the Debug APK containing commit
`2e253f6` on the OnePlus 13T described above. The APK replaced the original Bridge
installation, with its settings restored. This is user-reported confirmation for
the original pause/resume and seek issue on this device/software combination.
The pre-fix log capture and local test results remain the recorded diagnostic
evidence; the post-fix outcome was confirmed by the user through manual testing.

## NetEase 7.2.22 and online lyrics (2026-11-15)

The supplied APK identifies itself as `com.netease.cloudmusic`, version `7.2.22`
(7002022), SHA-256
`a2adc3c50a83f706daa0512735b88235e35be54c74b2b7574649faacf9c49a38`.
Static inspection of `com.netease.cloudmusic.module.player.playerutilmanager.e`
shows a `MediaMetadataCompat.Builder` publishing title, artist, album, duration
and artwork. Its optional media-session lyric setting supplies current-line text
through `android.media.metadata.LYRIC`; the key is defined in
`com.netease.cloudmusic.module.lyric.a`. Bridge's existing online lookup can use
these standard track fields to produce a complete `lyricInfo` timeline.
The README's 9.0.40 Provider profile remains the documented Provider baseline;
this change adapts the fork's Bridge online-lyrics path for the supplied 7.2.22 APK.

Two gaps prevented that online timeline from completing a player-state rebuild:

- With no Provider track key, the online runtime produced `mediaKey:title:artist`.
  `LyricInfoTrackMatcher` expects `title|artist` or a Provider stable key, so it
  rejected the generated identity. The fallback now uses `TrackIdentity.buildKey`;
  supplied Provider keys retain their existing value.
- `loadPlayerMediaDataInBgInner` reads the original player `MediaSession` metadata.
  The old repair gate required that metadata to already contain `lyricInfo`, while
  online enrichment lives in SystemUI. The resolver now reapplies the completed
  online result for the exact media key, media ID, title, artist, album and duration,
  validates it against the incoming media entry, and returns the same metadata to
  the native lyric loader.

The existing rebuild-reason, managed-package, session-token, empty-native-list
and populated-loader-result checks still apply. Each native load uses a null
previous lyric. Valid incoming Provider lyrics remain available independently of
online lookup success. Pending or failed online lookup leaves the host path in
control; this adaptation depends on a successful online result for legacy metadata.
Playback controls, lyric layout and AOD continue through their existing paths.

The new legacy-identity regression failed on the original implementation before
the fix. Added regressions cover original metadata after online refresh, media-key
and track changes, lookup failure, Provider fallback, artwork preservation, and
passing the resolved metadata to the native loader.

Local build intermediates, reports and APKs are under `android_tools/bridge-build`
using `android_tools/bridge-build.init.gradle` and the existing `build-bridge.ps1`.
Local validation passed: 79 tests across the online-lyrics, player-state repair,
track-matching, native-model, load-policy and publication-gate suites;
`:app:lintDebug`, `:app:assembleDebug` and `git diff --check` also passed.
The APK certificate matches the previous `v4.5.3-online-lyrics.20261031` Debug APK
(certificate SHA-256
`54c21ffb42e81ad580e489cbd0c2f3248b22d332e121a7a6d9e96bb6baeb854b`).
On-device acceptance (2026-11-15): the user confirmed that the supplied Debug APK
worked correctly in manual testing with NetEase 7.2.22. Its SHA-256 is
`67e642b0c730391bdd01f28e957a3bb40403bf97345f1ac8b00ef647ca9ad5e7`.
This is user-reported acceptance of the 7.2.22 fix. The 9.0.40 device evidence
remains the earlier 2026-10-31 confirmation; its native repair path is covered
by the current regression tests.

Publication validation for `v4.5.3-netease-7.2.22.20261115`: all 910 unit tests
completed, with 904 passing and 6 existing external-fixture cases skipped.
Debug and Release Lint, Debug assembly, package/version/scope, certificate and
alignment checks passed. The assembled APK hash matches the accepted artifact.
