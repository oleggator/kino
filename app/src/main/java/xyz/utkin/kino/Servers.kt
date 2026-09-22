package xyz.utkin.kino

import android.content.SharedPreferences
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val SERVERS = "servers"

private fun userKey(url: HttpUrl) = "user:$url"

private fun passKey(url: HttpUrl) = "pass:$url"

/** A configured WebDAV share: where it is, and what gets sent to reach it. */
data class Server(val url: HttpUrl, val user: String, val pass: String) {
    /** Rows are labelled by host; the port shows only when it is not the scheme's. */
    val label: String
        get() = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
}

/**
 * Server roots joined by newlines. A URL cannot contain one, so nothing needs escaping
 * — and not JSON, because `org.json` is an `android.jar` stub in plain JVM unit tests:
 * with `returnDefaultValues` it answers null instead of throwing, so a JSON codec would
 * fail silently in exactly the tests meant to pin it. Credentials live in their own
 * per-URL keys, so a password may contain anything at all without meeting this.
 */
internal fun encodeServers(urls: List<HttpUrl>) = urls.joinToString("\n")

internal fun decodeServers(raw: String?): List<HttpUrl> =
    raw.orEmpty().lineSequence().mapNotNull { it.trim().toHttpUrlOrNull() }.toList()

/**
 * The configured root a URL belongs to, or null when it belongs to none. Longest match
 * wins, so a server nested under another's path is found before its parent. Roots
 * always end in `/` — the settings form appends one — which is what stops
 * `https://a/dav2/` reading as something under `https://a/dav/`.
 */
internal fun ownerOf(servers: List<HttpUrl>, target: String): HttpUrl? =
    servers.filter { target.startsWith(it.toString()) }.maxByOrNull { it.toString().length }

fun loadServers(prefs: SharedPreferences): List<Server> =
    decodeServers(prefs.getString(SERVERS, null)).map {
        Server(
            it,
            prefs.getString(userKey(it), "").orEmpty(),
            prefs.getString(passKey(it), "").orEmpty(),
        )
    }

/** Adds or updates. [was] is the URL before an edit, so a changed URL moves its keys. */
fun saveServer(prefs: SharedPreferences, was: HttpUrl?, now: Server) {
    val urls = decodeServers(prefs.getString(SERVERS, null)).toMutableList()
    val at = urls.indexOfFirst { it == was || it == now.url }
    if (at >= 0) urls[at] = now.url else urls.add(now.url)
    val edit = prefs.edit()
        // distinct because editing one server's URL into another's would otherwise
        // leave the same root listed twice.
        .putString(SERVERS, encodeServers(urls.distinct()))
        .putString(userKey(now.url), now.user)
        .putString(passKey(now.url), now.pass)
    // An edit that moved the server would otherwise leave its password behind.
    if (was != null && was != now.url) edit.remove(userKey(was)).remove(passKey(was))
    edit.apply()
}

fun removeServer(prefs: SharedPreferences, url: HttpUrl) {
    val urls = decodeServers(prefs.getString(SERVERS, null)).filterNot { it == url }
    // Credentials go; resume positions stay. They are keyed by media URL, so re-adding
    // the server picks up where you left off -- and pruning them here is what would
    // silently wipe them when someone merely corrects a typo in a server's URL.
    prefs.edit()
        .putString(SERVERS, encodeServers(urls))
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
fun basicAuthFor(prefs: SharedPreferences, mediaUrl: String): String? {
    val servers = loadServers(prefs)
    val owner = ownerOf(servers.map(Server::url), mediaUrl) ?: return null
    val server = servers.first { it.url == owner }
    return Credentials.basic(server.user, server.pass)
}
