package com.aurora.musicnext;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The same parameterized filter is used for a page, its count and its complete playback queue. */
final class LibrarySelection {
    final String where;
    final String[] args;
    LibrarySelection(String query, boolean offlineOnly, String artist, String album, boolean favoritesOnly) {
        this(query, offlineOnly, artist, album, favoritesOnly, new String[0]);
    }
    private LibrarySelection(String query, boolean offlineOnly, String artist, String album, boolean favoritesOnly, String[] genres) {
        String pattern = "%" + escape(query.trim()) + "%";
        StringBuilder clause = new StringBuilder("(title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')");
        List<String> values = new ArrayList<>(); values.add(pattern); values.add(pattern); values.add(pattern);
        if (offlineOnly) clause.append(" AND download_complete=1");
        if (favoritesOnly) clause.append(" AND favorite=1");
        if (artist != null) { clause.append(" AND artist=? COLLATE NOCASE"); values.add(artist); }
        if (album != null) { clause.append(" AND album=? COLLATE NOCASE"); values.add(album); }
        if (genres.length > 0) {
            clause.append(" AND (");
            for (int i = 0; i < genres.length; i++) {
                if (i > 0) clause.append(" OR ");
                clause.append("genre LIKE ? ESCAPE '\\'"); values.add("%" + escape(genres[i]) + "%");
            }
            clause.append(")");
        }
        where = clause.toString(); args = values.toArray(new String[0]);
    }
    static LibrarySelection from(JSONObject value, boolean offlineOnly) {
        org.json.JSONArray terms = value.optJSONArray("genreTerms");
        if (terms != null && terms.length() > 20) throw new IllegalArgumentException("Too many genre terms");
        List<String> genres = new ArrayList<>();
        if (terms != null) for (int i = 0; i < terms.length(); i++) {
            String term = terms.optString(i).trim();
            if (term.length() > 100) throw new IllegalArgumentException("Genre term is too long");
            if (!term.isEmpty()) genres.add(term);
        }
        return new LibrarySelection(value.optString("query", ""), offlineOnly || value.optBoolean("offlineOnly"),
            value.has("artist") && !value.isNull("artist") ? value.optString("artist") : null,
            value.has("album") && !value.isNull("album") ? value.optString("album") : null, value.optBoolean("favoritesOnly"), genres.toArray(new String[0]));
    }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
}
