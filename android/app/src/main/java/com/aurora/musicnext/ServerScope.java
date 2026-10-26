package com.aurora.musicnext;

import java.net.URI;

final class ServerScope {
    private ServerScope() {}

    static void requireInside(String root, String address) {
        URI base = URI.create(root);
        URI target = URI.create(address);
        String path = target.getPath();
        String basePath = base.getPath();
        if (!"https".equalsIgnoreCase(base.getScheme()) || !"https".equalsIgnoreCase(target.getScheme())
            || base.getHost() == null || target.getHost() == null
            || !base.getHost().equalsIgnoreCase(target.getHost()) || port(base) != port(target)
            || target.getUserInfo() != null || target.getFragment() != null
            || path == null || basePath == null || !path.startsWith(basePath.endsWith("/") ? basePath : basePath + "/"))
            throw new IllegalArgumentException("歌曲不属于当前 WebDAV 连接，请重新连接原服务器");
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || segment.contains("\\") || segment.contains("%"))
                throw new IllegalArgumentException("WebDAV 路径包含不安全的转义或相对路径");
        }
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
