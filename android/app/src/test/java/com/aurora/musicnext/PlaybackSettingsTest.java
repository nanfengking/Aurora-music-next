package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackSettingsTest {
    @Test public void lyricSizeIsExplicitAndBounded() {
        for (int size : new int[]{18,24,30,36}) assertEquals(size, AppearanceSettings.validLyricsSize(size));
        for (double size : new double[]{0,17,19,36.5,200,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.validLyricsSize(size));
    }
    @Test public void validatesModesAndBoundedControls() {
        assertEquals("all", PlaybackSettings.validRepeat("all"));
        assertEquals(.5f, PlaybackSettings.validSpeed(.5), 0);
        assertEquals(2f, PlaybackSettings.validSpeed(2), 0);
        assertEquals(0f, PlaybackSettings.validVolume(0), 0);
        assertEquals(180, PlaybackSettings.validSleep(180));
        assertEquals(0, PlaybackSettings.validSleep(0));
    }
    @Test public void rejectsInvalidAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validRepeat("window"));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validSpeed(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validSpeed(4));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validVolume(1.01));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validVolume(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validSleep(1.5));
        assertThrows(IllegalArgumentException.class, () -> PlaybackSettings.validSleep(181));
    }
}
