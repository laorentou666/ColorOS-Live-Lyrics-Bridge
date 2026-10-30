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
