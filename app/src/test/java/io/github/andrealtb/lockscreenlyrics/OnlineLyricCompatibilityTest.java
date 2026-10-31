package io.github.andrealtb.lockscreenlyrics;

import io.github.andrealtb.lockscreenlyrics.online.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OnlineLyricCompatibilityTest {
    @Test public void onlineWordTimelineEntersExistingParserWithEndAndTranslationIntact() {
        OnlineLyric online = new OnlineLyric("netease", "yrc", 18, OnlineLyricParser.parse("yrc",
                "[1000,2500](1500,500,0)Hello (2300,700,0)world", "[00:01]你好世界", "", 10000));
        LyricsCoreAdapter.ParsedLyrics parsed = LyricsCoreAdapter.parse(online.rawLyric());
        assertEquals(1, parsed.lines.size());
        assertEquals("Hello world", parsed.lines.get(0).text);
        assertEquals(3500, parsed.lines.get(0).endMillis);
        assertEquals(2, parsed.lines.get(0).syllables.size());
        assertEquals(1500, parsed.lines.get(0).syllables.get(0).startMillis);
        assertEquals(2300, parsed.lines.get(0).syllables.get(1).startMillis);
        assertEquals("[00:01.000]你好世界", online.translationLyric());
    }

    @Test public void lineLyricRemainsUsableByExistingOfficialList() {
        OnlineLyric online = new OnlineLyric("kugou", "lrc", 18, OnlineLyricParser.parse("lrc", "[00:01]Hello\n[00:03]World"));
        LyricsCoreAdapter.ParsedLyrics parsed = LyricsCoreAdapter.parse(online.lineLyric());
        assertEquals(2, parsed.lines.size());
        assertEquals("Hello", parsed.lines.get(0).text);
        NativeLyricModelAssembler assembler = new NativeLyricModelAssembler(new LyricParseTraceSink() {
            public boolean traceEnabled() { return false; }
            public void trace(String message) { }
            public void onCoreParseFailure(boolean primarySource, Throwable failure) { }
        });
        assertEquals(io.github.andrealtb.lockscreenlyrics.render.LyricTimingMode.LINE_TIMED,
                assembler.assemble(online.rawLyric(), true, online.lineLyric(), true, "").model.lines.get(0).timingMode);
    }
}
