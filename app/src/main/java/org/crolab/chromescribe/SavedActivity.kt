package org.crolab.chromescribe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Fully offline library. Lists only transcripts that have real content on disk
 * and never touches the network to render the list itself.
 */
class SavedActivity : Activity() {

    private lateinit var cache: TranscriptStore
    private lateinit var list: ListView
    private lateinit var header: TextView
    private var items: List<TranscriptMeta> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cache = TranscriptStore(File(filesDir, "cache"))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this)
        bar.addView(Button(this).apply {
            text = getString(R.string.btn_sync)
            isAllCaps = false
            setOnClickListener { sync() }
        })
        root.addView(bar)
        header = TextView(this).apply {
            setPadding(24, 8, 24, 8)
            textSize = 12f
        }
        root.addView(header)
        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        list.setOnItemClickListener { _, _, position, _ ->
            val m = items.getOrNull(position) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, ReadActivity::class.java)
                    .putExtra("id", m.id).putExtra("title", m.title)
            )
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val m = items.getOrNull(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.dialog_remove_message, m.title))
                .setPositiveButton(R.string.btn_remove) { _, _ -> cache.remove(m.id); render() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun sync() {
        header.text = getString(R.string.status_syncing)
        val app = applicationContext
        AppExecutors.run("sync") {
            val report = Sync.run(app, cache, refresh = true)
            getSharedPreferences("chromescribe", MODE_PRIVATE).edit()
                .putString("last_sync", report.message.orEmpty()).apply()
            if (isFinishing || isDestroyed) return@run
            runOnUiThread { render() }
        }
    }

    private fun render() {
        items = cache.saved()
        val last = getSharedPreferences("chromescribe", MODE_PRIVATE).getString("last_sync", "") ?: ""
        header.text = if (last.isBlank()) resources.getQuantityString(R.plurals.saved_count, items.size, items.size)
        else resources.getQuantityString(R.plurals.saved_count_synced, items.size, items.size, last)
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            items.map { "${it.title}\n${it.channel}".trimEnd('\n') }
        )
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }
}