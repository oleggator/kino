package xyz.utkin.kino

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.StateSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

const val PREFS = "kino"

/** textColorSecondary from the theme, for rows that are not files. */
private const val SECONDARY = 0xFF9AA0A6.toInt()

class MainActivity : Activity() {

    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences
    private lateinit var list: ListView

    /** The breadcrumb. There is no action bar to put it in, so it is a real view. */
    private lateinit var pathView: TextView

    /** Breadcrumb. `stack.last()` is the directory currently on screen. */
    private val stack = ArrayList<HttpUrl>()

    /** Parallel to the ListView rows. `null` is the "Server settings" row. */
    private var rows: List<Entry?> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        list = ListView(this)
        // Dividers are 2014. The focus block below is what separates the rows now.
        list.divider = null
        list.dividerHeight = 0
        list.selector = focusSelector()
        list.setDrawSelectorOnTop(false)
        list.clipToPadding = false
        list.setPadding(0, dp(8), 0, dp(24))
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = rows.getOrNull(position)
            when {
                entry == null -> askForServer()
                entry.isDir -> {
                    stack.add(entry.url)
                    load()
                }
                else -> {
                    Log.i(TAG, "opening ${entry.url}")
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_URL, entry.url.toString()),
                    )
                }
            }
        }
        pathView = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // TV safe area: panels overscan, and a list flush to the bezel loses rows.
                setPadding(dp(48), dp(32), dp(48), 0)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "KINO"
                        textSize = 13f
                        letterSpacing = 0.25f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(getColor(R.color.kino_accent))
                    },
                )
                addView(
                    pathView,
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .apply { bottomMargin = dp(20) },
                )
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            },
        )

        val root = savedServer()
        if (root == null) {
            render(emptyList())
            askForServer()
        } else {
            stack.add(root)
            load()
        }
    }

    override fun onBackPressed() {
        // ponytail: plain back stack. Move to OnBackInvokedCallback if targetSdk ever
        // goes past 35 and predictive back becomes mandatory.
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            load()
        } else {
            super.onBackPressed()
        }
    }

    private fun savedServer(): HttpUrl? = prefs.getString("url", null)?.toHttpUrlOrNull()

    private fun auth(): String =
        Credentials.basic(prefs.getString("user", "").orEmpty(), prefs.getString("pass", "").orEmpty())

    private fun load() {
        val url = stack.lastOrNull() ?: return
        val auth = auth()
        // The action bar is the breadcrumb; the ellipsis is the whole loading indicator.
        pathView.text = "${url.encodedPath} …"
        Thread {
            val result = runCatching { webdavList(client, url, auth) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                pathView.text = url.encodedPath
                result
                    .onSuccess { render(it) }
                    .onFailure {
                        // Surfacing this matters: a wrong path or a 401 is the most
                        // likely first-run problem and it is otherwise invisible.
                        Log.w(TAG, "listing failed for $url", it)
                        toast(it.message ?: "Listing failed")
                        render(emptyList())
                    }
            }
        }.start()
    }

    private fun render(entries: List<Entry>) {
        // The settings row lives at the root only, so there is always a way back into
        // the dialog even when the listing is empty or the credentials are wrong.
        rows = if (stack.size <= 1) listOf<Entry?>(null) + entries else entries
        val labels = rows.map { it?.label ?: "⚙  Server settings" }
        // simple_list_item_1 is a bare TextView at its root, so restyling what
        // super.getView() hands back is the whole row design — no layout, no holder.
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 19f
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    // The ⚙ row is navigation, not content — dim it.
                    setTextColor(if (rows.getOrNull(position) == null) SECONDARY else Color.WHITE)
                }
        }
    }

    private fun askForServer() {
        // The variation bits are meaningless without the class bits: TYPE_VARIATION_URI
        // on its own leaves the class as TYPE_NULL, which makes the field uneditable.
        val url = editText("https://host/remote.php/dav/files/me/", prefs.getString("url", ""))
            .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val user = editText("username", prefs.getString("user", ""))
        val pass = editText("password", prefs.getString("pass", ""))
            .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(url)
            addView(user)
            addView(pass)
        }

        AlertDialog.Builder(this)
            .setTitle("WebDAV server")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val parsed = url.text.toString().trim()
                    .let { if (it.endsWith("/")) it else "$it/" }
                    .toHttpUrlOrNull()
                if (parsed == null) {
                    toast("That URL doesn't parse")
                    askForServer()
                    return@setPositiveButton
                }
                prefs.edit()
                    .putString("url", parsed.toString())
                    .putString("user", user.text.toString())
                    .putString("pass", pass.text.toString())
                    .apply()
                stack.clear()
                stack.add(parsed)
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Accent block behind the focused row: the only cue a D-pad user gets. */
    private fun focusSelector() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply {
                setColor(getColor(R.color.kino_accent) and 0x33FFFFFF)
                cornerRadius = dp(10).toFloat()
            },
        )
        addState(StateSet.WILD_CARD, ColorDrawable(Color.TRANSPARENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun editText(hintText: String, value: String?) = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
