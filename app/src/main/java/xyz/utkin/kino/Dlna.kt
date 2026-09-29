package xyz.utkin.kino

import android.content.SharedPreferences
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

private const val SSDP_HOST = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
private const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

private val SOAP = "text/xml; charset=utf-8".toMediaType()

/** Marks a browse-stack entry as a DLNA container rather than a URL. */
private const val DIDL = "didl|"

/** Servers cap what they will return; this is a page size they all cope with. */
private const val PAGE = 200

/** A DLNA media server, as its own description document describes it. */
data class Device(val description: HttpUrl, val udn: String, val name: String, val control: HttpUrl)

/**
 * Where a DLNA listing lives. Keyed by *description* URL rather than control URL: the
 * control URL is the device's own business and moves when it feels like it, while the
 * description URL is the address a person can read, type and correct. The title rides
 * along because an ObjectID is a server's private numbering and means nothing on screen.
 */
internal data class DlnaLoc(val description: HttpUrl, val objectId: String, val title: String)

internal fun encodeLoc(description: HttpUrl, objectId: String, title: String) =
    "$DIDL$description|$objectId|$title"

internal fun decodeLoc(loc: String): DlnaLoc? {
    if (!loc.startsWith(DIDL)) return null
    val parts = loc.removePrefix(DIDL).split("|", limit = 3)
    if (parts.size < 3) return null
    return DlnaLoc(parts[0].toHttpUrlOrNull() ?: return null, parts[1], parts[2])
}

/**
 * Description URL -> ContentDirectory control URL, resolved once per app run. One extra
 * GET per server per launch, rather than one per directory.
 */
private val controls = ConcurrentHashMap<HttpUrl, HttpUrl>()

/**
 * SSDP M-SEARCH, returning the `LOCATION` of every MediaServer that answers. Multicast
 * when [to] is null; unicast to that host when it is not, which is the only way to
 * reach a server with a router in the way — multicast is TTL 1 and never crosses one.
 *
 * Replies come back unicast to this socket's ephemeral port, so no `MulticastLock` and
 * no `CHANGE_WIFI_MULTICAST_STATE` permission are needed. UDP drops datagrams and
 * nothing retries, hence sending the search more than once.
 */
fun search(to: InetAddress? = null, timeoutMs: Long = 3_000): List<HttpUrl> {
    val target = to ?: InetAddress.getByName(SSDP_HOST)
    val message = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $MEDIA_SERVER\r\n\r\n"
        ).toByteArray()

    // Keyed by USN so a device that answers every send is listed once.
    val found = LinkedHashMap<String, HttpUrl>()
    val deadline = System.currentTimeMillis() + timeoutMs
    val buffer = ByteArray(4096)
    DatagramSocket().use { socket ->
        socket.soTimeout = 300
        var sends = 0
        while (System.currentTimeMillis() < deadline) {
            if (sends < 3) {
                socket.send(DatagramPacket(message, message.size, target, SSDP_PORT))
                sends++
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val reply = String(packet.data, 0, packet.length)
            val usn = header(reply, "USN") ?: continue
            val location = header(reply, "LOCATION")?.toHttpUrlOrNull() ?: continue
            if (usn !in found) found[usn] = location
        }
    }
    Log.i(TAG, "SSDP ${to ?: "multicast"} -> ${found.values}")
    return found.values.toList()
}

/**
 * An address a person typed: either a device description URL, or a bare `host[:port]`,
 * in which case the device is asked directly where its description lives.
 */
fun resolveDlna(client: OkHttpClient, address: String): Device {
    val typed = address.trim()
    val url = (if ("://" in typed) typed else "http://$typed").toHttpUrlOrNull()
        ?: throw IOException("$typed is not an address")
    // A path means they gave us the description itself; a bare host means they did not.
    if (url.encodedPath.length > 1) {
        return describe(client, url) ?: throw IOException("No ContentDirectory at $url")
    }
    for (location in search(InetAddress.getByName(url.host))) {
        describe(client, location)?.let { return it }
    }
    throw IOException("${url.host} did not answer — paste its description URL instead")
}

/** Fetches a device description and reads what this app needs out of it. */
fun describe(client: OkHttpClient, description: HttpUrl): Device? {
    Log.i(TAG, "GET $description")
    client.newCall(Request.Builder().url(description).build()).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("GET ${description.encodedPath} -> HTTP ${response.code}")
        }
        return parseDevice(response.body.bytes(), description)
    }
}

/**
 * One `Browse` of one container, paged until the server stops giving. [prefs] is here
 * for the repair path: a server that moved is found again by UDN and its stored address
 * rewritten, which keeps a row working across the restart that randomised its port.
 */
internal fun dlnaBrowse(client: OkHttpClient, prefs: SharedPreferences, loc: DlnaLoc): List<Entry> {
    val description = runCatching { controlFor(client, loc.description) }
        .fold(onSuccess = { loc.description }, onFailure = { heal(client, prefs, loc.description) ?: throw it })
    val control = controlFor(client, description)

    val entries = ArrayList<Entry>()
    var start = 0
    while (true) {
        val page = client.newCall(
            Request.Builder()
                .url(control)
                .header("SOAPAction", "\"$CONTENT_DIRECTORY#Browse\"")
                .post(browseEnvelope(loc.objectId, start).toRequestBody(SOAP))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Browse ${loc.objectId} -> HTTP ${response.code}")
            }
            readBrowseResponse(response.body.bytes())
        }
        entries += parseDidl(page.didl, description)
        start += page.returned
        // `returned == 0` is the real terminator: TotalMatches is advisory and some
        // servers report it as zero while still handing over rows.
        if (page.returned == 0 || start >= page.total) break
    }
    Log.i(TAG, "Browse ${loc.objectId} on $description -> ${entries.size} entries")
    return entries.sortedWith(BROWSE_ORDER)
}

