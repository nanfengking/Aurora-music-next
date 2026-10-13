package com.aurora.musicnext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checked byte arithmetic; no Android dependency and no allocation proportional to audio size. */
final class AudioRange {
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");
    static String header(long position, long length) throws AudioHttp.Failure {
        if (position < 0 || length != -1 && length <= 0) throw new AudioHttp.Failure("AUDIO_RANGE");
        try { return "bytes=" + position + "-" + (length == -1 ? "" : Long.toString(Math.addExact(position, length - 1))); }
        catch (ArithmeticException error) { throw new AudioHttp.Failure("AUDIO_RANGE"); }
    }
    static long remaining(int code, String range, long bodyLength, long position, long requested) throws AudioHttp.Failure {
        header(position, requested); // Validate even a request for the whole file.
        long available = bodyLength < 0 ? -1 : bodyLength - position;
        if (code == 206) {
            Matcher match = RANGE.matcher(range);
            if (!match.matches()) throw new AudioHttp.Failure("AUDIO_RANGE");
            try {
                long begin = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
                long total = match.group(3).equals("*") ? -1 : Long.parseLong(match.group(3));
                available = Math.addExact(Math.subtractExact(end, begin), 1);
                if (begin != position || end < begin || total >= 0 && total <= end
                    || bodyLength >= 0 && bodyLength != available || requested != -1 && available > requested)
                    throw new AudioHttp.Failure("AUDIO_RANGE");
            } catch (NumberFormatException | ArithmeticException error) { throw new AudioHttp.Failure("AUDIO_RANGE"); }
        } else if (bodyLength >= 0 && available < 0) throw new AudioHttp.Failure("AUDIO_RANGE");
        return requested == -1 ? available : available == -1 ? requested : Math.min(requested, available);
    }
    private AudioRange() {}
    /** Called after remaining() validates the range; an unknown total cannot prove a complete prefetch. */
    static long total(int code, String range, long bodyLength) throws AudioHttp.Failure {
        if (code == 200) return bodyLength;
        Matcher match = RANGE.matcher(range);
        if (code != 206 || !match.matches()) throw new AudioHttp.Failure("AUDIO_RANGE");
        if (match.group(3).equals("*")) return -1;
        try { return Long.parseLong(match.group(3)); }
        catch (NumberFormatException error) { throw new AudioHttp.Failure("AUDIO_RANGE"); }
    }
}
