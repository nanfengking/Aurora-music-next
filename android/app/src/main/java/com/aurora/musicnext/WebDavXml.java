package com.aurora.musicnext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Bounded DAV parsing; production supplies Android's parser, never a desktop JAXP provider. */
final class WebDavXml {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DEPTH = 32, MAX_TEXT = 8192, MAX_RESPONSES = 50000;
    private static final String DAV = "DAV:";
    private WebDavXml() {}

    static List<WebDavClient.Entry> parse(byte[] xml, URI base, URI target, XmlPullParser parser) throws IOException {
        if (xml.length > MAX_BYTES) throw new IOException("WEBDAV_XML_LIMIT");
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            // Android defaults DTD processing off. Disable it if enabled, then reject DOCDECL tokens.
            // Non-DTD JVM fixture parsers need not support setting this optional feature.
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL))
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw new IOException("WEBDAV_XML_UNSAFE");
            parser.setInput(new ByteArrayInputStream(xml), null); // Honor XML encoding/BOM, including UTF-16.
            List<WebDavClient.Entry> entries = new ArrayList<>();
            Row row = null; Properties props = null;
            boolean rootSeen = false, rootClosed = false;
            int responses = 0, propertyDepth = 0, resourceDepth = 0, event;
            while ((event = next(parser)) != XmlPullParser.END_DOCUMENT) {
                int depth = parser.getDepth();
                String name = parser.getName();
                boolean dav = DAV.equals(parser.getNamespace());
                if (event == XmlPullParser.START_TAG) {
                    if (depth == 1) {
                        if (rootSeen || !dav || !"multistatus".equals(name)) throw new IOException("WEBDAV_XML_INVALID");
                        rootSeen = true;
                    } else if (depth == 2 && dav && "response".equals(name)) {
                        if (++responses > MAX_RESPONSES) throw new IOException("WEBDAV_XML_LIMIT");
                        row = new Row();
                    } else if (row != null && dav) {
                        if (depth == 3 && "href".equals(name)) {
                            String href = text(parser);
                            if (row.href != null) throw new IOException("WEBDAV_XML_INVALID");
                            row.href = href;
                        } else if (depth == 3 && "status".equals(name)) row.status = status(text(parser));
                        else if (depth == 3 && "propstat".equals(name)) { row.hasProps = true; props = new Properties(); }
                        else if (props != null) {
                            if (depth == 4 && "status".equals(name)) props.ok = successful(status(text(parser)));
                            else if (depth == 4 && "prop".equals(name)) propertyDepth = depth;
                            else if (propertyDepth == 4 && depth == 5) {
                                if ("resourcetype".equals(name)) resourceDepth = depth;
                                else if ("getcontentlength".equals(name)) {
                                    try { props.size = Math.max(0, Long.parseLong(text(parser))); }
                                    catch (NumberFormatException ignored) { props.size = 0L; }
                                } else if ("getlastmodified".equals(name)) props.modified = text(parser);
                            } else if (resourceDepth == 5 && depth == 6 && "collection".equals(name)) props.directory = true;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (depth == 1) rootClosed = true;
                    if (dav && depth == 5 && "resourcetype".equals(name)) resourceDepth = 0;
                    if (dav && depth == 4 && "prop".equals(name)) propertyDepth = 0;
                    if (dav && depth == 3 && "propstat".equals(name) && row != null && props != null) {
                        if (props.ok) {
                            row.ok = true; row.directory |= props.directory;
                            if (props.size != null) row.size = props.size;
                            if (props.modified != null) row.modified = props.modified;
                        }
                        props = null; propertyDepth = 0; resourceDepth = 0;
                    }
                    if (dav && depth == 2 && "response".equals(name) && row != null) {
                        add(entries, row, base, target); row = null;
                    }
                }
            }
            if (!rootSeen || !rootClosed) throw new IOException("WEBDAV_XML_INVALID");
            return entries;
        } catch (XmlPullParserException error) {
            // Never expose server XML, paths, parser configuration URLs or entity values in the UI.
            throw new IOException("WEBDAV_XML_INVALID", error);
        }
    }

    private static int next(XmlPullParser parser) throws IOException, XmlPullParserException {
        int event = parser.nextToken();
        if (event == XmlPullParser.DOCDECL) throw new IOException("WEBDAV_XML_UNSAFE");
        if (event == XmlPullParser.START_TAG && parser.getDepth() > MAX_DEPTH) throw new IOException("WEBDAV_XML_LIMIT");
        if (event == XmlPullParser.ENTITY_REF) {
            String name = parser.getName();
            boolean standard = "amp".equals(name) || "lt".equals(name) || "gt".equals(name)
                || "apos".equals(name) || "quot".equals(name) || (name != null && name.startsWith("#"));
            if (!standard || parser.getText() == null) throw new IOException("WEBDAV_XML_UNSAFE");
        }
        return event;
    }

    private static String text(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth = parser.getDepth(), event;
        StringBuilder value = new StringBuilder();
        while ((event = next(parser)) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) return value.toString().trim();
            if (event == XmlPullParser.START_TAG) throw new IOException("WEBDAV_XML_INVALID");
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT
                || event == XmlPullParser.ENTITY_REF || event == XmlPullParser.IGNORABLE_WHITESPACE) {
                String part = parser.getText();
                if (part != null) {
                    if (value.length() + part.length() > MAX_TEXT) throw new IOException("WEBDAV_XML_LIMIT");
                    value.append(part);
                }
            }
        }
        throw new IOException("WEBDAV_XML_INVALID");
    }

    private static int status(String value) {
        String[] parts = value.split("\\s+", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/") || parts[1].length() != 3) return -1;
        try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { return -1; }
    }
    private static boolean successful(int status) { return status >= 200 && status < 300; }

    private static void add(List<WebDavClient.Entry> entries, Row row, URI base, URI target) {
        if (row.href == null || row.href.isEmpty() || (row.status != null && !successful(row.status))
            || (row.hasProps && !row.ok)) return;
        URI item;
        try {
            item = target.resolve(row.href).normalize();
            ServerScope.requireInside(base.toString(), item.toString());
        } catch (IllegalArgumentException ignored) { return; }
        String path = item.getPath(), current = target.getPath();
        if (path == null || path.equals(current) || path.equals(current + "/")) return;
        boolean directory = row.directory || path.endsWith("/");
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String name = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (!directory && !WebDavClient.audio(name)) return;
        entries.add(new WebDavClient.Entry(item.toString(), name, directory, row.size, row.modified));
    }

    private static final class Row {
        String href, modified = "";
        Integer status;
        long size;
        boolean directory, hasProps, ok;
    }
    private static final class Properties {
        Long size;
        String modified;
        boolean directory, ok;
    }
}
