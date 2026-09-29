package xyz.utkin.kino

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/** One tag for the whole app, so `adb logcat -s Kino` is the single thing to run. */
const val TAG = "Kino"

private const val DAV = "DAV:"

private val XML = "application/xml; charset=utf-8".toMediaType()

// ponytail: extension sniffing, because servers report getcontenttype wrong far more
// often than people name a file something exotic. Add to the set when bitten.
//
// The audio half is every container media3 1.11.1 ships an extractor for. Two that
// look like they belong are missing on purpose: `.dts`, because there is no raw DTS
// extractor (DTS arrives inside MKV or TS instead), and `.mid`, because the MIDI
// extractor lives in media3-exoplayer-midi, which this app does not depend on.
private val PLAYABLE_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "ts", "m2ts", "webm", "avi", "mov",
    "m4a", "m4b", "mka", "mp3", "flac", "wav", "ogg", "oga", "opus",
    "aac", "ac3", "eac3", "ec3", "ac4", "amr",
)

private const val PROPFIND_BODY =
    """<?xml version="1.0" encoding="utf-8"?>""" +
        """<propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""

/**
 * A row in a listing. [loc] is what following it pushes on the browse stack — the URL
 * itself for WebDAV, and an encoded `(description, ObjectID, title)` for a DLNA
 * container, which is not a URL at all. [url] stays what gets played.
 */
data class Entry(
    val name: String,
    val url: HttpUrl,
    val isDir: Boolean,
    val loc: String = url.toString(),
) {
    val label: String get() = if (isDir) "$name/" else name
}

/** Dirs first, then case-insensitive name — the order both kinds of listing use. */
internal val BROWSE_ORDER: Comparator<Entry> =
    compareByDescending<Entry> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/**
 * One `PROPFIND Depth: 1` against [url]. Directories plus playable files, nothing else.
 *
 * ponytail: no paging — the whole listing is fetched and held. WebDAV has no paging
 * of its own, so this only bites on a directory with thousands of entries; the fix
 * then is a windowed adapter, not a different request.
 */
fun webdavList(client: OkHttpClient, url: HttpUrl, auth: String?): List<Entry> {
    val request = Request.Builder()
        .url(url)
        .apply { if (auth != null) header("Authorization", auth) }
        .header("Depth", "1")
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
        .build()

    Log.i(TAG, "PROPFIND $url")
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("PROPFIND ${url.encodedPath} -> HTTP ${response.code}")
        }
        // Bytes, not a String: let the parser honour the XML prolog's encoding rather
        // than re-encoding whatever charset OkHttp guessed from the Content-Type.
        val body = response.body.bytes()
        val entries = parseMultistatus(body, url)
        Log.i(TAG, "  -> HTTP ${response.code}, ${body.size} bytes, ${entries.size} entries")
        return entries
    }
}

internal fun parseMultistatus(xml: ByteArray, base: HttpUrl): List<Entry> {
    val doc = newDocumentBuilder().parse(xml.inputStream())
    val selfPath = base.encodedPath.trimEnd('/')
    val entries = ArrayList<Entry>()

    val responses = doc.getElementsByTagNameNS(DAV, "response")
    for (i in 0 until responses.length) {
        val response = responses.item(i) as? Element ?: continue
        val href = response.firstText("href") ?: continue
        val resolved = base.resolve(href) ?: continue
        // The directory itself is always in its own Depth:1 listing.
        if (resolved.encodedPath.trimEnd('/') == selfPath) continue

        val isDir = response.getElementsByTagNameNS(DAV, "collection").length > 0
        val name = resolved.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
        if (!isDir && name.substringAfterLast('.', "").lowercase() !in PLAYABLE_EXTENSIONS) continue

        entries.add(Entry(name, resolved, isDir))
    }

    entries.sortWith(BROWSE_ORDER)
    return entries
}

/** Match by namespace + local name: servers use `d:`, `D:`, `lp1:` … interchangeably. */
private fun Element.firstText(localName: String): String? =
    getElementsByTagNameNS(DAV, localName).item(0)?.textContent?.trim()

/** Internal, not private: `Dlna.kt` parses three more documents through the same guard. */
internal fun newDocumentBuilder(): DocumentBuilder =
    DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // This parses a response off the network, so XXE is a real trust boundary.
        // The usual guard is setFeature("…/disallow-doctype-decl", true), but Android's
        // DocumentBuilderFactory is a stub that throws ParserConfigurationException from
        // *every* setFeature call, so that guard cannot be used here. These two are
        // plain setters that exist on both platforms: together they stop external
        // entity fetches and keep entity references from expanding into the tree.
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }
