package io.github.andrealtb.lockscreenlyrics.online;

import org.junit.Test;
import java.util.List;
import java.util.Base64;
import static org.junit.Assert.*;

public class OnlineLyricParserTest {
    private static List<LyricLine> parse(String format, String text) {
        return OnlineLyricParser.parse(format, text);
    }
    private static String ttml(String body) {
        return "<tt xmlns='http://www.w3.org/ns/ttml' xmlns:ttm='http://www.w3.org/ns/ttml#metadata' "
                + "xmlns:itunes='http://music.apple.com/lyric-ttml-internal'><body><div>" + body + "</div></body></tt>";
    }

    @Test public void yrcUsesAbsoluteTimesAndKeepsEnd() {
        List<LyricLine> lines = parse("yrc", "[1000,2000](1100,400,0)你(1600,900,0)好");
        assertEquals(1100, lines.get(0).getWords().get(0).getStart());
        assertEquals(3000, lines.get(0).getEnd());
        assertTrue(new OnlineLyric("netease", "yrc", 18, lines).rawLyric().endsWith("<00:03.000>"));
    }

    @Test public void qrcXmlEntitiesAndWordTimes() {
        List<LyricLine> lines = parse("qrc", "<QrcInfos><Lyric_1 LyricContent=\"[1000,2000]你(1100,400)&amp;(1600,900)&#10;[4000,1000]好(4000,1000)\"/></QrcInfos>");
        assertEquals(2, lines.size());
        assertEquals("你&", lines.get(0).getText());
        assertEquals(1600, lines.get(0).getWords().get(1).getStart());
    }

