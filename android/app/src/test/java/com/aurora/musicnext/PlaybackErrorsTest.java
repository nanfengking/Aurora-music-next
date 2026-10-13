package com.aurora.musicnext;

import androidx.media3.common.PlaybackException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.net.ProtocolException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackErrorsTest {
    @Test public void sourceErrorUnwrapsHttpButNeverExposesPrivateMessage() {
        IOException error = new IOException("fixture private URL", new AudioHttp.Failure("AUDIO_HTTP_403"));
        assertEquals("AUDIO_HTTP_403", PlaybackErrors.code(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
        assertEquals("AUDIO_SOURCE", PlaybackErrors.code(new IOException("fixture private URL"), 2000));
        assertEquals("AUDIO_SOURCE", PlaybackErrors.code(new AudioHttp.Failure("fixture private URL"), 2000));
    }
    @Test public void networkTlsAndCodecFailuresStayDistinct() {
        assertEquals("AUDIO_TIMEOUT", PlaybackErrors.code(new IOException(new SocketTimeoutException()), 2000));
        assertEquals("AUDIO_NETWORK", PlaybackErrors.code(new IOException(new UnknownHostException()), 2000));
        assertEquals("AUDIO_NETWORK", PlaybackErrors.code(new IOException(new SocketException()), 2000));
        assertEquals("AUDIO_INCOMPLETE", PlaybackErrors.code(new IOException(new ProtocolException()), 2000));
        assertEquals("AUDIO_TLS", PlaybackErrors.code(new IOException(new SSLHandshakeException("fixture")), 2000));
        assertEquals("AUDIO_FORMAT", PlaybackErrors.code(null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED));
        assertEquals("AUDIO_DECODER", PlaybackErrors.code(null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED));
        assertEquals("AUDIO_RANGE", PlaybackErrors.code(null, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE));
        assertEquals("AUDIO_OUTPUT", PlaybackErrors.code(null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED));
    }
    @Test public void cyclicChainsAreBoundedAndSafe() {
        IOException first = new IOException("fixture"), second = new IOException("fixture", first);
        first.initCause(second);
        assertEquals("AUDIO_SOURCE", PlaybackErrors.code(first, 2000));
    }
}
