package com.aurora.musicnext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Cache keys contain neither credentials nor raw URLs; account revisions never share bytes. */
final class CacheIdentity {
    static boolean remote(String scheme) { return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme); }
    static String key(String revision, String address) {
        return key(revision, address, "");
    }
    static String key(String revision, String address, String sourceStamp) {
        if (revision == null || revision.isEmpty()) throw new IllegalArgumentException("Missing cache scope");
        return "stream-v3:" + hash(revision + "\n" + address + "\n" + (sourceStamp == null ? "" : sourceStamp));
    }
    static String source(long size, String modified) { return "source-v1:" + hash(Math.max(0, size) + "\n" + (modified == null ? "" : modified)); }
    private static String hash(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte part : hash) value.append(String.format(Locale.ROOT, "%02x", part & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
