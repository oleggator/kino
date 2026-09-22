package xyz.utkin.kino

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServersTest {

    private val nas = "https://nas.lan/dav/".toHttpUrl()
    private val pi = "http://pi.lan:8080/media/".toHttpUrl()

    @Test
    fun `round trips a list of roots`() {
        assertEquals(listOf(nas, pi), decodeServers(encodeServers(listOf(nas, pi))))
    }

    @Test
    fun `survives nothing stored, blank, and a line that does not parse`() {
        assertEquals(emptyList<Any>(), decodeServers(null))
        assertEquals(emptyList<Any>(), decodeServers(""))
        assertEquals(listOf(nas), decodeServers("not a url\n$nas\n"))
    }

    /**
     * The one that matters: a URL is only ever handed the credentials of the server it
     * actually lives under, and a host nobody configured gets none at all.
     */
    @Test
    fun `ownerOf matches by root, not by host`() {
        val servers = listOf(nas, pi)

        assertEquals(nas, ownerOf(servers, "https://nas.lan/dav/Shows/Ep.mkv"))
        assertEquals(nas, ownerOf(servers, nas.toString()))
        assertEquals(pi, ownerOf(servers, "http://pi.lan:8080/media/x.mkv"))
        // Same host, outside the configured root.
        assertNull(ownerOf(servers, "https://nas.lan/private/x.mkv"))
        // A neighbour whose path merely starts with the same letters.
        assertNull(ownerOf(servers, "https://nas.lan/dav2/x.mkv"))
        assertNull(ownerOf(servers, "https://evil.example/dav/x.mkv"))
        assertNull(ownerOf(emptyList(), "https://nas.lan/dav/x.mkv"))
    }

    @Test
    fun `ownerOf prefers the longest root when one nests inside another`() {
        val inner = "https://nas.lan/dav/shows/".toHttpUrl()

        assertEquals(inner, ownerOf(listOf(nas, inner), "https://nas.lan/dav/shows/Ep.mkv"))
        assertEquals(inner, ownerOf(listOf(inner, nas), "https://nas.lan/dav/shows/Ep.mkv"))
        assertEquals(nas, ownerOf(listOf(nas, inner), "https://nas.lan/dav/films/Ep.mkv"))
    }

    /** The boundary itself: a URL nobody configured is sent no credentials. */
    @Test
    fun `credentials only ever reach the server they belong to`() {
        val servers = listOf(Server(nas, "me", "sekrit"), Server(pi, "pi", "raspberry"))

        assertEquals(basicAuthFor(servers, "https://nas.lan/dav/Ep.mkv"), basicAuthFor(servers, nas.toString()))
        assertNotEquals(
            basicAuthFor(servers, "https://nas.lan/dav/Ep.mkv"),
            basicAuthFor(servers, "http://pi.lan:8080/media/Ep.mkv"),
        )
        assertNull(basicAuthFor(servers, "https://evil.example/dav/Ep.mkv"))
        assertNull(basicAuthFor(servers, "https://nas.lan/private/Ep.mkv"))
        assertNull(basicAuthFor(emptyList(), "https://nas.lan/dav/Ep.mkv"))
    }

    @Test
    fun `labels by host, and keeps the port only when it is not the default`() {
        assertEquals("nas.lan", Server(nas, "", "").label)
        assertEquals("pi.lan:8080", Server(pi, "", "").label)
        assertEquals("nas.lan", Server("http://nas.lan/".toHttpUrl(), "", "").label)
    }
}
