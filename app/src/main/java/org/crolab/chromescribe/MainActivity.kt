package org.crolab.chromescribe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import org.json.JSONArray

/** Transcript list. Always renders from disk, then refreshes in the background. */
class MainActivity : Activity() {

    private lateinit var listView: ListView
    private var items = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listView = ListView(this)
        setContentView(listView)
        title = "ChromeScribe"

        listView.setOnItemClickListener { _, _, position, _ ->
            val t = items.getJSONObject(position)
            startActivity(
                Intent(this, ReadActivity::class.java)
                    .putExtra("id", t.optInt("id").toString())
                    .putExtra("title", t.optString("title"))
            )
        }

        render(Api.cachedIndex(this))
        refresh()
    }

    private fun refresh() {
        Thread {
            try {
                val list = Api.sync(this)
                runOnUiThread { render(list) }
            } catch (e: Exception) {
                runOnUiThread {
                    // Offline is the normal case here, not an error worth shouting about.
                    if (items.length() == 0) toast("Offline and nothing cached yet")
                }
            }
        }.start()
    }

    private fun render(list: JSONArray) {
        items = list
        val rows = (0 until list.length()).map { i ->
            val t = list.getJSONObject(i)
            val offline = Api.contentFile(this, t.optInt("id").toString()).exists()
            buildString {
                append(t.optString("title"))
                append("\n")
                append(t.optString("channel", "Unknown"))
                append("  ·  ")
                append(t.optString("created_at_formatted", ""))
                if (offline) append("  ·  saved")
            }
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
