package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class CrossfadePolicyTest {
    private boolean allowed(int seconds, boolean playing, boolean loading, boolean failed, boolean blocked, boolean power, boolean one, boolean live, long duration) {
        return CrossfadePolicy.automatic(seconds, playing, loading, failed, blocked, power, one, live, duration, 1);
    }
    @Test public void onlyOffAndDesktopDurationsAreValid() {
        for (int value = 0; value <= 3; value++) assertEquals(value, CrossfadePolicy.seconds(value));
        for (double value : new double[]{-1, .5, 4, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { CrossfadePolicy.seconds(value); fail("Invalid duration accepted"); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void defaultOffAndPausedDoNoWork() { assertFalse(allowed(0, true, false, false, false, false, false, false, 60000)); assertFalse(allowed(3, false, false, false, false, false, false, false, 60000)); }
    @Test public void currentLoadingAndErrorsHavePriority() { assertFalse(allowed(3, true, true, false, false, false, false, false, 60000)); assertFalse(allowed(3, true, false, true, false, false, false, false, 60000)); }
    @Test public void queueMutationAndPowerSaveBypass() { assertFalse(allowed(3, true, false, false, true, false, false, false, 60000)); assertFalse(allowed(3, true, false, false, false, true, false, false, 60000)); }
    @Test public void repeatOneLiveAndTinyClipsAreNotOverlapped() {
        assertFalse(allowed(3, true, false, false, false, false, true, false, 60000));
        assertFalse(allowed(3, true, false, false, false, false, false, true, 60000));
        assertFalse(allowed(3, true, false, false, false, false, false, false, 6000));
        assertTrue(allowed(3, true, false, false, false, false, false, false, 6001));
    }
    @Test public void speedUsesWallClockOverlap() {
        assertEquals(3000, CrossfadePolicy.remaining(20000, 14000, 2));
        assertEquals(12000, CrossfadePolicy.remaining(20000, 14000, .5f));
        assertFalse(CrossfadePolicy.automatic(3, true, false, false, false, false, false, false, 12000, 2));
    }
    @Test public void unknownOrInvalidTimeCannotTrigger() {
        assertEquals(0, CrossfadePolicy.remaining(-1, 10, 1)); assertEquals(0, CrossfadePolicy.remaining(10, -1, 1));
        assertEquals(0, CrossfadePolicy.remaining(10, 10, 1)); assertEquals(0, CrossfadePolicy.remaining(10, 0, Float.NaN));
    }
    @Test public void scheduleIsOneBoundaryNotWholeTrackPolling() { assertEquals(53000, CrossfadePolicy.delay(60000, 3)); assertEquals(0, CrossfadePolicy.delay(6500, 3)); }
    @Test public void overlapNeverExtendsPastOutgoingEnd() { assertEquals(800, CrossfadePolicy.overlap(800, 3)); assertEquals(3000, CrossfadePolicy.overlap(6000, 3)); assertEquals(0, CrossfadePolicy.overlap(6000, 0)); }
    @Test public void envelopeClampsAndCompletes() { assertEquals(0, CrossfadePolicy.progress(-10, 1000), 0); assertEquals(.5, CrossfadePolicy.progress(500, 1000), 0); assertEquals(1, CrossfadePolicy.progress(1010, 1000), 0); }
    @Test public void mixedRequestedVolumeNeverExceedsUserLimit() {
        for (int step = 0; step <= 100; step++) {
            float progress = step / 100f;
            for (float old : new float[]{0, .1f, .5f, 1}) for (float next : new float[]{0, .3f, 1}) {
                float sum = CrossfadePolicy.volume(.7f, old, 1 - progress, 1) + CrossfadePolicy.volume(.7f, next, progress, 1);
                assertTrue(sum <= .700001f);
            }
        }
    }
    @Test public void duckingAndMuteApplyToBothStreams() { assertEquals(.1f, CrossfadePolicy.volume(1, 1, .5f, .2f), .0001f); assertEquals(0, CrossfadePolicy.volume(0, 1, 1, 1), 0); }
    @Test public void invalidGainOrMixCoefficientsCannotBoost() { assertEquals(1, CrossfadePolicy.volume(2, 8, 2, 3), 0); assertEquals(0, CrossfadePolicy.volume(1, Float.NaN, 1, 1), 0); }
    @Test public void transientLossResumesOnlyExistingIntent() { CrossfadePolicy.FocusIntent intent = new CrossfadePolicy.FocusIntent(); intent.request(true); intent.command(0); assertTrue(intent.ready); assertFalse(intent.allowed()); intent.command(1); assertTrue(intent.allowed()); }
    @Test public void userPauseDuringFocusLossPreventsSurpriseResume() { CrossfadePolicy.FocusIntent intent = new CrossfadePolicy.FocusIntent(); intent.request(true); intent.command(0); intent.request(false); intent.command(1); assertFalse(intent.ready); assertFalse(intent.allowed()); }
    @Test public void permanentLossOrDeniedFocusRequiresNewIntent() { CrossfadePolicy.FocusIntent intent = new CrossfadePolicy.FocusIntent(); intent.request(true); intent.command(-1); intent.command(1); assertFalse(intent.allowed()); intent.request(true); assertTrue(intent.allowed()); }
    @Test public void automaticHandoffDoesNotInventEarlyCompletion() {
        assertFalse(CrossfadePolicy.finished(false, 60000, 57000)); assertFalse(CrossfadePolicy.finished(false, 60000, 59000));
        assertTrue(CrossfadePolicy.finished(false, 60000, 59800)); assertTrue(CrossfadePolicy.finished(true, 60000, 60000));
        assertFalse(CrossfadePolicy.finished(false, -1, 0)); assertFalse(CrossfadePolicy.finished(false, 100, 0));
    }
}
