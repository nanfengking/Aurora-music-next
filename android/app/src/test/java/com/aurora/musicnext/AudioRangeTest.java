package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioRangeTest {
    @Test public void prefetchTotalMustBeKnownAndMatchTheCatalogSize() throws Exception {
        assertEquals(10, AudioRange.total(200, "", 10)); assertEquals(-1, AudioRange.total(200, "", -1));
        assertEquals(10, AudioRange.total(206, "bytes 4-9/10", 6)); assertEquals(-1, AudioRange.total(206, "bytes 4-9/*", 6));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.total(206, "bytes 4-9/99999999999999999999", 6));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.total(206, "malformed", 6));
    }
    @Test public void checkedHeadersPreserveUnboundedAndBoundedRequests() throws Exception {
        assertEquals("bytes=0-", AudioRange.header(0, -1));
        assertEquals("bytes=12-15", AudioRange.header(12, 4));
        for (long[] input : new long[][]{{-1, -1}, {0, 0}, {1, -2}, {Long.MAX_VALUE, 2}})
            assertThrows(AudioHttp.Failure.class, () -> AudioRange.header(input[0], input[1]));
    }
    @Test public void validatesRangeOffsetTotalBodyLengthAndOverflow() throws Exception {
        assertEquals(6, AudioRange.remaining(206, "bytes 4-9/10", 6, 4, -1));
        assertEquals(6, AudioRange.remaining(206, "bytes 4-9/*", -1, 4, -1));
        assertEquals(3, AudioRange.remaining(206, "bytes 4-6/7", 3, 4, 6));
        for (String range : new String[]{"", "bytes 3-9/10", "bytes 4-3/10", "bytes 4-9/9", "bytes 4-9/10, bytes 12-15/20",
            "bytes 4-9223372036854775808/*", "bytes 4-9/999999999999999999999"})
            assertThrows(AudioHttp.Failure.class, () -> AudioRange.remaining(206, range, -1, 4, -1));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.remaining(206, "bytes 4-9/10", 7, 4, -1));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.remaining(206, "bytes 4-9/10", 6, 4, 5));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.remaining(206, "bytes 0-9223372036854775807/*", -1, 0, -1));
    }
    @Test public void serverIgnoringRangeSkipsWithoutInventingBytesPastEof() throws Exception {
        assertEquals(6, AudioRange.remaining(200, "", 10, 4, -1));
        assertEquals(6, AudioRange.remaining(200, "", 10, 4, 8));
        assertEquals(2, AudioRange.remaining(200, "", 10, 4, 2));
        assertEquals(0, AudioRange.remaining(200, "", 10, 10, -1));
        assertEquals(-1, AudioRange.remaining(200, "", -1, 4, -1));
        assertEquals(8, AudioRange.remaining(200, "", -1, 4, 8));
        assertThrows(AudioHttp.Failure.class, () -> AudioRange.remaining(200, "", 10, 11, -1));
    }
}
