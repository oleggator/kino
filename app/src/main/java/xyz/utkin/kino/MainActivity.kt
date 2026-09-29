package xyz.utkin.kino

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
// Text, Button, Surface, MaterialTheme and darkColorScheme all exist in BOTH
// androidx.tv.material3 and androidx.compose.material3. The TV ones are imported
// plainly above; these are aliased so the collision cannot go unnoticed. M3Text is
// not optional inside an OutlinedTextField: see the note on its label slots below.
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

const val PREFS = "kino"

// The add/edit flow, as screen names. "" is the server list itself.
private const val ADD = "add"
private const val NEW_DAV = "dav"
private const val NEW_DLNA = "dlna"
private const val SCAN = "scan"
private const val EDIT = "edit:"

private val ACCENT = Color(0xFF3DDC84)
private val BG = Color(0xFF10151C)
private val SURFACE = Color(0xFF161C25)
private val ON_SURFACE = Color(0xFFE8EAED)
private val MUTED = Color(0xFF9AA0A6)

/** TV panels overscan. Google's 10-foot guidance is 48dp × 27dp of safe area. */
private val SAFE_H = 48.dp
private val SAFE_V = 27.dp

private val KinoColors = darkColorScheme(
    primary = ACCENT,
    onPrimary = Color(0xFF00210F),
    background = BG,
    onBackground = ON_SURFACE,
    surface = SURFACE,
    onSurface = ON_SURFACE,
    surfaceVariant = Color(0xFF1E2630),
    onSurfaceVariant = MUTED,
    border = ACCENT,
)

