package xyz.utkin.kino

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavTest {

    private val base = "https://nas.lan/media/".toHttpUrl()

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/media/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/media/shows/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>https://nas.lan/media/Big%20Buck%20Bunny.mkv</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>123</D:getcontentlength></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/media/Alpha.MP4</D:href>
    <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
  </D:response>
  <D:response>
    <D:href>/media/notes.nfo</D:href>
    <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
  </D:response>
</D:multistatus>"""

    private fun parse(xml: String) = parseMultistatus(xml.toByteArray(), base)

    @Test
    fun `keeps dirs and videos, drops self and non-video, dirs first then name`() {
        val entries = parse(multistatus)

        assertEquals(listOf("shows", "Alpha.MP4", "Big Buck Bunny.mkv"), entries.map { it.name })
        assertEquals(listOf(true, false, false), entries.map { it.isDir })
    }

    @Test
    fun `resolves relative and absolute hrefs, and percent-decodes names`() {
        val entries = parse(multistatus)

        assertEquals("https://nas.lan/media/shows/", entries[0].url.toString())
        assertEquals(
            "https://nas.lan/media/Big%20Buck%20Bunny.mkv",
            entries.single { it.name == "Big Buck Bunny.mkv" }.url.toString(),
        )
    }

    /**
     * Captured from the real server, with the host and titles replaced: non-default
     * port, a deep base path, and `<D:resourcetype></D:resourcetype>` written as an
     * open/close pair rather than self-closing.
     */
    @Test
    fun `parses the real server's dialect`() {
        val base = "http://nas.lan:8080/Big.Buck.Bunny.2008.2160p.UHD.Blu-Ray.HEVC.TrueHD.7.1.x265-GRP/"
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
<D:multistatus xmlns:D="DAV:">
<D:response>
<D:href>/Big.Buck.Bunny.2008.2160p.UHD.Blu-Ray.HEVC.TrueHD.7.1.x265-GRP/</D:href>
<D:propstat><D:prop><D:displayname>Big.Buck.Bunny</D:displayname>
<D:resourcetype><D:collection/></D:resourcetype></D:prop>
<D:status>HTTP/1.1 200 OK</D:status></D:propstat>
</D:response><D:response>
<D:href>/Big.Buck.Bunny.2008.2160p.UHD.Blu-Ray.HEVC.TrueHD.7.1.x265-GRP/Big.Buck.Bunny.2008.x265.mkv</D:href>
<D:propstat><D:prop><D:displayname>Big.Buck.Bunny.2008.x265.mkv</D:displayname>
<D:resourcetype></D:resourcetype><D:getcontentlength>37490000000</D:getcontentlength></D:prop>
<D:status>HTTP/1.1 200 OK</D:status></D:propstat>
</D:response>
</D:multistatus>"""

        val entries = parseMultistatus(xml.toByteArray(), base.toHttpUrl())

        // The directory's own entry is dropped; only the playable file survives.
        assertEquals(listOf("Big.Buck.Bunny.2008.x265.mkv"), entries.map { it.name })
        assertEquals(false, entries.single().isDir)
        assertEquals(base + "Big.Buck.Bunny.2008.x265.mkv", entries.single().url.toString())
    }

    /**
     * A malicious or compromised server must not be able to make the parser read local
     * files. The portable `setFeature` guard does not exist on Android, so this pins the
     * behaviour that does: the entity resolves to nothing instead of leaking a file.
     */
    @Test
    fun `external entities are not resolved`() {
        val xxe = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE m [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>/leak&secret;.mkv</D:href>
<D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
</D:multistatus>"""

        val entries = parseMultistatus(xxe.toByteArray(), base)

        assertEquals(listOf("leak.mkv"), entries.map { it.name })
    }

    @Test
    fun `namespace prefix does not matter`() {
        val reprefixed = multistatus
            .replace("D:", "dav:")
            .replace("xmlns:D=", "xmlns:dav=")

        assertEquals(
            parse(multistatus).map { it.name },
            parse(reprefixed).map { it.name },
        )
    }
}
