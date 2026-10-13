package com.aurora.musicnext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicAssetUrlTest {
    @Test public void urlValidationRejectsPrivateHostsAndCredentials() {
        for (String value : new String[]{"http://example.com/image", "file:///image.jpg", "https://localhost/image", "https://127.0.0.1/image",
            "https://[::1]/image", "https://10.0.0.1/image", "https://example.local/image", "https://example.com/image#fragment"})
            assertThrows(IllegalArgumentException.class, () -> PublicAssetUrl.image(value));
        HttpUrl privateKey = HttpUrl.get("https://example.com/image").newBuilder().username("fixture").password("fixture").build();
        assertThrows(IllegalArgumentException.class, () -> PublicAssetUrl.image(privateKey.toString()));
        assertThrows(IllegalArgumentException.class, () -> PublicAssetUrl.endpoint("https://example.com/api?key=fixture"));
        assertEquals("https://example.com/api", PublicAssetUrl.endpoint("https://example.com/api"));
        assertNotNull(PublicAssetUrl.image("https://example.com/image?version=2"));
    }
    @Test public void dnsCannotResolveToAnyPrivateOrMixedAddress() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8"), privateIp = InetAddress.getByName("127.0.0.1");
        assertEquals(Collections.singletonList(publicIp), PublicAssetUrl.dns(host -> Collections.singletonList(publicIp)).lookup("example.com"));
        assertThrows(UnknownHostException.class, () -> PublicAssetUrl.dns(host -> Arrays.asList(publicIp, privateIp)).lookup("example.com"));
        for (String address : new String[]{"0.0.0.0", "10.0.0.1", "100.64.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "198.18.0.1", "224.0.0.1", "::", "fd00::1", "fe80::1", "2001:db8::1", "::ffff:127.0.0.1", "2002:7f00:1::1"})
            assertFalse(address, PublicAssetUrl.publicAddress(InetAddress.getByName(address)));
    }
    @Test public void ipv6AndDns64CellularAddressesAreClassifiedWithoutRejectingPublicV4() throws Exception {
        assertTrue(PublicAssetUrl.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        assertTrue(PublicAssetUrl.publicAddress(InetAddress.getByName("64:ff9b::808:808")));
        assertFalse(PublicAssetUrl.publicAddress(InetAddress.getByName("64:ff9b::a00:1")));
        assertFalse(PublicAssetUrl.sameOrigin(HttpUrl.get("https://example.com/api"), HttpUrl.get("https://images.example.com/image")));
        assertFalse(PublicAssetUrl.sameOrigin(HttpUrl.get("https://example.com/api"), HttpUrl.get("https://example.com:444/image")));
        assertTrue(PublicAssetUrl.sameOrigin(HttpUrl.get("https://example.com/api"), HttpUrl.get("https://example.com/image")));
    }
}
