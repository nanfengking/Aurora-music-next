package com.aurora.musicnext;

import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.InternalFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReplayGainTagsTest {
    private static TextInformationFrame text(String id, String description, String value) {
        return new TextInformationFrame(id, description, Collections.singletonList(value));
    }
    @Test public void readsFlacAndVorbisTrackTagsCaseInsensitively() {
        ReplayGain tags = ReplayGainTags.read(new Metadata(new VorbisComment("replaygain_track_gain", "-6.0 dB"), new VorbisComment("REPLAYGAIN_TRACK_PEAK", "1.1")));
        assertEquals(-6, tags.gainDb, 0); assertEquals(1.1, tags.peak, 0);
    }
    @Test public void readsMp3TxxxIncludingLegacyTxx() {
        for (String id : new String[]{"TXXX", "TXX"}) {
            ReplayGain tags = ReplayGainTags.read(new Metadata(text(id, "REPLAYGAIN_TRACK_GAIN", "-3.0 dB"), text(id, "REPLAYGAIN_TRACK_PEAK", "2")));
            assertEquals(-3, tags.gainDb, 0); assertEquals(2, tags.peak, 0);
        }
    }
    @Test public void readsM4aItunesFreeformTags() {
        ReplayGain tags = ReplayGainTags.read(new Metadata(new InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-7.25 dB"), new InternalFrame("com.apple.iTunes", "REPLAYGAIN_TRACK_PEAK", "1.3")));
        assertEquals(-7.25, tags.gainDb, 0); assertEquals(1.3, tags.peak, 0);
    }
    @Test public void invalidDuplicateDoesNotHideFirstValidTag() {
        ReplayGain tags = ReplayGainTags.read(new Metadata(new VorbisComment("REPLAYGAIN_TRACK_GAIN", "NaN"), new VorbisComment("REPLAYGAIN_TRACK_GAIN", "-4 dB"), new VorbisComment("REPLAYGAIN_TRACK_GAIN", "-40 dB")));
        assertEquals(-4, tags.gainDb, 0);
    }
    @Test public void ignoresAlbumR128AndUnrelatedId3Domains() {
        ReplayGain tags = ReplayGainTags.read(new Metadata(new VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-6 dB"), new VorbisComment("R128_TRACK_GAIN", "-512"), new InternalFrame("unrelated", "REPLAYGAIN_TRACK_GAIN", "-6 dB"), text("TIT2", "REPLAYGAIN_TRACK_GAIN", "-6 dB")));
        assertNull(tags.gainDb); assertNull(tags.peak); assertEquals(1, tags.factor(true), 0);
    }
    @Test public void handlesMissingMetadataAndEmptyValues() {
        assertSame(ReplayGain.NONE, ReplayGainTags.read(null));
        assertNull(ReplayGainTags.read(new Metadata(text("TXXX", "REPLAYGAIN_TRACK_GAIN", ""))).gainDb);
    }
    @Test public void scanningAndStringLengthsAreBounded() {
        Metadata.Entry[] entries = new Metadata.Entry[257];
        for (int i = 0; i < 256; i++) entries[i] = new VorbisComment("TITLE", "Fixture");
        entries[256] = new VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6 dB"); assertNull(ReplayGainTags.read(new Metadata(entries)).gainDb);
        assertNull(ReplayGainTags.read(new Metadata(new VorbisComment("REPLAYGAIN_TRACK_GAIN", "1".repeat(257)))).gainDb);
        assertNull(ReplayGainTags.read(new Metadata(new VorbisComment(" ".repeat(129) + "REPLAYGAIN_TRACK_GAIN", "-6 dB"))).gainDb);
    }
}
