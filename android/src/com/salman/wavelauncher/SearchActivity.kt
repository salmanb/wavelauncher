package com.salman.wavelauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class SearchActivity : BaseLauncherActivity() {

    private lateinit var input: EditText
    private lateinit var results: ListView
    private lateinit var adapter: ResultAdapter
    private var apps: List<AppEntry> = emptyList()
    private var contacts: List<ContactEntry> = emptyList()
    private val rows = ArrayList<SearchRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg(settings))
            setPadding(dp(20), dp(48), dp(20), dp(0))
        }
        input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            textSize = 19f
            setTextColor(Theme.text(settings))
            setHintTextColor(Theme.text2(settings))
            background = null
        }
        results = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(0, dp(12), 0, dp(24))
            clipToPadding = false
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        adapter = ResultAdapter()
        results.adapter = adapter

        apps = AppLoader.loadApps(this)

        Thread {
            val c = AppLoader.loadContacts(this)
            runOnUiThread { contacts = c; rebuild() }
        }.start()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        rebuild()
    }

    private fun rebuild() {
        val q = input.text.toString().trim()
        rows.clear()
        if (q.isEmpty()) {
            apps.take(8).forEach { rows.add(SearchRow.AppRow(it)) }
        } else {
            val ql = q.lowercase()
            MathParser.evaluate(q)?.let { v ->
                rows.add(SearchRow.CalcRow(q, MathParser.format(v)))
            }
            apps.filter { it.label.lowercase().contains(ql) }.take(6).forEach { rows.add(SearchRow.AppRow(it)) }
            if (contacts.isEmpty() &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
            }
            contacts.filter { it.name.lowercase().contains(ql) }.take(4).forEach { rows.add(SearchRow.ContactRow(it)) }
            rows.add(SearchRow.WebRow(q))
        }
        adapter.notifyDataSetChanged()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        if (code == 1) {
            Thread {
                contacts = AppLoader.loadContacts(this)
                runOnUiThread { rebuild() }
            }.start()
        }
    }

    sealed class SearchRow {
        data class CalcRow(val expr: String, val result: String) : SearchRow()
        data class AppRow(val app: AppEntry) : SearchRow()
        data class ContactRow(val contact: ContactEntry) : SearchRow()
        data class WebRow(val query: String) : SearchRow()
    }

    inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
            val row = rows[p]
            val v = conv ?: makeRow()
            val vh = v.tag as RowVH
            when (row) {
                is SearchRow.CalcRow -> {
                    vh.icon.text = "="
                    vh.title.text = "${row.expr} = ${row.result}"
                    vh.sub.text = "tap to copy"
                    v.setOnClickListener { copyToClipboard(row.result) }
                }
                is SearchRow.AppRow -> {
                    vh.icon.text = row.app.letter
                    vh.title.text = row.app.label
                    vh.sub.text = ""
                    v.setOnClickListener {
                        try { startActivity(row.app.launchIntent()) } catch (_: Exception) { }
                        finish()
                    }
                }
                is SearchRow.ContactRow -> {
                    vh.icon.text = row.contact.name.take(1)
                    vh.title.text = row.contact.name
                    vh.sub.text = "view in contacts"
                    v.setOnClickListener {
                        try { startActivity(Intent(Intent.ACTION_VIEW, row.contact.uri)) } catch (_: Exception) { }
                        finish()
                    }
                }
                is SearchRow.WebRow -> {
                    vh.icon.text = "g"
                    vh.title.text = "Search \"${row.query}\" on the web"
                    vh.sub.text = "google.com"
                    v.setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=" + Uri.encode(row.query))))
                        } catch (_: Exception) { }
                        finish()
                    }
                }
            }
            return v
        }

        private fun makeRow(): View {
            val v = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            val iconBg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
            val icon = TextView(this@SearchActivity).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(LauncherPrefs.accent(settings))
                background = iconBg
            }
            val col = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(this@SearchActivity).apply {
                textSize = 15.5f
                setTextColor(Theme.text(settings))
                maxLines = 1
            }
            val sub = TextView(this@SearchActivity).apply {
                textSize = 11.5f
                setTextColor(Theme.text2(settings))
                maxLines = 1
            }
            col.addView(title)
            col.addView(sub)
            v.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) })
            v.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            v.tag = RowVH(icon, title, sub)
            return v
        }
    }

    class RowVH(val icon: TextView, val title: TextView, val sub: TextView)

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("result", text))
        android.widget.Toast.makeText(this, "Copied $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
