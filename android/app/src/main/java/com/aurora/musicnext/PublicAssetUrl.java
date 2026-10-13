package com.aurora.musicnext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.Dns;
import okhttp3.HttpUrl;

/** Remote resources cannot turn metadata/redirects into local-network requests. */
final class PublicAssetUrl {
    static HttpUrl image(String address) {
        return validate(address, 2000);
    }
    static HttpUrl audio(String address) { return validate(address, 8192); }
    private static HttpUrl validate(String address, int limit) {
        if (address == null || address.length() > limit) throw new IllegalArgumentException("Invalid resource URL");
        HttpUrl url = HttpUrl.parse(address.trim());
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null)
            throw new IllegalArgumentException("Resources require HTTPS without URL credentials or fragments");
        String host = url.host();
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.indexOf('.') < 0 && host.indexOf(':') < 0)
            throw new IllegalArgumentException("Private resource hosts are not supported");
        if (host.matches("[0-9.]+") || host.contains(":")) {
            try { if (!publicAddress(InetAddress.getByName(host))) throw new IllegalArgumentException("Private resource address"); }
            catch (UnknownHostException error) { throw new IllegalArgumentException("Invalid resource address"); }
        }
        return url;
    }
    static String endpoint(String address) {
        HttpUrl url = image(address);
        if (url.query() != null) throw new IllegalArgumentException("Put resource credentials in the key field, not URL parameters");
        return url.toString();
    }
    static String origin(HttpUrl url) { return url.scheme() + "://" + (url.host().contains(":") ? "[" + url.host() + "]" : url.host()) + (url.port() == 443 ? "" : ":" + url.port()); }
    static boolean sameOrigin(HttpUrl a, HttpUrl b) { return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port(); }
    static Dns dns(Dns resolver) {
        return host -> {
            List<InetAddress> addresses = resolver.lookup(host);
            if (addresses.isEmpty()) throw new UnknownHostException("Resource host has no addresses");
            for (InetAddress address : addresses) if (!publicAddress(address)) throw new UnknownHostException("Private resource DNS address rejected");
            return addresses;
        };
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return publicV4(bytes, 0);
        if (bytes.length != 16) return false;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if ((first & 254) == 252 || first == 0 && second == 0) return false;
        // DNS64 on IPv6-only cellular networks: classify the embedded IPv4 address too.
        if (first == 0 && second == 100 && (bytes[2] & 255) == 255 && (bytes[3] & 255) == 155) {
            for (int i = 4; i < 12; i++) if (bytes[i] != 0) return false;
            return publicV4(bytes, 12);
        }
        if ((first & 224) != 32) return false;
        if (first == 32 && second == 1 && ((bytes[2] == 0 && bytes[3] == 0) || ((bytes[2] & 255) == 13 && (bytes[3] & 255) == 184))) return false;
        return first != 32 || second != 2 || publicV4(bytes, 2);
    }
    private static boolean publicV4(byte[] value, int offset) {
        int a = value[offset] & 255, b = value[offset + 1] & 255, c = value[offset + 2] & 255;
        return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
            || a == 192 && b == 168 || a == 100 && b >= 64 && b <= 127 || a == 198 && (b == 18 || b == 19)
            || a == 192 && b == 0 && (c == 0 || c == 2) || a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113);
    }
}
