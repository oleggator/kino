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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
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
     * Owns everything the two screens share: where we are, what is in it, and whether
     * the settings form is up. No ViewModel — `rememberSaveable` already carries the
     * one piece of state that has to survive the activity being recreated.
     */
    @Composable
    private fun App() {
        val context = LocalContext.current
        // ponytail: one server, one root. A second would need a picker screen and a
        // key scheme for the resume positions; add both if there is ever a second.
        // HttpUrl is not Parcelable, so the stack is saved as strings.
        var stack by rememberSaveable {
            mutableStateOf(listOfNotNull(prefs.getString("url", null)))
        }
        var showSettings by rememberSaveable { mutableStateOf(stack.isEmpty()) }
        // Tagged with the directory it came from. A LaunchedEffect body runs *after*
        // composition, so a plain `entries` list is briefly the previous folder's --
        // clearing it inside the effect was still one frame too late. Comparing the tag
        // makes a stale listing impossible to render, and makes `loading` derivable
        // rather than another piece of state to keep in sync.
        var loaded by remember { mutableStateOf<Pair<String, List<Entry>>?>(null) }

        if (showSettings) {
            SettingsScreen(
                onCancel = { if (stack.isNotEmpty()) showSettings = false },
                onSave = { saved ->
                    stack = listOf(saved.toString())
                    showSettings = false
                },
            )
            return
        }

        val current = stack.last()
        val auth = basicAuth()
        val entries = loaded?.takeIf { it.first == current }?.second
        LaunchedEffect(current, auth) {
            val url = current.toHttpUrlOrNull() ?: return@LaunchedEffect
            val result = withContext(Dispatchers.IO) { runCatching { webdavList(client, url, auth) } }
            result
                .onSuccess { loaded = current to it }
                .onFailure {
                    // Surfacing this matters: a wrong path or a 401 is the most likely
                    // first-run problem and it is otherwise invisible.
                    Log.w(TAG, "listing failed for $url", it)
                    Toast.makeText(context, it.message ?: "Listing failed", Toast.LENGTH_LONG).show()
                    loaded = current to emptyList()
                }
        }

        BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

        // The settings row lives at the root only, so there is always a way back into
        // the form even when the listing is empty or the credentials are wrong.
        // `remember` is load-bearing, not tidiness. List is an unstable type to Compose,
        // and strong skipping compares unstable parameters by identity — so a freshly
        // allocated list here made Browse, and every row in it, recompose on every
        // single recomposition of App. That is the flashing.
        val rows: List<Entry?> = remember(entries, stack.size) {
            if (stack.size <= 1) listOf<Entry?>(null) + entries.orEmpty() else entries.orEmpty()
        }

        Browse(
            path = current.toHttpUrlOrNull()?.displayPath().orEmpty(),
            loading = entries == null,
            rows = rows,
            onPick = { entry ->
                when {
                    entry == null -> showSettings = true
                    entry.isDir -> stack = stack + entry.url.toString()
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

    private fun basicAuth(): String = Credentials.basic(
        prefs.getString("user", "").orEmpty(),
        prefs.getString("pass", "").orEmpty(),
    )

    @Composable
    private fun SettingsScreen(onSave: (HttpUrl) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        var url by rememberSaveable { mutableStateOf(prefs.getString("url", "").orEmpty()) }
        var user by rememberSaveable { mutableStateOf(prefs.getString("user", "").orEmpty()) }
        var pass by rememberSaveable { mutableStateOf(prefs.getString("pass", "").orEmpty()) }
        val firstField = remember { FocusRequester() }

        BackHandler { onCancel() }
        LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

        // OutlinedTextField reads androidx.compose.material3's MaterialTheme, not the
        // TV one, so without this nesting it renders in material3's stock palette.
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
                // The label and placeholder slots below take M3Text, not tv-material's
                // Text. OutlinedTextField colours its slots through compose-material3's
                // LocalContentColor; tv-material's Text reads tv-material's, a different
                // CompositionLocal, whose default is Color.Black — invisible here.
                Text("Server", fontSize = 32.sp, fontWeight = FontWeight.Light, color = ON_SURFACE)
                Spacer(Modifier.height(24.dp))
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
                Row {
                    Button(
                        onClick = {
                            val parsed = url.trim()
                                .let { if (it.endsWith("/")) it else "$it/" }
                                .toHttpUrlOrNull()
                            if (parsed == null) {
                                Toast.makeText(context, "That URL doesn't parse", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            prefs.edit()
                                .putString("url", parsed.toString())
                                .putString("user", user)
                                .putString("pass", pass)
                                .apply()
                            onSave(parsed)
                        },
                    ) { Text("Save") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onCancel) { Text("Cancel") }
                }
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
                    key = { _, entry -> entry?.url?.toString() ?: "settings" },
                ) { index, entry ->
                    ListItem(
                        selected = false,
                        onClick = { onPick(entry) },
                        headlineContent = {
                            Text(entry?.label ?: "Server settings", fontSize = 19.sp)
                        },
                        leadingContent = {
                            Text(
                                when {
                                    entry == null -> "⚙"
                                    entry.isDir -> "▣"
                                    else -> "▶"
                                },
                                color = if (entry == null) MUTED else ACCENT,
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
