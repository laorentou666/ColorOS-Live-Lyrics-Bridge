package io.github.andrealtb.lockscreenlyrics;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class PlayerMediaLyricRepairTest {
    private static final String REASON = PlayerMediaLyricRepair.REBUILD_REASON;

    @Test
    public void pauseAndBufferingRebuildsLoadTheirOwnMetadataBeforePublication() throws Exception {
        Manager manager = new Manager();
        for (int state : new int[]{2, 6, 3}) {
            Data data = new Data();
            data.state = state;
            assertTrue(repair(manager, data, REASON));
            assertEquals(Collections.singletonList("current lyrics"), data.extension.lyric.lines);
            assertSame(data.metadata, manager.loader.metadata);
            assertNull(manager.loader.previous);
            assertEquals(state, data.state);
        }
        assertEquals(3, manager.loader.calls);
    }

    @Test
    public void populatedNativeLyricsAndOtherPublicationReasonsRemainOwnedByHost() throws Exception {
        Manager manager = new Manager();
        Data data = new Data();
        Lyric nativeLyric = new Lyric("native lyrics");
        data.extension.lyric = nativeLyric;
        assertFalse(repair(manager, data, REASON));
        assertSame(nativeLyric, data.extension.lyric);
        data.extension.lyric = new Lyric();
        for (String reason : new String[]{"updateState", "onMetaDataChanged", null}) {
            assertFalse(repair(manager, data, reason));
        }
        assertEquals(0, manager.loader.calls);
    }

    @Test
    public void metadataGateRejectsDifferentTrackAndUnsupportedPlayer() throws Exception {
        Manager manager = new Manager();
        Data data = new Data();
        data.title = "other song";
        assertFalse(repair(manager, data, REASON));
        data.title = "song";
        data.packageName = "other.player";
        assertFalse(repair(manager, data, REASON));
        assertEquals(0, manager.loader.calls);
    }

    @Test
    public void missingSessionOrMetadataSkipsLoading() throws Exception {
        Manager manager = new Manager();
        Data data = new Data();
        data.token = null;
        assertFalse(repair(manager, data, REASON));
        data.token = new Object();
        data.metadata = null;
        assertFalse(repair(manager, data, REASON));
        assertEquals(0, manager.loader.calls);
    }

    @Test
    public void sameKeyWithNewSessionUsesNewPayloadWithoutPreviousTrackFallback() throws Exception {
        Manager manager = new Manager();
        Data first = new Data();
        assertTrue(repair(manager, first, REASON));
        Data next = new Data();
        next.metadata.lyrics = "new session lyrics";
        assertTrue(repair(manager, next, REASON));
        assertEquals(Collections.singletonList("new session lyrics"), next.extension.lyric.lines);
        assertEquals(Collections.singletonList("current lyrics"), first.extension.lyric.lines);
        assertNull(manager.loader.previous);
    }

    @Test
    public void emptyNativeResultKeepsOriginalExtension() throws Exception {
        Manager manager = new Manager();
        Data data = new Data();
        data.metadata.lyrics = null;
        Lyric original = data.extension.lyric;
        assertFalse(repair(manager, data, REASON));
        assertSame(original, data.extension.lyric);
    }

    @Test
    public void resolverRequiresExactPublicationSignature() {
        assertNotNull(PlayerMediaLyricRepair.resolveEntry(Manager.class, Data.class));
        assertNull(PlayerMediaLyricRepair.resolveEntry(Object.class, Data.class));
        assertNull(PlayerMediaLyricRepair.resolveEntry(StaticManager.class, Data.class));
    }

    private boolean repair(Manager manager, Data data, String reason) throws Exception {
        Method loader = Loader.class.getMethod("load", String.class, Metadata.class,
                String.class, String.class, String.class, Lyric.class);
        return PlayerMediaLyricRepair.repair(manager, "same-key", data, reason, loader,
                (pkg, title, artist, metadata) -> "player".equals(pkg)
                        && "song".equals(title) && "artist".equals(artist));
    }

    public static final class Metadata {
        String lyrics = "current lyrics";
    }

    public static final class Lyric {
        final List<String> lines;
        Lyric() { this(null); }
        Lyric(String text) {
            lines = text == null ? Collections.emptyList() : Collections.singletonList(text);
        }
        public List<String> getLines() { return lines; }
    }

    public static final class Extension {
        Lyric lyric = new Lyric();
        public Lyric getLyricData() { return lyric; }
        public void setLyricData(Lyric value) { lyric = value; }
    }

    public static final class Data {
        Object token = new Object();
        Metadata metadata = new Metadata();
        String packageName = "player";
        String title = "song";
        int state;
        final Extension extension = new Extension();
        public Object getToken() { return token; }
        public Metadata getMetadata() { return metadata; }
        public String getPackageName() { return packageName; }
        public String getSong() { return title; }
        public String getArtist() { return "artist"; }
        public Extension getMediaDataEx() { return extension; }
    }

    public static final class Loader {
        int calls;
        Metadata metadata;
        Lyric previous;
        public Lyric load(String key, Metadata value, String pkg, String title,
                String artist, Lyric old) {
            calls++;
            metadata = value;
            previous = old;
            return new Lyric(value.lyrics);
        }
    }

    public static final class Manager {
        final Loader loader = new Loader();
        public Loader getMediaDataManagerEx() { return loader; }
        public void onMediaDataLoaded(String key, String oldKey, Data data, String reason) { }
    }

    public static final class StaticManager {
        public static void onMediaDataLoaded(String key, String oldKey, Data data, String reason) { }
    }
}