    @Test public void krcOffsetsAndLanguageRows() {
        String language = "{\"content\":[{\"type\":1,\"lyricContent\":[[\"你好\"]]},{\"type\":0,\"lyricContent\":[[\"hello\"]]}]}";
        List<LyricLine> lines = parse("krc", "[language:" + Base64.getEncoder().encodeToString(language.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "]\n[1000,1000]<0,400,0>he<400,600,0>llo");
        assertEquals(1400, lines.get(0).getWords().get(1).getStart());
        assertEquals("你好", lines.get(0).getTranslation());
        assertEquals("hello", lines.get(0).getRoman());
    }

    @Test public void lrcRepeatsAndFractionalTimes() {
        List<LyricLine> lines = parse("lrc", "[ar:artist]\n[00:02.50][00:01.5]Hello\n[00:04]World");
        assertEquals(3, lines.size());
        assertEquals(1500, lines.get(0).getStart());
        assertEquals(2500, lines.get(1).getStart());
        assertEquals(0, new OnlineLyric("netease", "lrc", 18, lines).getQuality());
    }

    @Test public void ttmlDropsOverlappingBackgroundAndExtractsNestedTranslation() {
        List<LyricLine> lines = parse("ttml", ttml("<p begin='1s' end='4s'><span begin='1s' end='2s'>主</span>"
                + "<span begin='2s' end='4s'>唱<span ttm:role='x-translation'>主唱翻译</span></span>"
                + "<span ttm:role='x-bg'><span begin='1.5s' end='3s'>和声</span></span>"
                + "<span ttm:role='x-roman'>zhu chang</span></p>"));
        assertEquals(1, lines.size());
        assertEquals("主唱", lines.get(0).getText());
        assertEquals("主唱翻译", lines.get(0).getTranslation());
        assertEquals("zhu chang", lines.get(0).getRoman());
        assertEquals(2, lines.get(0).getWords().size());
        assertEquals(4000, lines.get(0).getEnd());
    }

    @Test public void backgroundInGapSurvivesAndDuetIsSorted() {
        List<LyricLine> lines = parse("ttml", ttml("<p begin='5s' end='6s' ttm:agent='v2'><span begin='5s' end='6s'>对唱</span></p>"
                + "<p begin='1s' end='4s'><span begin='1s' end='2s'>主唱</span>"
                + "<span ttm:role='x-bg' begin='3s' end='4s'><span begin='3s' end='4s'>独立和声</span></span></p>"));
        assertEquals(3, lines.size());
        assertEquals("主唱", lines.get(0).getText());
        assertEquals(3000, lines.get(1).getStart());
        assertEquals("对唱", lines.get(2).getText());
        assertEquals(5000, lines.get(2).getWords().get(0).getStart());
    }

    @Test public void backgroundOverlappingAnotherLeadIsRemoved() {
        List<LyricLine> lines = parse("ttml", ttml("<p begin='1s' end='5s'><span begin='1s' end='2s'>A</span>"
                + "<span ttm:role='x-bg' begin='3s' end='5s'><span begin='3s' end='5s'>BG</span></span></p>"
                + "<p begin='4s' end='6s'><span begin='4s' end='6s'>B</span></p>"));
        assertEquals(2, lines.size());
    }

    @Test public void headTranslationAndRomanAlignWithParagraphKey() {
        String xml = ttml("<p itunes:key='L1' begin='2s' end='3s'><span begin='2s' end='3s'>Hello</span></p>")
                .replace("<body>", "<head><metadata><itunes:translations><itunes:translation xml:lang='zh-CN'>"
                        + "<itunes:text for='L1'>你好<span ttm:role='x-bg'>和声译文</span></itunes:text>"
                        + "</itunes:translation></itunes:translations><itunes:transliterations><itunes:transliteration>"
                        + "<itunes:text for='L1'>ha luo</itunes:text></itunes:transliteration></itunes:transliterations></metadata></head><body>");
        OnlineLyric lyric = new OnlineLyric("amll", "ttml", 18, parse("ttml", xml));
        assertEquals("[00:02.000]你好", lyric.translationLyric());
        assertEquals("ha luo", lyric.getLines().get(0).getRoman());
        assertEquals("Hello", lyric.getLines().get(0).getText());
    }

    @Test public void ttmlWithoutWordTimingStaysLineQuality() {
        OnlineLyric lyric = new OnlineLyric("amll", "ttml", 18, parse("ttml", ttml("<p begin='1s' end='2s'>Line</p>")));
        assertEquals(0, lyric.getQuality());
        assertEquals(2000, lyric.getLines().get(0).getEnd());
    }

    @Test public void reversedWordsKeepTextAndProduceMonotonicTags() {
        List<LyricLine> lines = parse("yrc", "[1000,5000](3000,500,0)one(2000,500,0)two");
        assertEquals("onetwo", lines.get(0).getText());
        assertEquals(3000, lines.get(0).getWords().get(1).getStart());
        assertEquals(6000, lines.get(0).getEnd());
    }

    @Test public void translationsUsePrimaryLineTimestamp() {
        List<LyricLine> lines = OnlineLyricParser.parse("yrc", "[1000,1000](1000,1000,0)Hello",
                "[00:01.200]你好", "", 10000);
        assertEquals("[00:01.000]你好", new OnlineLyric("netease", "yrc", 18, lines).translationLyric());
    }

    @Test public void yrcPreservesLiteralParenthesesAndQrcRomanizationStaysSeparate() {
        List<LyricLine> lines = OnlineLyricParser.parse("yrc", "[1000,2000](1000,1000,0)Hi (you)(2000,1000,0)!",
                "", "[1000,2000]hai(1000,1000)you(2000,1000)", 10000);
        assertEquals("Hi (you)!", lines.get(0).getText());
        assertEquals("haiyou", lines.get(0).getRoman());
    }

    @Test public void standaloneBackgroundUsesHeadTranslationAndLeadTiming() {
        String xml = ttml("<p itunes:key='L1' begin='1s' end='5s'><span begin='1s' end='2s'>Lead</span>"
                + "<span ttm:role='x-bg' begin='3s' end='4s'><span begin='3s' end='4s'>BG</span></span></p>")
                .replace("<body>", "<head><metadata><itunes:translations><itunes:translation>"
                        + "<itunes:text for='L1'>主唱译文<span ttm:role='x-bg'>和声译文</span></itunes:text>"
                        + "</itunes:translation></itunes:translations></metadata></head><body>");
        List<LyricLine> lines = parse("ttml", xml);
        assertEquals(2, lines.size());
        assertEquals("主唱译文", lines.get(0).getTranslation());
        assertEquals(3000, lines.get(1).getStart());
        assertEquals("和声译文", lines.get(1).getTranslation());
    }

    @Test(expected = IllegalArgumentException.class) public void xmlExternalEntitiesAreRejected() {
        parse("ttml", "<!DOCTYPE tt [<!ENTITY ext SYSTEM 'file:///secret'>]><tt>&ext;</tt>");
    }
}
