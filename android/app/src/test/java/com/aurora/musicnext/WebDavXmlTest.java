package com.aurora.musicnext;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import static org.junit.Assert.*;

/** Host parser fixtures, not proof of Android's framework parser or a real WebDAV account. */
public class WebDavXmlTest {
    private static final URI ROOT = URI.create("https://music.example/dav/");
    private static String xml(String content) { return "<d:multistatus xmlns:d=\"DAV:\">" + content + "</d:multistatus>"; }
    private static String row(String href, String properties) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + properties
            + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
    private static List<WebDavClient.Entry> parse(String value) throws IOException {
        return WebDavXml.parse(value.getBytes(StandardCharsets.UTF_8), ROOT, ROOT, new KXmlParser());
    }
    private static void rejects(String value, String code) {
        assertEquals(code, assertThrows(IOException.class, () -> parse(value)).getMessage());
    }

    @Test public void readsNamespacedDirectoriesChineseAudioAndMetadata() throws Exception {
        String value = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml(
            row("/dav/", "<d:resourcetype><d:collection/></d:resourcetype>")
            + row("/dav/Album/", "<d:resourcetype><d:collection/></d:resourcetype>")
            + row("/dav/%E6%AD%8C%E6%9B%B2.m4a", "<d:resourcetype/><d:getcontentlength>1024</d:getcontentlength><d:getlastmodified>fixture date</d:getlastmodified>")
            + row("/dav/readme.txt", "<d:resourcetype/>"));
        List<WebDavClient.Entry> entries = parse(value);
        assertEquals(2, entries.size()); assertTrue(entries.get(0).directory); assertEquals("Album", entries.get(0).name);
        assertFalse(entries.get(1).directory); assertEquals("歌曲.m4a", entries.get(1).name);
        assertEquals(1024, entries.get(1).size); assertEquals("fixture date", entries.get(1).modified);
    }

    @Test public void supportsDefaultNamespacesPredefinedAndNumericEntities() throws Exception {
        String value = "<multistatus xmlns=\"DAV:\"><response><href>/dav/&#x6B4C;&#26354;&amp;.m4a</href>"
            + "<propstat><prop><resourcetype/></prop><status>HTTP/2 200 OK</status></propstat></response></multistatus>";
        assertEquals("歌曲&.m4a", parse(value).get(0).name);
    }

    @Test public void combinesCdataAndTextWithoutLosingTheNextEntry() throws Exception {
        List<WebDavClient.Entry> entries = parse(xml(row("<![CDATA[/dav/A]]>B.m4a", "<d:resourcetype/>")
            + row("/dav/next.flac", "<d:resourcetype/>")));
        assertEquals(2, entries.size()); assertEquals("AB.m4a", entries.get(0).name); assertEquals("next.flac", entries.get(1).name);
    }

    @Test public void detectsUtf16Bom() throws Exception {
        byte[] value = ("\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + xml(row("/dav/歌曲.m4a", "<d:resourcetype/>")))
            .getBytes(StandardCharsets.UTF_16LE);
        assertEquals("歌曲.m4a", WebDavXml.parse(value, ROOT, ROOT, new KXmlParser()).get(0).name);
    }

    @Test public void ignoresFailedPropstatsAndResponseStatuses() throws Exception {
        String failed = "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getcontentlength>999</d:getcontentlength>"
            + "</d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>";
        String value = xml("<d:response><d:href>/dav/song.m4a</d:href>" + failed
            + "<d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            + "<d:response><d:href>/dav/failed.m4a</d:href>" + failed + "</d:response>"
            + "<d:response><d:href>/dav/missing.m4a</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>");
        List<WebDavClient.Entry> entries = parse(value); assertEquals(1, entries.size());
        assertFalse(entries.get(0).directory); assertEquals(123, entries.get(0).size);
    }

    @Test public void skipsForeignAndMalformedHrefsWithoutDroppingValidRows() throws Exception {
        String value = xml(row("https://other.example/dav/secret.m4a", "") + row("/dav/%ZZ.m4a", "")
            + row("/dav/%252e%252e/secret.m4a", "") + row("../outside.m4a", "") + row("good.m4a", ""));
        List<WebDavClient.Entry> entries = parse(value); assertEquals(1, entries.size());
        assertEquals("https://music.example/dav/good.m4a", entries.get(0).href);
    }

    @Test public void rejectsDtdExternalEntitiesAndInternalExpansion() {
        for (String dtd : new String[]{
            "<!DOCTYPE d:multistatus SYSTEM \"https://invalid.example/fixture.dtd\">",
            "<!DOCTYPE d:multistatus [<!ENTITY external SYSTEM \"file:///fixture-xxe.txt\">]>",
            "<!DOCTYPE d:multistatus [<!ENTITY a \"repeat\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
        }) rejects(dtd + xml(row("/dav/song.m4a", "")), "WEBDAV_XML_UNSAFE");
    }

    @Test public void rejectsUnknownEntitiesAndNestedTextFields() {
        rejects(xml(row("/dav/&unknown;.m4a", "")), "WEBDAV_XML_UNSAFE");
        rejects(xml(row("<d:href>/dav/song.m4a</d:href>", "")), "WEBDAV_XML_INVALID");
    }

    @Test public void rejectsUtf16DtdBeforeEntityUse() {
        byte[] value = ("\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
            + "<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM \"file:///fixture-xxe.txt\">]>" + xml(""))
            .getBytes(StandardCharsets.UTF_16LE);
        assertEquals("WEBDAV_XML_UNSAFE", assertThrows(IOException.class,
            () -> WebDavXml.parse(value, ROOT, ROOT, new KXmlParser())).getMessage());
    }

    @Test public void rejectsHtmlMalformedAndMultipleRootsWithSafeMessages() {
        for (String value : new String[]{"", "<html>login page</html>", "<d:multistatus xmlns:d=\"DAV:\"><d:response>",
            xml("") + xml(""), "<multistatus xmlns=\"other:\"/>"}) rejects(value, "WEBDAV_XML_INVALID");
        rejects(xml("<d:response><d:href>/dav/a.m4a</d:href><d:href>/dav/b.m4a</d:href></d:response>"), "WEBDAV_XML_INVALID");
    }

    @Test public void boundsBytesDepthAndFieldLength() {
        byte[] oversized = new byte[WebDavXml.MAX_BYTES + 1];
        assertEquals("WEBDAV_XML_LIMIT", assertThrows(IOException.class, () -> WebDavXml.parse(oversized, ROOT, ROOT, new KXmlParser())).getMessage());
        rejects(xml("<d:x>".repeat(32) + "</d:x>".repeat(32)), "WEBDAV_XML_LIMIT");
        rejects(xml(row("/dav/" + "a".repeat(8193) + ".m4a", "")), "WEBDAV_XML_LIMIT");
    }

    @Test public void acceptsEmptyDirectoriesAndClampsUnusableSizes() throws Exception {
        assertTrue(parse(xml("")).isEmpty());
        List<WebDavClient.Entry> entries = parse(xml(row("/dav/negative.mp3", "<d:getcontentlength>-20</d:getcontentlength>")
            + row("/dav/invalid.mp3", "<d:getcontentlength>not a number</d:getcontentlength>")
            + row("/dav/collection", "<d:resourcetype><d:collection/></d:resourcetype>")));
        assertEquals(3, entries.size()); assertEquals(0, entries.get(0).size); assertEquals(0, entries.get(1).size);
        assertTrue(entries.get(2).directory);
    }
}
