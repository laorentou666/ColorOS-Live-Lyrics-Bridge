package io.github.andrealtb.lockscreenlyrics.online;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OnlineLyricSourcesTest {
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