class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = KinoColors) {
                Column(Modifier.fillMaxSize().background(BG)) { App() }
            }
        }
    }

    /**
     * Owns everything the three screens share: which server list or directory we are
     * looking at, what is in it, and whether the settings form is up. No ViewModel —
     * `rememberSaveable` already carries the state that has to survive the activity
     * being recreated.
     */
    @Composable
    private fun App() {
        val context = LocalContext.current
        var servers by remember { mutableStateOf(loadServers(prefs)) }
        // Empty means the server list; anything else is a directory stack under one
        // server. HttpUrl is not Parcelable, so it is saved as strings.
        var stack by rememberSaveable { mutableStateOf(emptyList<String>()) }
        // One string, one screen: "" is the server list, the rest are the add/edit flow.
        // A string because that is what rememberSaveable takes without a custom Saver,
        // which is the same reason the stack above is one.
        var screen by rememberSaveable { mutableStateOf("") }
        // Tagged with the directory it came from. A LaunchedEffect body runs *after*
        // composition, so a plain `entries` list is briefly the previous folder's --
        // clearing it inside the effect was still one frame too late. Comparing the tag
        // makes a stale listing impossible to render, and makes `loading` derivable
        // rather than another piece of state to keep in sync.
        var loaded by remember { mutableStateOf<Pair<String, List<Entry>>?>(null) }

        val scope = rememberCoroutineScope()
        val done = {
            servers = loadServers(prefs)
            screen = ""
        }
        val back = { screen = "" }
        val remove: (HttpUrl) -> Unit = { removeServer(prefs, it); done() }

        when {
            screen == ADD -> {
                Chooser(onPick = { screen = it }, onClose = back)
                return
            }

            screen == NEW_DAV -> {
                DavForm(null, onSave = { saveServer(prefs, null, it); done() }, onRemove = null, onClose = back)
                return
            }

            screen == NEW_DLNA -> {
                DlnaForm(null, onSave = { addDlna(scope, it, was = null, onDone = done) }, onRemove = null, onClose = back)
                return
            }

            screen == SCAN -> {
                ScanScreen(
                    onPick = {
                        saveServer(prefs, null, Server(it.description, "", "", it.udn, it.name))
                        done()
                    },
                    onClose = back,
                )
                return
            }

            screen.startsWith(EDIT) -> {
                val which = screen.removePrefix(EDIT)
                val server = servers.firstOrNull { it.url.toString() == which }
                if (server != null && server.isDlna) {
                    DlnaForm(server, onSave = { addDlna(scope, it, was = server.url, onDone = done) }, onRemove = remove, onClose = back)
                } else {
                    DavForm(server, onSave = { saveServer(prefs, which.toHttpUrlOrNull(), it); done() }, onRemove = remove, onClose = back)
                }
                return
            }
        }

        if (stack.isEmpty()) {
            // The same synthetic-row convention the WebDAV root used to carry: null is
            // the settings row. `remember` keeps the list identity stable, which is
            // what stops Browse recomposing every row on every recomposition here.
            val sources: List<Entry?> = remember(servers) {
                listOf<Entry?>(null) + servers.map {
                    // A WebDAV row leads into its own URL; a DLNA row into container 0,
                    // which is what every ContentDirectory calls its root.
                    val loc = if (it.isDlna) encodeLoc(it.url, "0", it.label) else it.url.toString()
                    Entry(it.label, it.url, isDir = true, loc = loc)
                }
            }
            Browse(
                path = "Servers",
                loading = false,
                rows = sources,
                onPick = { entry -> if (entry == null) screen = ADD else stack = listOf(entry.loc) },
                // Long press is the only affordance a D-pad has left once OK is taken.
                onHold = { entry -> if (entry != null) screen = "$EDIT${entry.url}" },
            )
            return
        }

        val current = stack.last()
        // From the loaded list rather than prefs: one source of truth, and no disk-backed
        // read on every recomposition. Null stays null all the way to the request --
        // a URL under no configured server is sent no Authorization header at all.
        val auth = basicAuthFor(servers, current)
        val entries = loaded?.takeIf { it.first == current }?.second
        LaunchedEffect(current, auth) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // The one place the two protocols part company. Everything either
                    // side of it -- focus, staleness, the stack, the player -- is shared.
                    val dlna = decodeLoc(current)
                    if (dlna != null) dlnaBrowse(client, prefs, dlna) else webdavList(client, current.toHttpUrl(), auth)
                }
            }
            result
                .onSuccess { loaded = current to it }
                .onFailure {
                    // Surfacing this matters: a wrong path or a 401 is the most likely
                    // first-run problem and it is otherwise invisible.
                    Log.w(TAG, "listing failed for $current", it)
                    Toast.makeText(context, it.message ?: "Listing failed", Toast.LENGTH_LONG).show()
                    loaded = current to emptyList()
                }
        }

        // Back out of the last directory lands on the server list; back from there is
        // left to the system, so it leaves the app.
        BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

        // `remember` is load-bearing, not tidiness. List is an unstable type to Compose,
        // and strong skipping compares unstable parameters by identity — so a freshly
        // allocated list here made Browse, and every row in it, recompose on every
        // single recomposition of App. That is the flashing.
        val rows: List<Entry?> = remember(entries) { entries.orEmpty() }

        Browse(
            // Host included: with more than one server the path alone is ambiguous.
            path = decodeLoc(current)?.title
                ?: current.toHttpUrlOrNull()?.let { "${it.host}${it.displayPath()}" }.orEmpty(),
            loading = entries == null,
            rows = rows,
            onPick = { entry ->
                when {
                    entry == null -> Unit
                    entry.isDir -> stack = stack + entry.loc
                    else -> {
                        Log.i(TAG, "opening ${entry.url}")
                        startActivity(
                            Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_URL, entry.url.toString()),
                        )
                    }
                }
            },
        )
    }

    /**
     * Resolving a typed address is network work — a description fetch, and a unicast
     * search first when only a host was given — so it cannot happen in the click
     * handler. [was] is the address being replaced when an existing server is edited.
     */
    private fun addDlna(scope: CoroutineScope, address: String, was: HttpUrl?, onDone: () -> Unit) {
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { resolveDlna(client, address) } }
            found
                .onSuccess {
                    saveServer(prefs, was, Server(it.description, "", "", it.udn, it.name))
                    onDone()
                }
                .onFailure {
                    Log.w(TAG, "could not resolve $address", it)
                    Toast.makeText(this@MainActivity, it.message ?: "Could not reach it", Toast.LENGTH_LONG).show()
                }
        }
    }

    /**
     * Two ways in, because discovery is multicast with TTL 1: a server on another
     * subnet, behind a router or across a VPN never answers a scan, however well the
     * scan works. Typing its address is the only way to reach it, and once it is stored
     * everything else — browsing, playback — works the same, since a `res` URL is
     * ordinary routable HTTP.
     */
    @Composable
    private fun Chooser(onPick: (String) -> Unit, onClose: () -> Unit) {
        val first = remember { FocusRequester() }
        BackHandler { onClose() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        Column(
            Modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Add server", fontSize = 32.sp, fontWeight = FontWeight.Light, color = ON_SURFACE)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onPick(NEW_DAV) }, modifier = Modifier.focusRequester(first)) {
                Text("WebDAV URL")
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onPick(SCAN) }) { Text("Scan for DLNA") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onPick(NEW_DLNA) }) { Text("DLNA by address") }
            Spacer(Modifier.height(24.dp))
            Text(
                "A scan only reaches this network. Anything behind a router has to be typed.",
                fontSize = 14.sp,
                color = MUTED,
            )
        }
    }

    /** Discovered servers, rendered through [Browse] so the D-pad behaves as everywhere else. */
    @Composable
    private fun ScanScreen(onPick: (Device) -> Unit, onClose: () -> Unit) {
        var found by remember { mutableStateOf<List<Device>?>(null) }
        BackHandler { onClose() }
        LaunchedEffect(Unit) {
            found = withContext(Dispatchers.IO) {
                search().mapNotNull { runCatching { describe(client, it) }.getOrNull() }
            }
        }

        val rows: List<Entry?> = remember(found) {
            found.orEmpty().map { Entry(it.name, it.description, isDir = true) }
        }
        Browse(
            path = if (found?.isEmpty() == true) "Nothing found" else "Found",
            loading = found == null,
            rows = rows,
            onPick = { entry -> found?.firstOrNull { it.description == entry?.url }?.let(onPick) },
        )
    }

    /**
     * The chrome every form shares. `OutlinedTextField` reads androidx.compose.material3's
     * MaterialTheme, not the TV one, so without this nesting the fields render in
     * material3's stock palette.
     */
    @Composable
    private fun FormFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
        M3Theme(
            colorScheme = m3DarkColorScheme(
                primary = ACCENT,
                background = BG,
                surface = SURFACE,
                onSurface = ON_SURFACE,
                onSurfaceVariant = MUTED,
                outline = MUTED,
            ),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, fontSize = 32.sp, fontWeight = FontWeight.Light, color = ON_SURFACE)
                Spacer(Modifier.height(24.dp))
                content()
            }
        }
    }

    /**
     * Adds a WebDAV server when [server] is null, edits it otherwise. The fields are
     * keyed on which server is being edited, so opening the form on a different one
     * refills it rather than showing the last one's values.
     */
    @Composable
    private fun DavForm(
        server: Server?,
        onSave: (Server) -> Unit,
        onRemove: ((HttpUrl) -> Unit)?,
        onClose: () -> Unit,
    ) {
        val context = LocalContext.current
        val key = server?.url?.toString()
        var url by rememberSaveable(key) { mutableStateOf(key.orEmpty()) }
        var user by rememberSaveable(key) { mutableStateOf(server?.user.orEmpty()) }
        var pass by rememberSaveable(key) { mutableStateOf(server?.pass.orEmpty()) }
        val firstField = remember { FocusRequester() }

        // There is always a server list behind the form, so Back always has somewhere
        // to go — unlike the old fresh-install case, where dismissing led nowhere and
        // Back had to fall through to the system instead.
        BackHandler { onClose() }
        LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

        FormFrame(if (server == null) "Add WebDAV server" else "Edit server") {
            // The label and placeholder slots below take M3Text, not tv-material's Text.
            // OutlinedTextField colours its slots through compose-material3's
            // LocalContentColor; tv-material's Text reads tv-material's, a different
            // CompositionLocal, whose default is Color.Black — invisible here.
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { M3Text("WebDAV URL") },
                placeholder = { M3Text("https://host/remote.php/dav/files/me/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(firstField),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { M3Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { M3Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Leave blank if the server needs no credentials.", fontSize = 14.sp, color = MUTED)
            Spacer(Modifier.height(24.dp))
            Buttons(
                onSave = {
                    val parsed = url.trim()
                        .let { if (it.endsWith("/")) it else "$it/" }
                        .toHttpUrlOrNull()
                    if (parsed == null) {
                        Toast.makeText(context, "That URL doesn't parse", Toast.LENGTH_LONG).show()
                    } else {
                        onSave(Server(parsed, user, pass))
                    }
                },
                onClose = onClose,
                onRemove = server?.url?.let { url -> onRemove?.let { { it(url) } } },
            )
        }
    }

    /**
     * A DLNA server has no credentials to fill in — only where it lives. The address
     * stays editable when editing an existing one on purpose: that is the only repair
     * available when a routed server moves and no rescan can find it.
     */
    @Composable
    private fun DlnaForm(
        server: Server?,
        onSave: (String) -> Unit,
        onRemove: ((HttpUrl) -> Unit)?,
        onClose: () -> Unit,
    ) {
        val key = server?.url?.toString()
        var address by rememberSaveable(key) { mutableStateOf(key.orEmpty()) }
        val firstField = remember { FocusRequester() }

        BackHandler { onClose() }
        LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

        FormFrame(server?.label ?: "DLNA by address") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { M3Text("Description URL, or just the host") },
                placeholder = { M3Text("http://10.0.0.9:8200/rootDesc.xml") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(firstField),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "A bare host or IP is asked directly where its description lives.",
                fontSize = 14.sp,
                color = MUTED,
            )
            Spacer(Modifier.height(24.dp))
            Buttons(
                onSave = { onSave(address) },
                onClose = onClose,
                onRemove = server?.url?.let { url -> onRemove?.let { { it(url) } } },
            )
        }
    }

    /** Save / Cancel, and Remove only where there is something to remove. */
    @Composable
    private fun Buttons(onSave: () -> Unit, onClose: () -> Unit, onRemove: (() -> Unit)?) {
        Row {
            Button(onClick = onSave) { Text("Save") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClose) { Text("Cancel") }
            if (onRemove != null) {
                Spacer(Modifier.width(12.dp))
                // Takes its credentials with it; the resume positions stay.
                Button(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

/** `pathSegments` is decoded, `encodedPath` is not — a space should read as a space. */
private fun HttpUrl.displayPath() =
    pathSegments.filter { it.isNotEmpty() }.joinToString("/", prefix = "/")

/**
 * The browse list. `ListItem` brings its own focus scale, border and glow, which is the
 * whole reason for being on tv-material — never `Modifier.clickable` here, it gives no
 * focus state at all on a D-pad.
 */
@Composable
private fun Browse(
    path: String,
    loading: Boolean,
    rows: List<Entry?>,
    onPick: (Entry?) -> Unit,
    onHold: ((Entry?) -> Unit)? = null,
) {
    val first = remember { FocusRequester() }

    // Nothing is focused by default in Compose, which on a TV means the D-pad is dead.
    // Re-fired per directory; requestFocus throws if the node is not attached yet.
    LaunchedEffect(path, rows.size) {
        if (rows.isNotEmpty()) runCatching { first.requestFocus() }
    }

    // A PROPFIND on a LAN usually answers in well under 100ms, and an indicator that
    // appears for three frames is worse than none. Only show one if the load is still
    // running after this long.
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        showLoading = false
        if (loading) {
            delay(250)
            showLoading = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = SAFE_H, vertical = SAFE_V)) {
        Text("KINO", color = ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp)
        Text(
            path,
            color = ON_SURFACE,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // The height is reserved whether or not the bar is showing, so the list does
        // not jump down a few pixels every time a directory finishes loading.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (showLoading) {
                // tv-material has no progress component. This is compose-material3's,
                // and it reads its default colours from a theme we are not inside, so
                // they are passed explicitly rather than nesting a theme for one widget.
                LinearProgressIndicator(
                    color = ACCENT,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // A new directory gets a new LazyColumn instead of recycling the old one. Reuse
        // meant the new listing inherited the previous scroll offset and re-used each
        // row's composition, so entering a folder animated rows into their replacements
        // and then scrolled to the top — the "strange transition".
        key(path) {
            LazyColumn(
                // Keeps the focused row clear of the screen edge. LocalBringIntoViewSpec,
                // the old pivot hook, was removed in foundation 1.12.1.
                contentPadding = PaddingValues(vertical = 28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    rows,
                    key = { _, entry -> entry?.url?.toString() ?: "add" },
                ) { index, entry ->
                    ListItem(
                        selected = false,
                        onClick = { onPick(entry) },
                        onLongClick = onHold?.let { hold -> { hold(entry) } },
                        headlineContent = {
                            // The null row only ever appears on the server list now,
                            // where it adds one -- directory listings carry no synthetic
                            // rows since the settings row moved off the WebDAV root.
                            Text(entry?.label ?: "Add server", fontSize = 19.sp)
                        },
                        leadingContent = {
                            Text(
                                when {
                                    // A gear would read as settings; this row adds a
                                    // server, and on a fresh install it is the only
                                    // row on the screen, so it carries the accent too.
                                    entry == null -> "+"
                                    entry.isDir -> "▣"
                                    else -> "▶"
                                },
                                color = ACCENT,
                                fontSize = 17.sp,
                            )
                        },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}
