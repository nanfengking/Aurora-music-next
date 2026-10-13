package com.aurora.musicnext;

import android.util.Xml;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android parser with in-memory fixtures only: no network, credentials, files or user DB. */
@RunWith(AndroidJUnit4.class)
public class WebDavXmlInstrumentedTest {
    private static final URI ROOT = URI.create("https://music.example/dav/");
    private static String xml(String body) { return "<d:multistatus xmlns:d=\"DAV:\">" + body + "</d:multistatus>"; }
    private static List<WebDavClient.Entry> parse(String body) throws IOException {
        return WebDavXml.parse(body.getBytes(StandardCharsets.UTF_8), ROOT, ROOT, Xml.newPullParser());
    }

    @Test public void androidParserReadsDirectoriesAndChineseM4aWithoutJaxpFeatures() throws Exception {
        String props = "<d:propstat><d:prop><d:getcontentlength>321</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>";
        List<WebDavClient.Entry> entries = parse(xml("<d:response><d:href>/dav/%E6%AD%8C.m4a</d:href>" + props + "</d:response>"
            + "<d:response><d:href>/dav/Album/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>"
            + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"));
        assertEquals(2, entries.size()); assertEquals("歌.m4a", entries.get(0).name);
        assertEquals(321, entries.get(0).size); assertTrue(entries.get(1).directory);
    }

    @Test public void androidParserHonorsUtf16AndEscapedFilenames() throws Exception {
        String value = "\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?><multistatus xmlns=\"DAV:\"><response>"
            + "<href>/dav/&#x6B4C;&amp;.m4a</href></response></multistatus>";
        List<WebDavClient.Entry> entries = WebDavXml.parse(value.getBytes(StandardCharsets.UTF_16LE), ROOT, ROOT, Xml.newPullParser());
        assertEquals("歌&.m4a", entries.get(0).name);
    }

    @Test public void androidParserRejectsDtdAndUnknownEntities() {
        for (String dtd : new String[]{"<!DOCTYPE d:multistatus SYSTEM \"https://invalid.example/fixture.dtd\">",
            "<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM \"file:///fixture-xxe.txt\">]>"}) {
            assertEquals("WEBDAV_XML_UNSAFE", assertThrows(IOException.class, () -> parse(dtd + xml(""))).getMessage());
        }
        assertEquals("WEBDAV_XML_UNSAFE", assertThrows(IOException.class, () -> parse(xml("<d:response><d:href>/dav/&unknown;.m4a</d:href></d:response>"))).getMessage());
        byte[] utf16 = ("\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE d:multistatus SYSTEM \"https://invalid.example/fixture.dtd\">" + xml(""))
            .getBytes(StandardCharsets.UTF_16LE);
        assertEquals("WEBDAV_XML_UNSAFE", assertThrows(IOException.class,
            () -> WebDavXml.parse(utf16, ROOT, ROOT, Xml.newPullParser())).getMessage());
    }

    @Test public void androidParserRejectsNonDavBodiesAndDeepNesting() {
        assertEquals("WEBDAV_XML_INVALID", assertThrows(IOException.class, () -> parse("<html>fixture login</html>")).getMessage());
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 32; i++) nested.append("<d:x>");
        for (int i = 0; i < 32; i++) nested.append("</d:x>");
        assertEquals("WEBDAV_XML_LIMIT", assertThrows(IOException.class, () -> parse(xml(nested.toString()))).getMessage());
    }
}
