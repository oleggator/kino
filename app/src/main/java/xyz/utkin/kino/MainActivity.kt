package xyz.utkin.kino

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

const val PREFS = "kino"

class MainActivity : Activity() {

    private val client = OkHttpClient()
    private lateinit var prefs: SharedPreferences
    private lateinit var list: ListView

    /** Breadcrumb. `stack.last()` is the directory currently on screen. */
    private val stack = ArrayList<HttpUrl>()

    /** Parallel to the ListView rows. `null` is the "Server settings" row. */
    private var rows: List<Entry?> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        list = ListView(this)
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
        setContentView(list)

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
        title = url.encodedPath
        Thread {
            val result = runCatching { webdavList(client, url, auth) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
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
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
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

    private fun editText(hintText: String, value: String?) = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
