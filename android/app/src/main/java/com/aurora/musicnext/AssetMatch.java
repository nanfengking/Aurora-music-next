package com.aurora.musicnext;

import java.text.Normalizer;
import java.util.Locale;

/** Mirrors the desktop's conservative metadata matching, without Android dependencies. */
final class AssetMatch {
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }
    static boolean same(String a, String b) { return !normalize(a).isEmpty() && normalize(a).equals(normalize(b)); }
    static boolean lyrics(String title, String artist, String album, String candidateTitle, String candidateArtist, String candidateAlbum, int level) {
        return same(title, candidateTitle) && (level >= 2 || artist.isEmpty() || same(artist, candidateArtist))
            && (level >= 1 || album.isEmpty() || same(album, candidateAlbum));
    }
    static boolean cover(String title, String artist, String album, double duration,
        String candidateTitle, String candidateArtist, String candidateAlbum, double candidateDuration) {
        return !artist.isEmpty() && same(title, candidateTitle) && same(artist, candidateArtist)
            && (album.isEmpty() || same(album, candidateAlbum))
            && (duration <= 0 || candidateDuration <= 0 || Math.abs(duration - candidateDuration) <= 20);
    }
    static boolean albumCover(String artist, String album, String candidateArtist, String candidateAlbum) {
        return !artist.isEmpty() && !album.isEmpty() && same(artist, candidateArtist) && same(album, candidateAlbum);
    }
}
