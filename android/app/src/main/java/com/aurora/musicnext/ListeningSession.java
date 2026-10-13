package com.aurora.musicnext;

/** Deduplicate completion/skip across Media3's discontinuity and item-transition callbacks. */
final class ListeningSession {
    private String activeId;
    boolean start(String id) {
        if (id == null || id.equals(activeId)) return false;
        activeId = id; return true;
    }
    boolean finish(String id) {
        if (id == null || !id.equals(activeId)) return false;
        activeId = null; return true;
    }
}
