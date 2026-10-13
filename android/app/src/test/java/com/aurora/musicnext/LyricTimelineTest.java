package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricTimelineTest {
    @Test public void parsesFractionsRepeatedTagsAndOffset() {
        LyricTimeline lyrics = new LyricTimeline("[offset:-250]\n[00:02.5][00:04.050]同一句\n[00:08.005]结束");
        assertEquals(3, lyrics.lines.size());
        assertEquals(2250, lyrics.lines.get(0).timeMs);
        assertEquals(3800, lyrics.lines.get(1).timeMs);
        assertEquals("", lyrics.current(2249));
        assertEquals("同一句", lyrics.current(2250));
        assertEquals("结束", lyrics.next(4000));
        assertEquals(3755, lyrics.delayUntilNext(4000));
        assertEquals(-1, lyrics.delayUntilNext(9000));
    }
    @Test public void ignoresMetadataInvalidSecondsAndPlainLyrics() {
        LyricTimeline lyrics = new LyricTimeline("[ar:An artist]\nplain text\n[00:61]invalid\n[00:03]valid");
        assertEquals(1, lyrics.lines.size());
        assertEquals("valid", lyrics.current(3000));
        assertEquals(3000, lyrics.delayUntilNext(0));
        assertTrue(new LyricTimeline(null).lines.isEmpty());
    }
    @Test public void sortsTimesAndRemovesEnhancedLrcTags() {
        LyricTimeline lyrics = new LyricTimeline("[00:09]Later\n[00:02]<00:02.0>Hello <00:03.0>world");
        assertEquals("Hello world", lyrics.current(5000));
        assertEquals("Later", lyrics.next(5000));
    }
}
