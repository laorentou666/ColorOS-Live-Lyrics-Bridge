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
}