/**
 * DIDL-Lite: `<container>` is a folder, `<item>` is a file. `upnp:class` is the server
 * saying what it holds, which beats the extension sniffing [parseMultistatus] must do.
 */
internal fun parseDidl(xml: String, description: HttpUrl): List<Entry> {
    if (xml.isBlank()) return emptyList()
    val root = newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        ?: return emptyList()
    val entries = ArrayList<Entry>()
    val nodes = root.childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i) as? Element ?: continue
        val title = node.text("title")?.takeIf { it.isNotBlank() } ?: continue
        when (node.localName ?: node.nodeName) {
            "container" -> entries += Entry(
                title,
                description,
                isDir = true,
                loc = encodeLoc(description, node.getAttribute("id"), title),
            )

            "item" -> {
                val kind = node.text("class").orEmpty()
                if ("videoItem" !in kind && "audioItem" !in kind) continue
                val res = pickRes(node) ?: continue
                entries += Entry(title, res, isDir = false)
            }
        }
    }
    return entries
}

/**
 * friendlyName, UDN and the ContentDirectory control URL. URLs inside a description may
 * be relative — to `<URLBase>` when there is one, and to the description's own address
 * otherwise. Elements are matched on local name against any namespace, because servers
 * differ on whether they prefix them.
 */
internal fun parseDevice(xml: ByteArray, description: HttpUrl): Device? {
    val root = newDocumentBuilder().parse(xml.inputStream()).documentElement ?: return null
    val base = root.text("URLBase")?.toHttpUrlOrNull() ?: description
    val services = root.getElementsByTagNameNS("*", "service")
    for (i in 0 until services.length) {
        val service = services.item(i) as? Element ?: continue
        if (service.text("serviceType") != CONTENT_DIRECTORY) continue
        val control = service.text("controlURL")?.let(base::resolve) ?: continue
        return Device(
            description = description,
            udn = root.text("UDN").orEmpty(),
            name = root.text("friendlyName")?.takeIf { it.isNotBlank() } ?: description.host,
            control = control,
        )
    }
    return null
}

private class Page(val didl: String, val returned: Int, val total: Int)

private fun readBrowseResponse(xml: ByteArray): Page {
    val root = newDocumentBuilder().parse(xml.inputStream()).documentElement
        ?: throw IOException("empty Browse response")
    // <Result> carries the DIDL-Lite document as *text*. textContent unescapes it and
    // that string is parsed as a second document; walking it as child nodes of this one
    // is the classic dead end here.
    return Page(
        didl = root.text("Result").orEmpty(),
        returned = root.text("NumberReturned")?.toIntOrNull() ?: 0,
        total = root.text("TotalMatches")?.toIntOrNull() ?: 0,
    )
}

private fun browseEnvelope(objectId: String, start: Int) =
    """<?xml version="1.0" encoding="utf-8"?>""" +
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
        """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
        """<u:Browse xmlns:u="$CONTENT_DIRECTORY">""" +
        """<ObjectID>${escape(objectId)}</ObjectID>""" +
        """<BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>""" +
        """<StartingIndex>$start</StartingIndex><RequestedCount>$PAGE</RequestedCount>""" +
        """<SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""

/** Real ObjectIDs contain `&` — `0$1$8`, `64$@30489&x` — which a raw splice turns into a 500. */
private fun escape(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * An item may carry several `<res>`. Prefer an `http-get` one that is not a transcode:
 * `DLNA.ORG_CI=1` means the server converted it, and Direct Play is the point here.
 */
private fun pickRes(item: Element): HttpUrl? {
    val nodes = item.getElementsByTagNameNS("*", "res")
    var transcode: HttpUrl? = null
    for (i in 0 until nodes.length) {
        val res = nodes.item(i) as? Element ?: continue
        val protocol = res.getAttribute("protocolInfo")
        if (!protocol.startsWith("http-get:")) continue
        val url = res.textContent?.trim()?.toHttpUrlOrNull() ?: continue
        if ("DLNA.ORG_CI=1" !in protocol) return url
        if (transcode == null) transcode = url
    }
    return transcode
}

private fun controlFor(client: OkHttpClient, description: HttpUrl): HttpUrl {
    controls[description]?.let { return it }
    val device = describe(client, description) ?: throw IOException("No ContentDirectory at $description")
    controls[description] = device.control
    return device.control
}

/**
 * A server whose address stopped working: search again, match the same device by UDN
 * and rewrite what is stored, so a restart that randomised its port costs nothing. Only
 * reaches the local subnet — a routed server is repaired by editing its address.
 */
private fun heal(client: OkHttpClient, prefs: SharedPreferences, description: HttpUrl): HttpUrl? {
    val was = loadServers(prefs).firstOrNull { it.url == description } ?: return null
    val udn = was.udn?.takeIf { it.isNotBlank() } ?: return null
    val device = search().asSequence()
        .mapNotNull { runCatching { describe(client, it) }.getOrNull() }
        .firstOrNull { it.udn == udn }
        ?: return null
    Log.i(TAG, "healed $description -> ${device.description}")
    saveServer(prefs, description, was.copy(url = device.description, name = device.name))
    controls[device.description] = device.control
    return device.description
}

/** SSDP replies are HTTP-shaped but are not HTTP; header names vary in case. */
private fun header(message: String, name: String) = message.lineSequence()
    .map(String::trim)
    .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
    ?.substringAfter(':')
    ?.trim()

/** Local name against any namespace: servers differ on whether they prefix elements. */
private fun Element.text(name: String): String? =
    getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()
