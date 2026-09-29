package xyz.utkin.kino

import android.content.SharedPreferences
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val SERVERS = "servers"

private fun userKey(url: HttpUrl) = "user:$url"

private fun passKey(url: HttpUrl) = "pass:$url"

/**
 * A configured source. [url] is a WebDAV root, or a DLNA device description URL when
 * [udn] is set — the description rather than the ContentDirectory control URL, because
 * that one is the device's own business and moves when it feels like it.
 */
data class Server(
    val url: HttpUrl,
    val user: String,
    val pass: String,
    /** Non-null means DLNA. Kept so a server that moved can be found again by identity. */
    val udn: String? = null,
    /** The device's friendlyName; WebDAV servers have none and fall back to the host. */
    val name: String? = null,
) {
    val isDlna: Boolean get() = udn != null

    /**
     * A DLNA device names itself; a WebDAV server is labelled by host, with the port
     * shown only when it is not the scheme's. Two WebDAV roots on one host read the
     * same, which was the accepted cost of having nothing to type when adding one.
     */
    val label: String
        get() = name
            ?: if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
}

/**
 * One server per line. A WebDAV root is written as the bare URL it always was, so
 * everything already stored keeps working; a DLNA server adds a marker and the two
 * fields discovery gives us:
 *
 *     https://nas.lan/dav/
 *     dlna|http://10.0.0.9:8200/rootDesc.xml|uuid:4d69…|Living Room NAS
 *
 * Newline-separated because a URL cannot contain one, so nothing needs escaping — and
 * not JSON, because `org.json` is an `android.jar` stub in plain JVM unit tests: with
 * `returnDefaultValues` it answers null instead of throwing, so a JSON codec would fail
 * silently in exactly the tests meant to pin it. Credentials live in their own per-URL
 * keys, so a password may contain anything at all without meeting this.
 */
internal fun encodeServers(servers: List<Server>) = servers.joinToString("\n") {
    if (it.isDlna) "$DLNA${it.url}|${it.udn}|${it.name}" else it.url.toString()
}

internal fun decodeServers(raw: String?): List<Server> =
    raw.orEmpty().lineSequence().mapNotNull(::decodeServer).toList()

private const val DLNA = "dlna|"

private fun decodeServer(line: String): Server? {
    val text = line.trim()
    if (!text.startsWith(DLNA)) {
        return text.toHttpUrlOrNull()?.let { Server(it, "", "") }
    }
    val parts = text.removePrefix(DLNA).split("|", limit = 3)
    if (parts.size < 3) return null
    val url = parts[0].toHttpUrlOrNull() ?: return null
    return Server(url, "", "", udn = parts[1], name = parts[2])
}

/**
 * The configured root a URL belongs to, or null when it belongs to none. Longest match
 * wins, so a server nested under another's path is found before its parent. Roots
 * always end in `/` — the settings form appends one — which is what stops
 * `https://a/dav2/` reading as something under `https://a/dav/`.
 */
internal fun ownerOf(servers: List<HttpUrl>, target: String): HttpUrl? =
    servers.filter { target.startsWith(it.toString()) }.maxByOrNull { it.toString().length }

/** Credentials are stored per URL, so they are attached here rather than in the codec. */
fun loadServers(prefs: SharedPreferences): List<Server> =
    decodeServers(prefs.getString(SERVERS, null)).map {
        if (it.isDlna) {
            it
        } else {
            it.copy(
                user = prefs.getString(userKey(it.url), "").orEmpty(),
                pass = prefs.getString(passKey(it.url), "").orEmpty(),
            )
        }
    }

/** Adds or updates. [was] is the URL before an edit, so a changed URL moves its keys. */
fun saveServer(prefs: SharedPreferences, was: HttpUrl?, now: Server) {
    val servers = decodeServers(prefs.getString(SERVERS, null)).toMutableList()
    val at = servers.indexOfFirst { it.url == was || it.url == now.url }
    if (at >= 0) servers[at] = now else servers.add(now)
    val edit = prefs.edit()
        // distinctBy because editing one server's URL into another's would otherwise
        // leave the same address listed twice.
        .putString(SERVERS, encodeServers(servers.distinctBy(Server::url)))
        .putString(userKey(now.url), now.user)
        .putString(passKey(now.url), now.pass)
    // An edit that moved the server would otherwise leave its password behind.
    if (was != null && was != now.url) edit.remove(userKey(was)).remove(passKey(was))
    edit.apply()
}

fun removeServer(prefs: SharedPreferences, url: HttpUrl) {
    val servers = decodeServers(prefs.getString(SERVERS, null)).filterNot { it.url == url }
    // Credentials go; resume positions stay. They are keyed by media URL, so re-adding
    // the server picks up where you left off -- and pruning them here is what would
    // silently wipe them when someone merely corrects a typo in a server's URL.
    prefs.edit()
        .putString(SERVERS, encodeServers(servers))
        .remove(userKey(url))
        .remove(passKey(url))
        .apply()
}

/**
 * Credentials for a media URL, or null when no configured server owns it. Null means no
 * `Authorization` header at all: a URL this app was not pointed at is not one its
 * password is for. That is the only thing standing between a server-supplied href and
 * the credentials of an unrelated host.
 */
internal fun basicAuthFor(servers: List<Server>, mediaUrl: String): String? {
    // DLNA servers are anonymous, and their res URLs must never be handed a password
    // meant for something else.
    val roots = servers.filterNot(Server::isDlna).map(Server::url)
    val owner = ownerOf(roots, mediaUrl) ?: return null
    val server = servers.first { it.url == owner }
    return Credentials.basic(server.user, server.pass)
}

/** The same, for a caller that holds prefs rather than an already-loaded list. */
fun basicAuthFor(prefs: SharedPreferences, mediaUrl: String) =
    basicAuthFor(loadServers(prefs), mediaUrl)
