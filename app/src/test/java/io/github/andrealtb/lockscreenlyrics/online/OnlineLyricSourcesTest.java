package io.github.andrealtb.lockscreenlyrics.online;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OnlineLyricSourcesTest {
    @Test public void knownNeteaseIdDisplaysPlatformLyricsWhileAmllIsPendingThenReplacesThem() throws Exception {
        List<String> urls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch amllStarted = new CountDownLatch(1);
        CountDownLatch releaseAmll = new CountDownLatch(1);
        CountDownLatch platformDisplayed = new CountDownLatch(1);
        String response = new JSONObject().put("data", new JSONObject().put("lyrics",
                "<tt><body><p begin='1s' end='2s'>TTML</p></body></tt>")).toString();
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            if (url.contains("api.amll.dev")) {
                amllStarted.countDown();
                try { assertTrue(releaseAmll.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return response;
            }
            if (url.contains("/lyric/v1")) return "{\"yrc\":{\"lyric\":\"[1000,1000](1000,1000,0)Platform\"}}";
            return "{}";
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<CompletableFuture<List<OnlineLyric>>> pending = new ArrayList<>();
        List<OnlineLyric> published = new CopyOnWriteArrayList<>();
        OnlineLyricSession session = new OnlineLyricSession((track, request) -> {
            CompletableFuture<List<OnlineLyric>> future = sources.fetchAll(track, request, executor);
            pending.add(future);
            return future;
        }, lyric -> {
            published.add(lyric);
            if (lyric.getSource().equals("netease")) platformDisplayed.countDown();
        });
        try {
            session.setOrder("netease,qqmusic,kugou,amll");
            session.observe("A", new LyricTrack(
                    "One Last Kiss (动画电影《新世纪福音战士新剧场版：终》主题曲)", "宇多田ヒカル",
                    "One Last Kiss", 252026, "1824020871"));
            assertTrue(amllStarted.await(3, TimeUnit.SECONDS));
            assertTrue("platform lyrics must display before AMLL completes", platformDisplayed.await(3, TimeUnit.SECONDS));
            assertFalse(pending.get(0).isDone());
            assertEquals("netease", session.current("A").getSource());
            releaseAmll.countDown();
            assertEquals(2, pending.get(0).get(5, TimeUnit.SECONDS).size());
            assertEquals("amll", session.current("A").getSource());
            assertEquals("TTML", session.current("A").getLines().get(0).getText());
            assertEquals(2, published.size());
            assertTrue(urls.contains("https://api.amll.dev/v1/lyrics/get?ncmMusicId=1824020871"));
            assertFalse(urls.stream().anyMatch(url -> url.contains("/search/get")));
        } finally { releaseAmll.countDown(); executor.shutdownNow(); }
    }

    @Test public void unavailableOrInvalidDirectTtmlKeepsPlatformLyrics() throws Exception {
        for (String response : Arrays.asList("{}", "{\"data\":{\"lyrics\":\"invalid TTML\"}}", "offline")) {
            List<String> urls = Collections.synchronizedList(new ArrayList<>());
            OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
                urls.add(url);
                if (url.contains("api.amll.dev")) {
                    if (response.equals("offline")) throw new IllegalStateException("offline");
                    return response;
                }
                if (url.contains("/search/get")) return "{\"result\":{\"songs\":[{\"id\":123,\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}],\"duration\":180000}]}}";
                if (url.contains("/lyric/v1")) return "{\"lrc\":{\"lyric\":\"[00:01]Fallback\"}}";
                return "{}";
            });
            ExecutorService executor = Executors.newFixedThreadPool(6);
            try {
                List<OnlineLyric> results = sources.fetchAll(new LyricTrack("Song", "Artist", "", 180000, "123"),
                        new LyricRequest(), executor).get(5, TimeUnit.SECONDS);
                assertTrue(urls.contains("https://api.amll.dev/v1/lyrics/get?ncmMusicId=123"));
                assertFalse(urls.stream().anyMatch(url -> url.contains("/search/get")));
                assertTrue(urls.stream().anyMatch(url -> url.contains("musics.fcg")));
                assertTrue(urls.stream().anyMatch(url -> url.contains("mobilecdn")));
                assertEquals(1, results.size());
                assertEquals("netease", results.get(0).getSource());
                assertEquals("Fallback", results.get(0).getLines().get(0).getText());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test public void cancellingRequestStopsQueuedSourcesAndPublication() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricRequest request = new LyricRequest(lyric -> fail("cancelled lyrics must not be published"));
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            scope.cancel();
            return "{}";
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                sources.fetchAll(new LyricTrack("Song", "Artist", "", 180000, "123"), request, executor)
                        .get(5, TimeUnit.SECONDS);
                fail("cancelled track must stop queued sources");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof CancellationException);
            }
            assertEquals(1, urls.size());
        } finally { executor.shutdownNow(); }
    }

    @Test public void neteaseSearchMatchesPublishedAliasAndKeepsArtistAndDurationFilters() {
        String title = "One Last Kiss (动画电影《新世纪福音战士新剧场版：终》主题曲)";
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) ->
                "{\"result\":{\"songs\":[{\"id\":1824020871,\"name\":\"One Last Kiss\","
                        + "\"alias\":[\"动画电影《新世纪福音战士新剧场版：终》主题曲\"],"
                        + "\"artists\":[{\"name\":\"宇多田ヒカル\"}],\"album\":{\"name\":\"One Last Kiss\"},\"duration\":252026}]}}");
        LyricTrack track = new LyricTrack(title, "宇多田ヒカル", "One Last Kiss", 252026);
        LyricSong matched = sources.searchNetease(track, new LyricRequest());
        assertNotNull(matched);
        assertEquals("1824020871", matched.getId());
        assertTrue(OnlineLyricSelection.score(track, matched.getTrack()) >= 0);
        assertNotNull(sources.searchNetease(new LyricTrack("One Last Kiss", "宇多田ヒカル", "", 252026), new LyricRequest()));
        assertNull(sources.searchNetease(new LyricTrack(title, "Other", "", 252026), new LyricRequest()));
        assertNull(sources.searchNetease(new LyricTrack(title, "宇多田ヒカル", "", 300000), new LyricRequest()));
        assertNull(sources.searchNetease(new LyricTrack("One Last Kiss (Unrelated extended live edition recorded at a different concert)",
                "宇多田ヒカル", "", 252026), new LyricRequest()));
    }

    @Test public void allSourcesRunInParallelAndAmllReusesValidatedIds() throws Exception {
        CountDownLatch searches = new CountDownLatch(3);
        CountDownLatch releaseQq = new CountDownLatch(1);
        CountDownLatch platformAvailable = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        List<String> urls = Collections.synchronizedList(new ArrayList<>());
        List<OnlineLyric> published = new CopyOnWriteArrayList<>();
        String krc = OnlineLyricCryptoTest.krc("[1000,2000]<100,400,0>你<600,900,0>好");
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            try {
                boolean search = url.contains("/search/get") || url.contains("musics.fcg") || url.contains("mobilecdn");
                if (search) {
                    count.incrementAndGet(); searches.countDown();
                    assertTrue("all platform searches must start together", searches.await(3, TimeUnit.SECONDS));
                }
                if (url.contains("/search/get")) return "{\"result\":{\"songs\":[{\"id\":123,\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\"},\"duration\":180000}]}}";
                if (url.contains("/lyric/v1")) return "{\"yrc\":{\"lyric\":\"[1000,2000](1000,1000,0)你好\"},\"ytlrc\":{\"lyric\":\"[00:01]Hello\"}}";
                if (url.contains("musics.fcg")) {
                    assertTrue(releaseQq.await(5, TimeUnit.SECONDS));
                    assertTrue(url.endsWith(LyricCrypto.qqSign(body)));
                    JSONObject param = new JSONObject(body).getJSONObject("request").getJSONObject("param");
                    assertEquals("Song Artist", param.getString("query"));
                    return "{\"code\":0,\"request\":{\"code\":0,\"data\":{\"body\":{\"item_song\":[{\"id\":456,\"mid\":\"qqmid\",\"title\":\"Song\",\"singer\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\"},\"interval\":180}]}}}}";
                }
                if (url.contains("musicu.fcg")) return "{\"code\":0,\"request\":{\"code\":0,\"data\":{\"qrc_t\":1,\"lyric\":\"" + OnlineLyricCryptoTest.QRC + "\"}}}";
                if (url.contains("mobilecdn")) return "{\"data\":{\"info\":[{\"hash\":\"hash123\",\"songname\":\"Song\",\"singername\":\"Artist\",\"album_name\":\"Album\",\"duration\":180}]}}";
                if (url.contains("lyrics.kugou.com/search")) {
                    assertTrue(url.contains("hash=hash123") && url.contains("timelength=180"));
                    return "{\"candidates\":[{\"id\":123,\"accesskey\":\"key\",\"krctype\":1,\"contenttype\":0}]}";
                }
                if (url.contains("/download")) return "{\"fmt\":\"krc\",\"content\":\"" + krc + "\"}";
                if (url.contains("api.amll.dev")) return new JSONObject().put("data", new JSONObject().put("lyrics",
                        "<tt><body><p begin='1s' end='2s'><span begin='1s' end='2s'>TTML</span></p></body></tt>")).toString();
                throw new AssertionError(url);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            CompletableFuture<List<OnlineLyric>> pending = sources.fetchAll(new LyricTrack("Song", "Artist", "Album", 180000),
                    new LyricRequest(lyric -> {
                        published.add(lyric);
                        if (lyric.getSource().equals("netease")) platformAvailable.countDown();
                    }), executor);
            assertTrue("completed lyrics must publish while another platform is still searching", platformAvailable.await(3, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            releaseQq.countDown();
            List<OnlineLyric> results = pending.get(5, TimeUnit.SECONDS);
            assertEquals(3, count.get());
            assertEquals(5, results.size());
            assertEquals(new HashSet<>(results), new HashSet<>(published));
            Set<String> formats = new HashSet<>(); for (OnlineLyric result : results) formats.add(result.getFormat());
            assertEquals(new HashSet<>(Arrays.asList("yrc", "qrc", "krc", "ttml")), formats);
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("ncmMusicId=123")));
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("qqMusicId=qqmid")));
        } finally { releaseQq.countDown(); executor.shutdownNow(); }
    }

    @Test public void crossPlatformLyricsUseTheCatalogTitleAndArtistsForSearchAndMatching() throws Exception {
        for (String[] sample : Arrays.asList(
                new String[]{"28466084", "瑠璃の鳥", "霜月はるか", "瑠璃の鳥 (ゲーム「殻ノ少女」EDテーマ)"},
                new String[]{"409060879", "突然好想你 (Live)", "徐佳莹", "突然好想你 (Live)"})) {
            String id = sample[0], name = sample[1], artist = sample[2], displayTitle = sample[3];
            String query = name + " " + artist;
            List<String> qqBodies = new ArrayList<>(), kugouUrls = new ArrayList<>();
            AtomicInteger details = new AtomicInteger();
            OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, request) -> {
                if (url.contains("/song/detail/")) {
                    details.incrementAndGet();
                    assertTrue(url.endsWith("ids=%5B" + id + "%5D"));
                    return "{\"songs\":[{\"id\":" + id + ",\"name\":\"" + name + "\",\"artists\":[{\"name\":\"" + artist + "\"}]}]}";
                }
                if (url.contains("musics.fcg")) {
                    qqBodies.add(body);
                    return "{\"code\":0,\"request\":{\"code\":0,\"data\":{\"body\":{\"item_song\":[{\"id\":456,\"mid\":\"mid\",\"title\":\""
                            + name + "\",\"singer\":[{\"name\":\"" + artist + "\"}]}]}}}}";
                }
                if (url.contains("musicu.fcg")) return "{\"code\":0,\"request\":{\"code\":0,\"data\":{\"qrc_t\":1,\"lyric\":\"" + OnlineLyricCryptoTest.QRC + "\"}}}";
                if (url.contains("mobilecdn")) {
                    kugouUrls.add(url);
                    return "{\"data\":{\"info\":[{\"hash\":\"hash\",\"songname\":\"" + name + "\",\"singername\":\"" + artist + "\"}]}}";
                }
                if (url.contains("lyrics.kugou.com/search")) return "{\"candidates\":[{\"id\":7,\"accesskey\":\"key\"}]}";
                if (url.contains("/download")) return "{\"fmt\":\"lrc\",\"content\":\"WzAwOjAxXUxpbmU=\"}";
                if (url.endsWith("qqMusicId=mid")) return "{\"data\":{\"lyrics\":\"<tt><body><p begin='1s' end='2s'>Line</p></body></tt>\"}}";
                return "{}";
            });
            List<OnlineLyric> results = sources.fetchAll(new LyricTrack(displayTitle, "Artist shown by player", "", 0, id),
                    new LyricRequest(), Runnable::run).get();
            assertEquals(1, details.get());
            assertEquals(query, new JSONObject(qqBodies.get(0)).getJSONObject("request").getJSONObject("param").getString("query"));
            assertTrue(kugouUrls.get(0).contains("keyword=" + java.net.URLEncoder.encode(query, "UTF-8") + "&"));
            for (String source : Arrays.asList("qqmusic", "kugou", "amll")) {
                assertTrue(source, results.stream().anyMatch(lyric -> source.equals(lyric.getSource()) && lyric.getScore() >= 0));
            }
        }
    }

    @Test public void wrongArtistPreventsFetchingLyricsAndTtml() {
        List<String> urls = new ArrayList<>();
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            return "{\"result\":{\"songs\":[{\"id\":123,\"name\":\"Song\",\"artists\":[{\"name\":\"Other\"}],\"duration\":180000}]}}";
        });
        assertNull(sources.searchNetease(new LyricTrack("Song", "Artist", "", 180000), new LyricRequest()));
        assertEquals(1, urls.size());
    }

    @Test public void failingSourcesResolveToEmptyResultForFallback() throws Exception {
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> { throw new IllegalStateException("offline"); });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            assertTrue(sources.fetchAll(new LyricTrack("Song", "Artist", "", 180000), new LyricRequest(), executor).get(5, TimeUnit.SECONDS).isEmpty());
        } finally { executor.shutdownNow(); }
    }
}
