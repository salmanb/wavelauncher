package com.salman.wavelauncher

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

class DrawerActivity : BaseLauncherActivity() {

    private lateinit var search: EditText
    private lateinit var grid: GridView
    private var apps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private lateinit var adapter: GridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg(settings))
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        search = EditText(this).apply {
            hint = "Search all apps"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Theme.text(settings))
            setHintTextColor(Theme.text2(settings))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(4)
            verticalSpacing = dp(10)
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        Thread {
            val loaded = AppLoader.loadApps(this)
            runOnUiThread { apps = loaded; filter(""); }
        }.start()

        adapter = GridAdapter()
        grid.adapter = adapter

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filter(search.text.toString())
        })
    }

    private fun filter(q: String) {
        val ql = q.trim().lowercase()
        shown = if (ql.isEmpty()) apps else apps.filter { it.label.lowercase().contains(ql) }
        adapter.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    inner class GridAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, conv: View?, parent: ViewGroup): View {
            val v = conv ?: makeCell()
            val app = shown[p]
            val cell = v.tag as Cell
            cell.icon.setImageDrawable(app.icon)
            cell.label.text = app.label
            v.setOnClickListener {
                try { startActivity(app.launchIntent()) } catch (_: Exception) { }
                finish()
            }
            return v
        }

        private fun makeCell(): View {
            val v = LinearLayout(this@DrawerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val icon = android.widget.ImageView(this@DrawerActivity)
            val label = TextView(this@DrawerActivity).apply {
                textSize = 10.5f
                setTextColor(Theme.text2(settings))
                maxLines = 1
                gravity = Gravity.CENTER
            }
            v.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(6) })
            v.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            v.tag = Cell(icon, label)
            return v
        }
    }

    class Cell(val icon: android.widget.ImageView, val label: TextView)
}
