package com.aurora.musicnext;

import android.content.Context;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class WebDavClient {
    static final class Entry {
        final String href, name, modified;
        final boolean directory;
        final long size;
        Entry(String href, String name, boolean directory, long size, String modified) {
            this.href = href; this.name = name; this.directory = directory; this.size = size; this.modified = modified;
        }
        JSONObject json() throws JSONException {
            JSONObject item = new JSONObject(); item.put("href", href); item.put("name", name);
            item.put("directory", directory); item.put("size", size); item.put("modified", modified);
            if (!directory && audio(name)) item.put("songId", MusicStore.idFor(href));
            return item;
        }
    }
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).followRedirects(false).build();
    private final SecretStore secrets;
    WebDavClient(Context context) { secrets = new SecretStore(context); }

    static String normalizeRoot(String input) {
        URI uri = URI.create(input.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("首版仅支持 HTTPS WebDAV 地址，且地址不能包含账号或片段");
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (!path.endsWith("/")) path += "/";
        return URI.create(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path).toString();
    }

    List<Entry> list(String path, String root, String authorization) throws Exception {
        URI base = URI.create(root);
        URI target = path == null || path.isEmpty() ? base : base.resolve(path).normalize();
        assertInside(base, target);
        Request request = new Request.Builder().url(target.toString()).header("Authorization", authorization)
            .header("Depth", "1").method("PROPFIND", RequestBody.create(
                "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>",
                MediaType.get("application/xml; charset=utf-8"))).build();
        byte[] xml;
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403) throw new IllegalArgumentException("WebDAV 账号或密码不正确");
            if (response.code() != 207 && response.code() != 200) throw new IllegalArgumentException("WebDAV 返回 " + response.code());
            if (response.body() == null) throw new IllegalArgumentException("WebDAV 没有返回目录内容");
            try (InputStream stream = response.body().byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > WebDavXml.MAX_BYTES) throw new IllegalArgumentException("WEBDAV_XML_LIMIT");
                    output.write(buffer, 0, count);
                }
                xml = output.toByteArray();
            }
        }
        return WebDavXml.parse(xml, base, target, Xml.newPullParser());
    }
    List<Entry> list(String path) throws Exception {
        SecretStore.Connection connection = secrets.connection();
        return list(path, connection.root, connection.authorization);
    }
    JSONObject scan(MusicStore store) throws Exception {
        int added = 0, failed = 0, visited = 0;
        SecretStore.Connection connection = secrets.connection();
        ArrayDeque<String> folders = new ArrayDeque<>(); folders.add("");
        Set<String> seen = new HashSet<>();
        while (!folders.isEmpty() && seen.size() < 2000 && visited < 50000) {
            String folder = folders.removeFirst(); if (!seen.add(folder)) continue;
            try {
                for (Entry item : list(folder, connection.root, connection.authorization)) {
                    if (++visited > 50000) break;
                    if (item.directory) folders.addLast(item.href);
                    else if (store.upsert(item.href, item.name, item.size, item.modified)) added++;
                }
            } catch (Exception error) { failed++; }
        }
        if (!folders.isEmpty() || visited >= 50000) throw new IllegalArgumentException("曲库超过首版扫描上限（2000 个目录或 50000 个文件），请分目录连接");
        JSONObject result = new JSONObject(); result.put("added", added); result.put("failed", failed); return result;
    }
    static boolean audio(String name) { return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|mp4|flac|wav|ogg|opus|aac)$"); }
    private static void assertInside(URI base, URI target) {
        ServerScope.requireInside(base.toString(), target.toString());
    }
    JSONArray browse(String path, MusicStore store) throws Exception {
        JSONArray result = new JSONArray();
        for (Entry entry : list(path)) {
            if (!entry.directory) store.upsert(entry.href, entry.name, entry.size, entry.modified);
            result.put(entry.json());
        }
        return result;
    }
}
