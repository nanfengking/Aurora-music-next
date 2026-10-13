package com.aurora.musicnext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, platform-independent LRC timeline used by playback and widget scheduling. */
final class LyricTimeline {
    static final class Line {
        final long timeMs;
        final String text;
        Line(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?i)\\[offset:([+-]?\\d+)\\]");
    final List<Line> lines;

    static LyricTimeline parse(String text) { return new LyricTimeline(text); }

    LyricTimeline(String text) {
        if (text == null) text = "";
        text = text.substring(0, Math.min(text.length(), 100_000));
        long offset = 0;
        Matcher adjustment = OFFSET.matcher(text);
        if (adjustment.find()) {
            try { offset = Math.max(-60_000, Math.min(60_000, Long.parseLong(adjustment.group(1)))); }
            catch (NumberFormatException ignored) { }
        }
        List<Line> parsed = new ArrayList<>();
        for (String row : text.split("\\r?\\n")) {
            Matcher matches = TIME.matcher(row);
            List<Long> times = new ArrayList<>();
            int last = 0;
            while (matches.find() && parsed.size() + times.size() < 2000) {
                int seconds = Integer.parseInt(matches.group(2));
                if (seconds >= 60) continue;
                String fraction = matches.group(3);
                long milliseconds = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                times.add(Math.max(0, Long.parseLong(matches.group(1)) * 60_000 + seconds * 1000L + milliseconds + offset));
                last = matches.end();
            }
            String content = row.substring(last).replaceAll("<\\d{1,3}:\\d{2}\\.\\d{1,3}>", "").trim();
            for (Long time : times) parsed.add(new Line(time, content));
            if (parsed.size() >= 2000) break;
        }
        parsed.sort(Comparator.comparingLong(line -> line.timeMs));
        lines = Collections.unmodifiableList(parsed);
    }

    int index(long position) {
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs <= position) { found = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return found;
    }
    String current(long position) { int i = index(position); return i < 0 ? "" : lines.get(i).text; }
    String next(long position) { int i = index(position) + 1; return i < lines.size() ? lines.get(i).text : ""; }
    long delayUntilNext(long position) {
        int i = index(position) + 1;
        return i < lines.size() ? Math.max(100, lines.get(i).timeMs - position) : -1;
    }
}
