package xyz.utkin.kino

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaTest {

    private val description = "http://10.0.0.9:8200/rootDesc.xml".toHttpUrl()

    private val didl = """<?xml version="1.0"?>
<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
           xmlns:dc="http://purl.org/dc/elements/1.1/"
           xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
  <container id="64${'$'}1" parentID="64" childCount="3">
    <dc:title>Shows</dc:title>
    <upnp:class>object.container.storageFolder</upnp:class>
  </container>
  <item id="64${'$'}1${'$'}0">
    <dc:title>Big Buck Bunny</dc:title>
    <upnp:class>object.item.videoItem</upnp:class>
    <res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_CI=1">http://nas.lan:8200/transcoded.mkv</res>
    <res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_CI=0">http://nas.lan:8200/original.mkv</res>
  </item>
  <item id="64${'$'}1${'$'}1">
    <dc:title>Poster</dc:title>
    <upnp:class>object.item.imageItem.photo</upnp:class>
    <res protocolInfo="http-get:*:image/jpeg:*">http://nas.lan:8200/poster.jpg</res>
  </item>
  <item id="64${'$'}1${'$'}2">
    <dc:title>Not over HTTP</dc:title>
    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
    <res protocolInfo="internal:nas.lan:audio/mpeg:*">internal-only</res>
  </item>
  <item id="64${'$'}1${'$'}3">
    <dc:title>Track</dc:title>
    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
    <res protocolInfo="http-get:*:audio/flac:*">http://nas.lan:8200/track.flac</res>
  </item>
</DIDL-Lite>"""

    @Test
    fun `keeps containers and playable items, drops images and anything not over http`() {
        val entries = parseDidl(didl, description)

        assertEquals(listOf("Shows", "Big Buck Bunny", "Track"), entries.map { it.name })
        assertEquals(listOf(true, false, false), entries.map { it.isDir })
    }

    /** Direct Play is the point: a `DLNA.ORG_CI=1` res is the server's transcode. */
    @Test
    fun `prefers the original res over a transcode`() {
        val film = parseDidl(didl, description).single { it.name == "Big Buck Bunny" }

        assertEquals("http://nas.lan:8200/original.mkv", film.url.toString())
    }

    /** A container is not a URL, so what the stack carries has to survive a round trip. */
    @Test
    fun `a container round trips through the browse stack`() {
        val shows = parseDidl(didl, description).single { it.name == "Shows" }
        val loc = decodeLoc(shows.loc)

        assertEquals(description, loc?.description)
        assertEquals("64\$1", loc?.objectId)
        assertEquals("Shows", loc?.title)
        // A WebDAV row is a plain URL and must not read as a DLNA one.
        assertNull(decodeLoc("https://nas.lan/dav/Shows/"))
    }

    @Test
    fun `a title containing the separator survives`() {
        val loc = decodeLoc(encodeLoc(description, "0\$1", "Rock | Pop"))

        assertEquals("0\$1", loc?.objectId)
        assertEquals("Rock | Pop", loc?.title)
    }

    @Test
    fun `parses a device description and resolves a relative controlURL`() {
        val device = parseDevice(deviceXml(), description)

        assertEquals("Living Room NAS", device?.name)
        assertEquals("uuid:4d696e69-444c-164e-9d41-b827eb0c1234", device?.udn)
        // Resolved against the description's own URL, and the ConnectionManager
        // service listed before it is not mistaken for the one we want.
        assertEquals("http://10.0.0.9:8200/ctl/ContentDir", device?.control.toString())
    }

    @Test
    fun `URLBase wins over the description URL when present`() {
        val device = parseDevice(deviceXml(base = "http://10.0.0.9:9000/"), description)

        assertEquals("http://10.0.0.9:9000/ctl/ContentDir", device?.control.toString())
    }

    @Test
    fun `a device with no ContentDirectory is not a media server`() {
        assertNull(parseDevice(deviceXml(contentDirectory = false), description))
    }

    private fun deviceXml(base: String? = null, contentDirectory: Boolean = true) = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  ${base?.let { "<URLBase>$it</URLBase>" }.orEmpty()}
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>Living Room NAS</friendlyName>
    <UDN>uuid:4d696e69-444c-164e-9d41-b827eb0c1234</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <controlURL>/ctl/ConnectionMgr</controlURL>
      </service>
      ${if (contentDirectory) {
        """<service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <controlURL>/ctl/ContentDir</controlURL>
      </service>"""
    } else {
        ""
    }}
    </serviceList>
  </device>
</root>""".toByteArray()
}
