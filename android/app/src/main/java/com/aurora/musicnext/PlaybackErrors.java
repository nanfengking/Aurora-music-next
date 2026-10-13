package com.aurora.musicnext;

import androidx.media3.common.PlaybackException;
import java.io.FileNotFoundException;
import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Only bounded codes cross into the UI/logs; exception text can contain passwords or signed URLs. */
final class PlaybackErrors {
    private static final Set<String> SAFE = new HashSet<>(Arrays.asList(
        "AUDIO_SCOPE", "AUDIO_REDIRECT_LOOP", "AUDIO_REDIRECT_INVALID", "AUDIO_REDIRECT_UNSAFE",
        "AUDIO_NOT_AUDIO", "AUDIO_ENCODING", "AUDIO_EMPTY", "AUDIO_RANGE", "AUDIO_CANCELLED", "AUDIO_INCOMPLETE"));
    static String code(Throwable error, int mediaCode) {
        // A cyclic/custom exception chain must not hold the main thread indefinitely.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof AudioHttp.Failure) {
                String value = ((AudioHttp.Failure) cause).code;
                if (SAFE.contains(value) || value != null && value.matches("AUDIO_HTTP_[1-5][0-9]{2}")) return value;
            }
        }
        cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SSLException) return "AUDIO_TLS";
            if (cause instanceof SocketTimeoutException) return "AUDIO_TIMEOUT";
            if (cause instanceof UnknownHostException || cause instanceof SocketException) return "AUDIO_NETWORK";
            if (cause instanceof EOFException || cause instanceof ProtocolException) return "AUDIO_INCOMPLETE";
            if (cause instanceof FileNotFoundException) return "AUDIO_FILE";
        }
        switch (mediaCode) {
            case PlaybackException.ERROR_CODE_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: return "AUDIO_TIMEOUT";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: return "AUDIO_NETWORK";
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE: return "AUDIO_NOT_AUDIO";
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND: return "AUDIO_FILE";
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION: return "AUDIO_SCOPE";
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED: return "AUDIO_REDIRECT_UNSAFE";
            case PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE: return "AUDIO_RANGE";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED: return "AUDIO_FORMAT";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED: return "AUDIO_DECODER";
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED: return "AUDIO_OUTPUT";
            default: return "AUDIO_SOURCE";
        }
    }
    private PlaybackErrors() {}
}
