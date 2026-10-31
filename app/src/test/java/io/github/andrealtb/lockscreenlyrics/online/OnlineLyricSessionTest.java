package io.github.andrealtb.lockscreenlyrics.online;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class OnlineLyricSessionTest {
    private final LyricTrack track = new LyricTrack("Hello", "Alice / Bob", "Album", 180000);
    private OnlineLyric lyric(String source, String format, int score) {
        return new OnlineLyric(source, format, score, OnlineLyricParser.parse(format,
                format.equals("lrc") ? "[00:01]Hello" : "[1000,1000](1000,1000,0)Hello"));
    }

    @Test public void matchingHardFiltersAndScoresFollowSplayer() {
        assertEquals(20, OnlineLyricSelection.score(track, new LyricTrack("Hello", "Bob", "Album", 184000)));
        assertEquals(-1, OnlineLyricSelection.score(track, new LyricTrack("Hello", "Carol", "Album", 180000)));
        assertEquals(-1, OnlineLyricSelection.score(track, new LyricTrack("Hello", "Alice", "Album", 201000)));
        assertEquals(-1, OnlineLyricSelection.score(track, new LyricTrack("Else", "Alice", "Album", 180000)));
        assertEquals(-1, OnlineLyricSelection.score(new LyricTrack("Hi", "", "", 0), new LyricTrack("Hi abcde", "", "", 0)));
        assertTrue(OnlineLyricSelection.score(track, new LyricTrack("Hello (Live)", "Alice", "", 180000)) > 0);
    }

    @Test public void qualityThenScoreThenUserOrder() {
        List<String> order = OnlineLyricSelection.order("kugou,qqmusic,netease,amll");
        OnlineLyric line = lyric("kugou", "lrc", 20);
        OnlineLyric word = lyric("netease", "yrc", 18);
        assertEquals(word, OnlineLyricSelection.best(Arrays.asList(line, word), order));
        OnlineLyric qq = lyric("qqmusic", "yrc", 18);
        assertEquals(qq, OnlineLyricSelection.best(Arrays.asList(word, qq), order));
        OnlineLyric higherScore = lyric("netease", "yrc", 20);
        assertEquals(higherScore, OnlineLyricSelection.best(Arrays.asList(qq, higherScore), order));
    }

    @Test public void unknownAndDuplicatePreferencesRestoreEverySource() {
        assertEquals(Arrays.asList("qqmusic", "amll", "netease", "kugou"), OnlineLyricSelection.order("qqmusic,unknown,qqmusic"));
    }

    @Test public void lateAResultCannotReplaceBOrTheNextA() {
        List<CompletableFuture<List<OnlineLyric>>> pending = new ArrayList<>();
        List<LyricRequest> requests = new ArrayList<>();
        OnlineLyricSession session = new OnlineLyricSession((t, r) -> {
            requests.add(r);
            CompletableFuture<List<OnlineLyric>> future = new CompletableFuture<>(); pending.add(future); return future;
        }, value -> {});
        assertNull(session.observe("A", track));
        session.observe("B", track);
        session.observe("A", track);
        pending.get(0).complete(Collections.singletonList(lyric("netease", "yrc", 20)));
        pending.get(1).complete(Collections.singletonList(lyric("qqmusic", "yrc", 20)));
        assertNull(session.current("A"));
        OnlineLyric current = lyric("kugou", "yrc", 18);
        pending.get(2).complete(Collections.singletonList(current));
        assertEquals(current, session.current("A"));
        assertNull(session.current("B"));
        try { requests.get(0).check(); fail("old scope should be cancelled"); } catch (CancellationException expected) { }
    }

    @Test public void liveOrderReselectsCachedResultsWithoutRefetch() {
        CompletableFuture<List<OnlineLyric>> future = new CompletableFuture<>();
        List<OnlineLyric> published = new ArrayList<>();
        OnlineLyricSession session = new OnlineLyricSession((t, r) -> future, published::add);
        session.observe("A", track);
        OnlineLyric netease = lyric("netease", "yrc", 18), qq = lyric("qqmusic", "yrc", 18);
        session.setOrder("qqmusic,netease");
        future.complete(Arrays.asList(netease, qq));
        assertEquals(qq, session.current("A"));
        session.setOrder("netease,qqmusic");
        assertEquals(netease, session.current("A"));
        assertEquals(2, published.size());
    }

    @Test public void failureAndEmptyResultsLeaveNativeFallbackAvailable() {
        OnlineLyricSession session = new OnlineLyricSession((t, r) -> CompletableFuture.completedFuture(Collections.emptyList()), value -> fail());
        assertNull(session.observe("A", track));
        OnlineLyricSession failing = new OnlineLyricSession((t, r) -> {
            CompletableFuture<List<OnlineLyric>> f = new CompletableFuture<>(); f.completeExceptionally(new java.io.IOException()); return f;
        }, value -> fail());
        assertNull(failing.observe("A", track));
    }
}
