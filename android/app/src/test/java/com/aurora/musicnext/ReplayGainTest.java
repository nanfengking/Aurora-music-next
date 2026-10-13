package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReplayGainTest {
    @Test public void untaggedAndDisabledTracksStayAtUnity() {
        assertEquals(1, ReplayGain.NONE.factor(true), 0);
        assertEquals(1, new ReplayGain(-12.0, 4.0).factor(false), 0);
    }
    @Test public void decibelsUseAmplitudeNotPower() {
        assertEquals(.501187f, new ReplayGain(-6.0, null).factor(true), .000001f);
    }
    @Test public void positiveGainNeverBoosts() {
        assertEquals(1, new ReplayGain(6.0, .5).factor(true), 0);
    }
    @Test public void peakProtectionChoosesTheLowerFactor() {
        assertEquals(.25f, new ReplayGain(-6.0, 4.0).factor(true), 0);
        assertEquals(.251189f, new ReplayGain(-12.0, 2.0).factor(true), .000001f);
    }
    @Test public void peakAloneCanAttenuate() {
        assertEquals(.5f, new ReplayGain(null, 2.0).factor(true), 0);
    }
    @Test public void zeroIsARealGainNotAnUnknownTag() {
        ReplayGain tags = new ReplayGain(0.0, 1.0);
        assertEquals(Double.valueOf(0), tags.gainDb); assertEquals(1, tags.factor(true), 0);
    }
    @Test public void ignoresNonFiniteAndOutOfRangeNumbers() {
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -61, 61})
            assertNull(new ReplayGain(value, null).gainDb);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 65})
            assertNull(new ReplayGain(null, value).peak);
        assertEquals(1, new ReplayGain(Double.NaN, -1.0).factor(true), 0);
    }
    @Test public void parsingAcceptsOrdinaryDecimalAndOptionalDb() {
        assertEquals(-6.5, ReplayGain.parseGain(" -6.50 dB "), 0);
        assertEquals(.5, ReplayGain.parseGain("+.5 DB"), 0);
        assertEquals(1.25, ReplayGain.parsePeak(" 1.25 "), 0);
    }
    @Test public void parsingRejectsAmbiguousGarbageAndOversizedTags() {
        for (String value : new String[]{"NaN", "Infinity", "", "--1", "6 dB extra", "-7,2", "1e999", "1".repeat(257)})
            assertNull(ReplayGain.parseGain(value));
        for (String value : new String[]{"0", "-1", "1.0 dB", "Infinity", "1e999"}) assertNull(ReplayGain.parsePeak(value));
        assertNull(ReplayGain.parseGain(null)); assertNull(ReplayGain.parsePeak(null));
    }
    @Test public void boundsCannotProduceNonFiniteOrAmplifiedVolume() {
        for (double gain : new double[]{-60, -6, 0, 60}) for (double peak : new double[]{.000001, .5, 1, 64}) {
            float factor = new ReplayGain(gain, peak).factor(true);
            assertTrue(Float.isFinite(factor) && factor > 0 && factor <= 1);
        }
    }
    @Test public void changingTracksImmediatelyResetsOldAttenuation() {
        ReplayGain.State state = new ReplayGain.State(); state.select("first"); state.source("first", new ReplayGain(-12.0, null));
        assertTrue(state.volume(.8f, true) < .8f); state.select("second"); assertEquals(.8f, state.volume(.8f, true), 0);
        state.cached("first", new ReplayGain(-24.0, null)); state.source("first", new ReplayGain(-24.0, null));
        assertEquals(.8f, state.volume(.8f, true), 0);
    }
    @Test public void freshlyParsedTagsBeatLateDatabaseReloads() {
        ReplayGain.State state = new ReplayGain.State(); state.select("song");
        state.cached("song", new ReplayGain(-12.0, null)); state.source("song", new ReplayGain(-6.0, null));
        state.cached("song", new ReplayGain(-24.0, null)); assertEquals(.501187f, state.volume(1, true), .000001f);
        state.source("song", ReplayGain.NONE); state.cached("song", new ReplayGain(-24.0, null));
        assertEquals(1, state.volume(1, true), 0);
    }
    @Test public void userVolumeAndDisableRemainAuthoritative() {
        ReplayGain.State state = new ReplayGain.State(); state.select("song"); state.source("song", new ReplayGain(-6.0, null));
        assertEquals(.250594f, state.volume(.5f, true), .000001f);
        assertEquals(.5f, state.volume(.5f, false), 0); assertEquals(0, state.volume(0, true), 0);
        state.select(null); assertEquals(.5f, state.volume(.5f, true), 0);
    }
}
