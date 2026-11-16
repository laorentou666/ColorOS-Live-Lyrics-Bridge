package io.github.andrealtb.lockscreenlyrics;

import android.media.MediaMetadata;
import android.os.Looper;
import io.github.andrealtb.lockscreenlyrics.online.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class OnlineLyricRuntimeTest {
    private MediaMetadata metadata(String title) {
        return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://player/cover")
                .putString("lyricInfo", "{\"lyric\":\"[00:01]Native\",\"trackKey\":\"" + title + "\"}").build();
    }
    private OnlineLyric lyric() {
        return new OnlineLyric("netease", "yrc", 20, OnlineLyricParser.parse("yrc", "[1000,1000](1000,500,0)On(1500,500,0)line"));
    }

    @Test public void failureKeepsOriginalMetadataAndSuccessfulResultPreservesArtwork() {
        CompletableFuture<List<OnlineLyric>> pending = new CompletableFuture<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> pending);
        MediaMetadata original = metadata("A");
        List<MediaMetadata> updates = new ArrayList<>();
        assertSame(original, runtime.observe("player", "key", original, updates::add));
        pending.complete(Collections.singletonList(lyric()));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, updates.size());
        assertEquals("content://player/cover", updates.get(0).getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        assertEquals("A", LyricInfoContract.parse(updates.get(0).getString("lyricInfo")).trackKey);
        assertEquals("bridge-online:netease", LyricInfoContract.parse(runtime.apply("key", original).getString("lyricInfo")).source);
        assertSame(original, runtime.apply("another-player", original));
        assertEquals("[00:01]Native", LyricInfoContract.parse(original.getString("lyricInfo")).lyric);
    }

    @Test public void completionQueuedBeforeTrackSwitchIsDiscardedOnMainThread() {
        List<CompletableFuture<List<OnlineLyric>>> pending = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            CompletableFuture<List<OnlineLyric>> f = new CompletableFuture<>(); pending.add(f); return f;
        });
        List<MediaMetadata> updates = new ArrayList<>();
        runtime.observe("player", "key", metadata("A"), updates::add);
        pending.get(0).complete(Collections.singletonList(lyric()));
        MediaMetadata second = metadata("B");
        runtime.observe("player", "key", second, updates::add);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(updates.isEmpty());
        pending.get(1).complete(Collections.emptyList());
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(second, runtime.apply("key", second));
    }

    @Test public void partialResultsRefreshMetadataBeforeCompletionAndAmllSurvivesLaterPlatformResults() {
        CompletableFuture<List<OnlineLyric>> pending = new CompletableFuture<>();
        List<LyricRequest> requests = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            requests.add(request);
            return pending;
        });
        MediaMetadata original = legacyMetadata("A");
        List<MediaMetadata> updates = new ArrayList<>();
        runtime.observe("player", "key", original, updates::add);
        requests.get(0).publish(lyric());
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(pending.isDone());
        assertEquals("bridge-online:netease", LyricInfoContract.parse(updates.get(0).getString("lyricInfo")).source);

        OnlineLyric amll = new OnlineLyric("amll", "ttml", 18,
                OnlineLyricParser.parse("ttml", "<tt><body><p begin='1s' end='2s'>TTML</p></body></tt>"));
        requests.get(0).publish(amll);
        requests.get(0).publish(new OnlineLyric("qqmusic", "yrc", 20, lyric().getLines()));
        pending.complete(Arrays.asList(lyric(), amll));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, updates.size());
        assertEquals("bridge-online:amll", LyricInfoContract.parse(updates.get(1).getString("lyricInfo")).source);
        assertEquals("content://player/cover", updates.get(1).getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        assertEquals("bridge-online:amll", LyricInfoContract.parse(runtime.resolveForPlayerRebuild(
                "key", original, "A", "Artist").getString("lyricInfo")).source);
    }

    @Test public void metadataEnrichmentStartsNewLookup() {
        List<CompletableFuture<List<OnlineLyric>>> pending = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            CompletableFuture<List<OnlineLyric>> f = new CompletableFuture<>(); pending.add(f); return f;
        });
        MediaMetadata first = metadata("A");
        runtime.observe("player", "key", first, value -> {});
        runtime.observe("player", "key", new MediaMetadata.Builder(first).putLong(MediaMetadata.METADATA_KEY_DURATION, 183000).build(), value -> {});
        assertEquals(2, pending.size());
    }

    @Test public void neteaseMediaIdIsForwardedAndOnlineReplayKeepsTheSameLookup() {
        List<LyricTrack> tracks = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            tracks.add(track);
            return CompletableFuture.completedFuture(Collections.singletonList(lyric()));
        });
        MediaMetadata original = new MediaMetadata.Builder(legacyMetadata("A"))
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "1824020871").build();
        List<MediaMetadata> updates = new ArrayList<>();
        runtime.observe("com.netease.cloudmusic", "media-key", original, updates::add);
        shadowOf(Looper.getMainLooper()).idle();
        runtime.observe("com.netease.cloudmusic", "media-key", updates.get(0), updates::add);
        assertEquals(1, tracks.size());
        assertEquals("1824020871", tracks.get(0).getNeteaseSongId());
        assertNotNull(runtime.resolveForPlayerRebuild("media-key", original, "A", "Artist"));
        runtime.observe("com.netease.cloudmusic", "media-key", new MediaMetadata.Builder(original)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "1910937515").build(), updates::add);
        assertEquals(2, tracks.size());
        assertEquals("1910937515", tracks.get(1).getNeteaseSongId());
        assertNull(runtime.resolveForPlayerRebuild("media-key", original, "A", "Artist"));
    }

    @Test public void otherPlayersAndInvalidNeteaseIdsKeepMetadataSearch() {
        List<LyricTrack> tracks = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            tracks.add(track);
            return CompletableFuture.completedFuture(Collections.emptyList());
        });
        MediaMetadata original = new MediaMetadata.Builder(legacyMetadata("A"))
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "1824020871").build();
        runtime.observe("com.apple.android.music", "apple-key", original, value -> {});
        runtime.observe("com.spotify.music", "spotify-key", original, value -> {});
        for (String id : Arrays.asList("", "0", "-1", "song_1824020871", "9223372036854775808")) {
            runtime.observe("com.netease.cloudmusic", "media-key", new MediaMetadata.Builder(original)
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id).build(), value -> {});
        }
        assertEquals(7, tracks.size());
        assertTrue(tracks.stream().allMatch(track -> track.getNeteaseSongId().isEmpty()));
    }

    @Test public void legacyMetadataGetsAnOnlineIdentityAcceptedByPlayerStateRepair() {
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) ->
                CompletableFuture.completedFuture(Collections.singletonList(lyric())));
        // NetEase 7.2.22 publishes the standard track fields without a Provider track key.
        MediaMetadata original = legacyMetadata("A");
        MediaMetadata enriched = runtime.observe("com.netease.cloudmusic", "media-key",
                original, value -> {});
        LyricInfoContract.Payload payload = LyricInfoContract.parse(enriched.getString("lyricInfo"));
        assertNotNull(payload);
        assertTrue(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "A", "Artist"));
        assertFalse(LyricInfoTrackMatcher.payloadMatchesTrack(payload, "B", "Artist"));
    }

    @Test public void playerRebuildUsesCompletedOnlineLyricsWithOriginalSessionMetadata() {
        CompletableFuture<List<OnlineLyric>> pending = new CompletableFuture<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> pending);
        MediaMetadata original = legacyMetadata("A");
        List<MediaMetadata> updates = new ArrayList<>();
        runtime.observe("com.netease.cloudmusic", "media-key", original, updates::add);
        assertNull(runtime.resolveForPlayerRebuild("media-key", original, "A", "Artist"));
        pending.complete(Collections.singletonList(lyric()));
        shadowOf(Looper.getMainLooper()).idle();
        runtime.observe("com.netease.cloudmusic", "media-key", updates.get(0), updates::add);

        // Pause, buffering and resume each rebuild from the unchanged player metadata.
        for (int state : new int[]{2, 6, 3}) {
            MediaMetadata resolved = runtime.resolveForPlayerRebuild(
                    "media-key", original, "A", "Artist");
            assertNotNull("Playback state " + state, resolved);
            assertEquals("bridge-online:netease",
                    LyricInfoContract.parse(resolved.getString("lyricInfo")).source);
            assertEquals("content://player/cover",
                    resolved.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        }
        assertNull(original.getString("lyricInfo"));
        assertEquals("Current line", original.getString("android.media.metadata.LYRIC"));
    }

    @Test public void onlineRepairRequiresTheCurrentMediaKeyAndCompleteTrackIdentity() {
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) ->
                CompletableFuture.completedFuture(Collections.singletonList(lyric())));
        MediaMetadata original = legacyMetadata("A");
        runtime.observe("com.netease.cloudmusic", "media-key", original, value -> {});
        assertNull(runtime.resolveForPlayerRebuild("other-key", original, "A", "Artist"));
        assertNull(runtime.resolveForPlayerRebuild("media-key", original, "B", "Artist"));
        assertNull(runtime.resolveForPlayerRebuild("media-key", original, "A", "Other artist"));
        assertNull(runtime.resolveForPlayerRebuild("media-key", original, "", "Artist"));
        for (MediaMetadata changed : new MediaMetadata[]{legacyMetadata("B"),
                new MediaMetadata.Builder(original).putString(MediaMetadata.METADATA_KEY_ARTIST, "Other artist").build(),
                new MediaMetadata.Builder(original).putString(MediaMetadata.METADATA_KEY_ALBUM, "Other album").build(),
                new MediaMetadata.Builder(original).putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "new-id").build(),
                new MediaMetadata.Builder(original).putLong(MediaMetadata.METADATA_KEY_DURATION, 183000).build()}) {
            assertNull(runtime.resolveForPlayerRebuild("media-key", changed, "A", "Artist"));
        }
    }

    @Test public void trackSwitchAndFailedLookupLeaveLegacyRepairWithoutAResult() {
        List<CompletableFuture<List<OnlineLyric>>> pending = new ArrayList<>();
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) -> {
            CompletableFuture<List<OnlineLyric>> result = new CompletableFuture<>();
            pending.add(result);
            return result;
        });
        MediaMetadata first = legacyMetadata("A");
        MediaMetadata second = legacyMetadata("B");
        runtime.observe("com.netease.cloudmusic", "media-key", first, value -> {});
        pending.get(0).complete(Collections.singletonList(lyric()));
        assertNotNull(runtime.resolveForPlayerRebuild("media-key", first, "A", "Artist"));
        runtime.observe("com.netease.cloudmusic", "media-key", second, value -> {});
        assertNull(runtime.resolveForPlayerRebuild("media-key", first, "A", "Artist"));
        pending.get(1).complete(Collections.emptyList());
        assertNull(runtime.resolveForPlayerRebuild("media-key", second, "B", "Artist"));
    }

    @Test public void nativeProviderMetadataKeepsItsExistingRepairPath() {
        OnlineLyricRuntime runtime = new OnlineLyricRuntime((track, request) ->
                CompletableFuture.completedFuture(Collections.emptyList()));
        MediaMetadata nativeMetadata = new MediaMetadata.Builder(metadata("A"))
                .putString("lyricInfo", "{\"lyric\":\"[00:01]Native\","
                        + "\"trackKey\":\"123|A|Artist|180\",\"sessionGeneration\":7}").build();
        assertSame(nativeMetadata, runtime.resolveForPlayerRebuild(
                "media-key", nativeMetadata, "A", "Artist"));
        assertNull(runtime.resolveForPlayerRebuild("media-key", nativeMetadata, "B", "Artist"));
        runtime.observe("com.netease.cloudmusic", "media-key", nativeMetadata, value -> {});
        assertSame(nativeMetadata, runtime.resolveForPlayerRebuild(
                "media-key", nativeMetadata, "A", "Artist"));
    }

    private MediaMetadata legacyMetadata(String title) {
        return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://player/cover")
                .putString("android.media.metadata.LYRIC", "Current line")
                .build();
    }
}
