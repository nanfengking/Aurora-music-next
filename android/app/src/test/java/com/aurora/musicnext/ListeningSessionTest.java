package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListeningSessionTest {
    @Test public void pauseResumeAndDuplicateCallbacksAreOneSession() {
        ListeningSession session = new ListeningSession();
        assertTrue(session.start("one")); assertFalse(session.start("one"));
        assertTrue(session.finish("one")); assertFalse(session.finish("one"));
    }
    @Test public void staleDepartureDoesNotCloseTheNextTrack() {
        ListeningSession session = new ListeningSession();
        session.start("one"); assertTrue(session.finish("one")); session.start("two");
        assertFalse(session.finish("one")); assertFalse(session.start("two")); assertTrue(session.finish("two"));
    }
    @Test public void repeatingOneTrackStartsANewSessionOnlyAfterCompletion() {
        ListeningSession session = new ListeningSession();
        assertTrue(session.start("one")); assertTrue(session.finish("one"));
        assertTrue(session.start("one")); assertFalse(session.start(null));
    }
}
