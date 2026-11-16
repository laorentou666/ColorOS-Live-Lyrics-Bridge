package io.github.andrealtb.lockscreenlyrics.online;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OnlineLyricSourcesTest {
    @Test public void knownNeteaseIdFetchesTtmlWithoutSearching() throws Exception {
        List<String> urls = new ArrayList<>();
        String response = new JSONObject().put("data", new JSONObject().put("lyrics",
                "<tt><body><p begin='1s' end='2s'><span begin='1s' end='2s'>TTML</span></p></body></tt>")).toString();
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            return response;
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<OnlineLyric> results = sources.fetchAll(new LyricTrack(
                    "One Last Kiss (动画电影《新世纪福音战士新剧场版：终》主题曲)", "宇多田ヒカル",
                    "One Last Kiss", 252026, "1824020871"), new LyricRequest(), executor).get(5, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("https://api.amll.dev/v1/lyrics/get?ncmMusicId=1824020871"), urls);
            assertEquals(1, results.size());
            assertEquals("amll", results.get(0).getSource());
            assertEquals("TTML", results.get(0).getLines().get(0).getText());
        } finally { executor.shutdownNow(); }
    }

    @Test public void unavailableOrInvalidDirectTtmlFallsBackToPlatformSearch() throws Exception {
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
                assertEquals("https://api.amll.dev/v1/lyrics/get?ncmMusicId=123", urls.get(0));
                assertTrue(urls.stream().anyMatch(url -> url.contains("/search/get")));
                assertTrue(urls.stream().anyMatch(url -> url.contains("musics.fcg")));
                assertTrue(urls.stream().anyMatch(url -> url.contains("mobilecdn")));
                assertEquals(1, results.size());
                assertEquals("netease", results.get(0).getSource());
                assertEquals("Fallback", results.get(0).getLines().get(0).getText());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test public void cancellingDirectTtmlDoesNotStartFallbackSearch() throws Exception {
        List<String> urls = new ArrayList<>();
        LyricRequest request = new LyricRequest();
        OnlineLyricSources sources = new OnlineLyricSources((url, body, headers, scope) -> {
            urls.add(url);
            scope.cancel();
            return "{}";
        });
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            try {
                sources.fetchAll(new LyricTrack("Song", "Artist", "", 180000, "123"), request, executor)
                        .get(5, TimeUnit.SECONDS);
                fail("cancelled track must not start fallback searches");
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
        AtomicInteger count = new AtomicInteger();
        List<String> urls = Collections.synchronizedList(new ArrayList<>());
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
            List<OnlineLyric> results = sources.fetchAll(new LyricTrack("Song", "Artist", "Album", 180000), new LyricRequest(), executor).get(5, TimeUnit.SECONDS);
            assertEquals(3, count.get());
            assertEquals(5, results.size());
            Set<String> formats = new HashSet<>(); for (OnlineLyric result : results) formats.add(result.getFormat());
            assertEquals(new HashSet<>(Arrays.asList("yrc", "qrc", "krc", "ttml")), formats);
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("ncmMusicId=123")));
            assertTrue(urls.stream().anyMatch(u -> u.endsWith("qqMusicId=qqmid")));
        } finally { executor.shutdownNow(); }
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
