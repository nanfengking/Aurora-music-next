package com.aurora.musicnext;

import androidx.annotation.OptIn;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.metadata.id3.InternalFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;

/** Bounded adapter for FLAC/Vorbis comments, MP3 TXXX and M4A iTunes freeform tags. */
@OptIn(markerClass = UnstableApi.class)
final class ReplayGainTags {
    private ReplayGainTags() {}
    static ReplayGain read(Metadata metadata) {
        Double gain = null, peak = null;
        if (metadata == null) return ReplayGain.NONE;
        for (int index = 0; index < Math.min(256, metadata.length()); index++) {
            Metadata.Entry entry = metadata.get(index);
            String name = null, value = null;
            if (entry instanceof VorbisComment) {
                VorbisComment comment = (VorbisComment) entry; name = comment.key; value = comment.value;
            } else if (entry instanceof TextInformationFrame) {
                TextInformationFrame frame = (TextInformationFrame) entry;
                if ("TXXX".equals(frame.id) || "TXX".equals(frame.id)) {
                    name = frame.description; value = frame.values.isEmpty() ? null : frame.values.get(0);
                }
            } else if (entry instanceof InternalFrame) {
                InternalFrame frame = (InternalFrame) entry;
                if ("com.apple.iTunes".equalsIgnoreCase(frame.domain)) { name = frame.description; value = frame.text; }
            }
            if (name == null || name.length() > 128) continue;
            name = name.trim();
            if (gain == null && "REPLAYGAIN_TRACK_GAIN".equalsIgnoreCase(name)) gain = ReplayGain.parseGain(value);
            if (peak == null && "REPLAYGAIN_TRACK_PEAK".equalsIgnoreCase(name)) peak = ReplayGain.parsePeak(value);
            if (gain != null && peak != null) break;
        }
        // Album gain, R128, Opus header gain and Sound Check are intentionally not reapplied.
        return new ReplayGain(gain, peak);
    }
}
